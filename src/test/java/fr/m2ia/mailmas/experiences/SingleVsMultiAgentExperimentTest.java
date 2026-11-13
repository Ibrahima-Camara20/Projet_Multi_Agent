package fr.m2ia.mailmas.experiences;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.m2ia.mailmas.MailAssistant;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.app.ProcessingResult;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.FileContextProvider;
import fr.m2ia.mailmas.experiences.ReplyGrader.Expectation;
import fr.m2ia.mailmas.experiences.ReplyGrader.Grade;
import fr.m2ia.mailmas.generation.DefaultDraftGenerator;
import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.OllamaLLMClient;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.persistence.JsonInbox;
import fr.m2ia.mailmas.support.ConfigProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L'hypothèse du sujet, testée directement : une organisation d'agents spécialisés fait-elle mieux
 * qu'un agent unique avec le même modèle local ?
 *
 * <p>Trois configurations, sur les mêmes e-mails, avec le même modèle et le même budget de contexte :</p>
 * <ol>
 *   <li><b>multi-agent</b> : le système complet (routing, agent spécialisé, son contexte) ;</li>
 *   <li><b>agent unique</b> : un seul agent, qui reçoit les consignes de tous les agents et a accès
 *       à tous les documents de contexte ;</li>
 *   <li><b>multi-agent sans contexte</b> : le bon agent, mais sans documents (influence du contexte).</li>
 * </ol>
 * <p>Chaque proposition passe par {@link ReplyGrader}. Toutes les propositions sont recopiées en
 * annexe du rapport généré, pour la relecture humaine.</p>
 *
 * <p>Long (de l'ordre de 30 s par proposition) : ignoré par défaut. Lancement :</p>
 * <pre>
 *   .\mvnw.cmd test "-Dtest=SingleVsMultiAgentExperimentTest" "-Dexperiences=true"
 *   .\mvnw.cmd test "-Dtest=SingleVsMultiAgentExperimentTest" "-Dexperiences=true" "-Druns=1"
 * </pre>
 * <p>Options : {@code -Druns=} (essais par e-mail, 3 par défaut), {@code -Dtimeout=} (délai d'un appel
 * au modèle en secondes, 300 par défaut), {@code -Dmodel=}, {@code -Dconfigs=multi,critique,unique,sans-contexte},
 * {@code -Demails=n15,004} (seulement ces e-mails).</p>
 * <p>Résultats : {@code target/experiences-mono-multi-<modèle>.md}.</p>
 */
class SingleVsMultiAgentExperimentTest {

    static final Path EXPECTATIONS = Path.of("src/test/resources/experiences/attendus.json");
    static final Path EXTRA_EMAILS = Path.of("src/test/resources/experiences/emails");

    static final String MULTI = "multi-agent";
    static final String SINGLE = "agent unique";
    static final String NO_CONTEXT = "multi-agent sans contexte";
    static final String CRITIC = "multi-agent + critique";
    static final List<String> ALL = List.of(MULTI, CRITIC, SINGLE, NO_CONTEXT);

    /** Une proposition évaluée. */
    record Run(String config, Email email, int run, String agent, boolean routingOk, String body, long ms,
               Grade grade, String error, String note) {
    }

    @Test
    void compareSingleAgentWithMultiAgent() throws Exception {
        assumeTrue(Boolean.getBoolean("experiences"), "Expériences désactivées : ajouter -Dexperiences=true");
        AppConfig config = AppConfig.load(Path.of("config/app.properties"));
        assumeTrue(ollamaIsRunning(config.llmUrl()), "Ollama n'est pas lancé : expériences ignorées");

        String model = System.getProperty("model", config.llmModel());
        int runs = Integer.getInteger("runs", 3);
        List<String> configs = selectedConfigs(System.getProperty("configs", ""));
        // Même délai pour toutes les configurations ; plus long que celui de l'application (90 s) pour que
        // l'agent unique, plus lent, puisse répondre : on mesure sa qualité ET on compte ses dépassements.
        int timeout = Integer.getInteger("timeout", 300);
        LLMClient llm = new OllamaLLMClient(config.llmUrl(), model, Duration.ofSeconds(timeout), true);
        llm.generate("Réponds simplement : ok", "", "ok");             // chargement du modèle, exclu des mesures

        Map<String, Expectation> expectations = loadExpectations();
        List<String> only = System.getProperty("emails", "").isBlank() ? List.of()
                : List.of(System.getProperty("emails").split("\\s*,\\s*"));
        List<Email> emails = loadEmails().stream().filter(e -> expectations.containsKey(e.id()))
                .filter(e -> only.isEmpty() || only.contains(e.id())).toList();
        List<AgentProfile> profiles = ConfigProfiles.load(config.agentsDir());
        Path contextRoot = config.agentsDir().resolveSibling("context");
        String contextText = readAll(contextRoot);
        AgentProfile single = singleAgent(profiles);

        List<Run> results = new ArrayList<>();
        for (Email email : emails) {
            Expectation exp = expectations.get(email.id());
            String sources = email.subject() + "\n" + email.body() + "\n" + contextText;
            AgentProfile right = profiles.stream().filter(p -> p.id().equals(exp.expectedAgents().get(0)))
                    .findFirst().orElseThrow();
            AgentProfile rightWithoutContext = new AgentProfile(right.id(), right.name(), right.description(),
                    right.systemPrompt(), List.of(), right.routingHints(), right.fallback());

            for (int run = 1; run <= runs; run++) {
                // Système complet, routing compris (avec départage par le LLM, comme dans la démo), sans puis avec critique
                if (configs.contains(MULTI)) {
                    results.add(system(MULTI, false, email, run, exp, sources, config, llm));
                }
                if (configs.contains(CRITIC)) {
                    results.add(system(CRITIC, true, email, run, exp, sources, config, llm));
                }
                // Agent unique : toutes les consignes, tous les documents, même budget de contexte
                if (configs.contains(SINGLE)) {
                    results.add(generate(SINGLE, email, run, single, exp, sources, config, llm, contextRoot));
                }
                // Bon agent sans contexte
                if (configs.contains(NO_CONTEXT)) {
                    results.add(generate(NO_CONTEXT, email, run, rightWithoutContext, exp, sources, config, llm, contextRoot));
                }

                System.out.printf(Locale.ROOT, "%s essai %d/%d terminé%n", email.id(), run, runs);
            }
        }

        String md = report(model, runs, config.llmTimeoutSeconds(), emails, results, configs);
        System.out.println("\n" + md);
        Path out = Path.of("target", "experiences-mono-multi-" + model.replaceAll("[^A-Za-z0-9.-]", "_") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.println("Résultats enregistrés dans " + out.toAbsolutePath());

        assertFalse(results.isEmpty());
    }

    // ------------------------------------------------------------------ configurations

    /**
     * L'agent unique reçoit les consignes de tous les agents, présentées par cas, et toutes les
     * sources de contexte. Construit automatiquement à partir des profils : pas de réglage à la main
     * en sa défaveur.
     */
    static AgentProfile singleAgent(List<AgentProfile> profiles) {
        StringBuilder prompt = new StringBuilder("""
                Tu traites seul tous les e-mails de l'utilisateur, quel que soit l'expéditeur.
                Repère de quel type d'e-mail il s'agit, puis applique les consignes du cas correspondant.
                """);
        List<String> sources = new ArrayList<>();
        for (AgentProfile p : profiles) {
            prompt.append("\n### Cas « ").append(p.name()).append(" » : ").append(p.description()).append('\n')
                    .append(p.systemPrompt().strip()).append('\n');
            p.contextSources().stream().filter(s -> !sources.contains(s)).forEach(sources::add);
        }
        return new AgentProfile("single", "Agent unique", "Traite tous les e-mails", prompt.toString(), sources,
                null, false);
    }

    /** Système complet (routing + agent spécialisé), avec ou sans agent critique. */
    private static Run system(String label, boolean critic, Email email, int run, Expectation exp, String sources,
                              AppConfig config, LLMClient llm) {
        long t0 = System.nanoTime();
        try {
            MailAssistant assistant = MailAssistant.create(config, llm, true, new InMemoryConversationMemory(), critic);
            ProcessingResult r = assistant.service().submit(email);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r.status() != ProcessingResult.Status.PROPOSED) {
                return new Run(label, email, run, r.selectedAgentId(), false, "", ms, null, r.message(), null);
            }
            String body = r.draft().body();
            String note = r.message() != null && r.message().contains("Relecture")
                    ? r.message().substring(r.message().indexOf("Relecture")) : null;
            return new Run(label, email, run, r.selectedAgentId(), exp.expectedAgents().contains(r.selectedAgentId()),
                    body, ms, ReplyGrader.grade(body, exp, sources), null, note);
        } catch (Exception e) {
            return failed(label, email, run, t0, e);
        }
    }

    /** -Dconfigs=multi,critique,unique,sans-contexte (par défaut : les quatre). */
    static List<String> selectedConfigs(String property) {
        if (property == null || property.isBlank()) {
            return ALL;
        }
        List<String> keys = List.of(property.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        List<String> out = new ArrayList<>();
        if (keys.contains("multi")) out.add(MULTI);
        if (keys.contains("critique")) out.add(CRITIC);
        if (keys.contains("unique")) out.add(SINGLE);
        if (keys.contains("sans-contexte")) out.add(NO_CONTEXT);
        return out.isEmpty() ? ALL : out;
    }

    private static Run generate(String config, Email email, int run, AgentProfile agent, Expectation exp,
                                String sources, AppConfig appConfig, LLMClient llm, Path contextRoot) {
        long t0 = System.nanoTime();
        try {
            ContextProvider context = new FileContextProvider(contextRoot);
            DraftGenerator generator = DefaultDraftGenerator.from(appConfig, llm, context, new InMemoryConversationMemory());
            String body = generator.generate(email, agent, List.of(), 1).body();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new Run(config, email, run, agent.id(), true, body, ms, ReplyGrader.grade(body, exp, sources), null, null);
        } catch (Exception e) {
            return failed(config, email, run, t0, e);
        }
    }

    private static Run failed(String config, Email email, int run, long t0, Exception e) {
        return new Run(config, email, run, "?", false, "", (System.nanoTime() - t0) / 1_000_000, null,
                e.getClass().getSimpleName() + " : " + e.getMessage(), null);
    }

    // ------------------------------------------------------------------ rapport

    static String report(String model, int runs, int appTimeout, List<Email> emails, List<Run> results,
                         List<String> configs) {
        StringBuilder md = new StringBuilder();
        md.append("# Agent unique ou organisation multi-agent ?\n\n")
                .append("Modèle : `").append(model).append("` — ").append(emails.size()).append(" e-mails × ")
                .append(runs).append(" essai(s) × ").append(configs.size()).append(" configuration(s)\n\n");

        md.append("## Synthèse\n\n")
                .append("| Configuration | Bon agent | Faits attendus | Formule interdite | Fuite d'un autre domaine | Bonne langue | Bon registre (tu/vous) | Date/heure sans source | [À COMPLÉTER] par réponse | Temps moyen | Plus de ").append(appTimeout).append(" s | Échecs | Corrigées par le critique |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (String c : configs) {
            List<Run> all = results.stream().filter(r -> r.config.equals(c)).toList();
            List<Run> ok = all.stream().filter(r -> r.grade != null).toList();
            List<Run> withFacts = ok.stream().filter(r -> r.grade.factsTotal() > 0).toList();
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %.1f | %.1f s | %s | %d | %s |%n", c,
                    c.equals(MULTI) || c.equals(CRITIC) ? pct(all.stream().filter(Run::routingOk).count(), all.size()) : "—",
                    withFacts.isEmpty() ? "—" : pctD(withFacts.stream().mapToDouble(r -> r.grade.factRate()).average().orElse(0)),
                    pct(ok.stream().filter(r -> !r.grade.forbiddenFound().isEmpty()).count(), ok.size()),
                    pct(ok.stream().filter(r -> !r.grade.leaksFound().isEmpty()).count(), ok.size()),
                    pct(ok.stream().filter(r -> r.grade.languageOk()).count(), ok.size()),
                    pct(ok.stream().filter(r -> r.grade.addressOk()).count(), ok.size()),
                    pct(ok.stream().filter(r -> !r.grade.unsupported().isEmpty()).count(), ok.size()),
                    ok.stream().mapToInt(r -> r.grade.placeholders()).average().orElse(0),
                    all.stream().mapToLong(Run::ms).average().orElse(0) / 1000.0,
                    pct(all.stream().filter(r -> r.ms > appTimeout * 1000L).count(), all.size()),
                    all.size() - ok.size(),
                    c.equals(CRITIC) ? pct(all.stream().filter(r -> r.note != null).count(), all.size()) : "—"));
        }
        md.append("\nFaits attendus : part moyenne des faits trouvés, sur les e-mails qui en attendent. Les autres "
                + "colonnes : part des réponses concernées. Temps du multi-agent : routing compris. « Plus de "
                + appTimeout + " s » : réponses qui auraient échoué avec le délai de l'application.\n");

        md.append("\n## Par e-mail (faits trouvés en moyenne ; signalements sur tous les essais)\n\n| E-mail |");
        configs.forEach(c -> md.append(' ').append(c).append(" |"));
        md.append("\n|---|").append("---|".repeat(configs.size())).append('\n');
        for (Email e : emails) {
            md.append("| ").append(e.id()).append(' ').append(e.subject().replace("|", "/")).append(" |");
            for (String c : configs) {
                List<Run> all = results.stream().filter(r -> r.config.equals(c) && r.email.id().equals(e.id())).toList();
                List<Run> rs = all.stream().filter(r -> r.grade != null).toList();
                long failures = all.size() - rs.size();
                String facts = rs.isEmpty() ? "échec"
                        : (rs.get(0).grade.factsTotal() == 0 ? "—"
                        : pctD(rs.stream().mapToDouble(r -> r.grade.factRate()).average().orElse(0)))
                        + (failures > 0 ? " (" + failures + " échec)" : "");
                String flags = rs.stream().flatMap(r -> Stream.concat(Stream.concat(
                                r.grade.forbiddenFound().stream().map(f -> "« " + f + " »"),
                                r.grade.leaksFound().stream().map(l -> "fuite " + l)),
                                Stream.concat(r.grade.unsupported().stream().map(d -> "sans source " + d),
                                        Stream.concat(r.grade.languageOk() ? Stream.<String>empty() : Stream.of("langue"),
                                                r.grade.addressOk() ? Stream.<String>empty() : Stream.of("registre")))))
                        .distinct().collect(Collectors.joining(", "));
                md.append(' ').append(facts).append(flags.isEmpty() ? "" : " — " + flags).append(" |");
            }
            md.append('\n');
        }

        md.append("\n## Annexe : toutes les propositions (relecture humaine)\n");
        for (Run r : results) {
            md.append("\n### ").append(r.email.id()).append(" — ").append(r.config).append(" — essai ").append(r.run)
                    .append(String.format(Locale.ROOT, " (%s, %.1f s)%n%n", r.agent, r.ms / 1000.0));
            if (r.error != null) {
                md.append("**Échec** : ").append(r.error).append('\n');
                continue;
            }
            Grade g = r.grade;
            if (r.note != null) md.append("*").append(r.note).append("*\n\n");
            md.append(String.format(Locale.ROOT, "Faits : %d/%d", g.factsFound(), g.factsTotal()));
            if (!g.missingFacts().isEmpty()) md.append(" (manque : ").append(String.join(" ; ", g.missingFacts())).append(')');
            if (!g.forbiddenFound().isEmpty()) md.append(" — interdit : ").append(g.forbiddenFound());
            if (!g.leaksFound().isEmpty()) md.append(" — fuite : ").append(g.leaksFound());
            if (!g.unsupported().isEmpty()) md.append(" — sans source : ").append(g.unsupported());
            if (!g.languageOk()) md.append(" — mauvaise langue");
            if (!g.addressOk()) md.append(" — mauvais registre (tu/vous)");
            md.append("\n\n```\n").append(r.body.strip()).append("\n```\n");
        }
        return md.toString();
    }

    private static String pct(long n, long total) {
        return total == 0 ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * n / total);
    }

    private static String pctD(double rate) {
        return String.format(Locale.ROOT, "%.0f %%", 100.0 * rate);
    }

    // ------------------------------------------------------------------ données

    static Map<String, Expectation> loadExpectations() throws IOException {
        List<Expectation> list = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(EXPECTATIONS.toFile(), new TypeReference<List<Expectation>>() { });
        Map<String, Expectation> map = new LinkedHashMap<>();
        list.forEach(e -> map.put(e.emailId(), e));
        return map;
    }

    /** E-mails de la démo (config/inbox) et, s'il existe, le jeu de test supplémentaire. */
    static List<Email> loadEmails() {
        List<Email> all = new ArrayList<>(new JsonInbox(Path.of("config/inbox")).findAll());
        all.addAll(new JsonInbox(EXTRA_EMAILS).findAll());
        return all;
    }

    private static String readAll(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(root)) {
            StringBuilder sb = new StringBuilder();
            for (Path f : files.filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt")).toList()) {
                sb.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            }
            return sb.toString();
        }
    }

    private static boolean ollamaIsRunning(String baseUrl) {
        try {
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }
}