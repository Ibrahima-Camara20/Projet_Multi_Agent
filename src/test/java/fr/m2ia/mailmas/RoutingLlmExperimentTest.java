package fr.m2ia.mailmas;

import fr.m2ia.mailmas.agent.AgentFactory;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.DefaultAgentFactory;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.RoutingOutcome;
import fr.m2ia.mailmas.agent.SimpleMessageBus;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.LLMRequest;
import fr.m2ia.mailmas.llm.LLMResponse;
import fr.m2ia.mailmas.llm.OllamaLLMClient;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.LlmRoutingStrategy;
import fr.m2ia.mailmas.routing.MetadataRoutingStrategy;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.routing.SenderRoutingStrategy;
import fr.m2ia.mailmas.routing.WeightedRoutingStrategy;
import fr.m2ia.mailmas.support.ConfigProfiles;
import fr.m2ia.mailmas.support.LabeledEmails;
import fr.m2ia.mailmas.support.LabeledEmails.Labeled;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import fr.m2ia.mailmas.support.TestSupport.MapContactDirectory;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Expériences de routing avec le <b>vrai modèle local</b> (Ollama), sur les 21 e-mails étiquetés.
 *
 * <p>Compare, sur les mêmes données :</p>
 * <ol>
 * <li>la combinaison pondérée (sans LLM, référence : 90 % avec le faux LLM) ;</li>
 * <li>le routing par le LLM seul ;</li>
 * <li>la combinaison pondérée avec départage des cas ambigus par le LLM (configuration de la démo) ;</li>
 * <li>la combinaison pondérée avec refus des agents par mots-clés (76 %) ;</li>
 * <li>la combinaison pondérée avec auto-évaluation des agents par le LLM.</li>
 * </ol>
 * <p>Pour chaque configuration : exactitude, nombre d'appels au modèle et temps ajouté. La
 * rédaction des réponses n'est pas mesurée (faux générateur) : seul le coût du routing compte.</p>
 *
 * <p>Long (plusieurs minutes) : ignoré par défaut. Lancement :</p>
 * <pre>
 *   .\mvnw.cmd test "-Dtest=RoutingLlmExperimentTest" "-Dexperiences=true"
 *   .\mvnw.cmd test "-Dtest=RoutingLlmExperimentTest" "-Dexperiences=true" "-Dmodel=qwen3.5:9b"
 * </pre>
 * <p>Le tableau est affiché et enregistré dans {@code target/experiences-routing-<modèle>.md}.</p>
 */
class RoutingLlmExperimentTest {

    /** Cas où le refus par mots-clés se trompait (s5, t4, r4) ou avait raison (g3). */
    private static final List<String> REFUSAL_CASES = List.of("s5", "t4", "r4", "g3");

    @Test
    void compareRoutingConfigurationsWithRealModel() throws Exception {
        assumeTrue(Boolean.getBoolean("experiences"), "Expériences désactivées : ajouter -Dexperiences=true");
        AppConfig config = AppConfig.load(Path.of("config/app.properties"));
        assumeTrue(ollamaIsRunning(config.llmUrl()), "Ollama n'est pas lancé : expériences ignorées");

        String model = System.getProperty("model", config.llmModel());
        TimedLlm llm = new TimedLlm(new OllamaLLMClient(config.llmUrl(), model,
                Duration.ofSeconds(config.llmTimeoutSeconds()), true));

        long warmUp = System.nanoTime();
        llm.generate("Réponds simplement : ok", "", "ok"); // chargement du modèle, exclu des mesures
        long warmUpMs = (System.nanoTime() - warmUp) / 1_000_000;
        llm.reset();

        List<AgentProfile> profiles = ConfigProfiles.load(Path.of("config/agents"));
        MapContactDirectory contacts = LabeledEmails.contacts();
        List<Labeled> set = LabeledEmails.labeledSet();

        Map<String, Function<Void, RoutingAgent>> configs = new LinkedHashMap<>();
        configs.put("pondérée", v -> build(weighted(contacts), null, false, null, profiles));
        configs.put("LLM seul", v -> build(new LlmRoutingStrategy(llm), null, false, null, profiles));
        configs.put("pondérée + départage LLM", v -> build(weighted(contacts), new LlmRoutingStrategy(llm), false,
                null, profiles));
        configs.put("pondérée + refus mots-clés", v -> build(weighted(contacts), null, true, null, profiles));
        configs.put("pondérée + refus LLM", v -> build(weighted(contacts), null, false, llm, profiles));

        StringBuilder md = new StringBuilder();
        md.append("# Expériences de routing avec le vrai modèle\n\n")
                .append("Modèle : `").append(model).append("` — chargement initial : ")
                .append(warmUpMs).append(" ms (exclu des mesures)\n\n");

        Map<String, Map<String, String>> chosen = new LinkedHashMap<>();
        Map<String, Result> results = new LinkedHashMap<>();
        Map<String, String> rawLlmOnly = new LinkedHashMap<>();
        for (var entry : configs.entrySet()) {
            RoutingAgent router = entry.getValue().apply(null);
            Map<String, String> perEmail = new LinkedHashMap<>();
            int ok = 0;
            llm.reset();
            long maxMs = 0;
            for (Labeled l : set) {
                long before = llm.totalMs;
                RoutingOutcome out = router.process(l.email(), List.of(), 1);
                maxMs = Math.max(maxMs, llm.totalMs - before);
                String agent = out.agentId() == null ? "(aucun)" : out.agentId();
                boolean good = l.expected().equals(agent);
                ok += good ? 1 : 0;
                perEmail.put(l.email().id(), agent + (good ? " ✓" : " ✗"));
                if (entry.getKey().equals("LLM seul") && !llm.answers.isEmpty())
                    rawLlmOnly.put(l.email().id(), llm.answers.get(llm.answers.size() - 1));
            }
            chosen.put(entry.getKey(), perEmail);
            results.put(entry.getKey(), new Result(ok, set.size(), llm.calls, llm.totalMs, maxMs));
            System.out.printf(Locale.ROOT, "%-28s terminé : %d/%d, %d appels LLM, %.1f s%n",
                    entry.getKey(), ok, set.size(), llm.calls, llm.totalMs / 1000.0);
        }

        // Tableau détaillé
        md.append("## Agent choisi par e-mail\n\n| id | attendu |");
        configs.keySet().forEach(k -> md.append(' ').append(k).append(" |"));
        md.append("\n|---|---|");
        configs.keySet().forEach(k -> md.append("---|"));
        md.append('\n');
        for (Labeled l : set) {
            md.append("| ").append(l.email().id()).append(" | ").append(l.expected()).append(" |");
            for (String k : configs.keySet())
                md.append(' ').append(chosen.get(k).get(l.email().id())).append(" |");
            md.append('\n');
        }

        // Synthèse
        md.append("\n## Synthèse\n\n| Configuration | Exactitude | Appels au modèle | Temps ajouté total | Moyenne par e-mail | Pire e-mail |\n")
                .append("|---|---|---|---|---|---|\n");
        results.forEach((k, r) -> md.append(String.format(Locale.ROOT, "| %s | %d/%d (%.0f %%) | %d | %.1f s | %.1f s | %.1f s |%n",
                k, r.ok, r.total, 100.0 * r.ok / r.total, r.calls, r.totalMs / 1000.0,
                r.totalMs / 1000.0 / r.total, r.maxMs / 1000.0)));

        // Focus sur le refus
        md.append("\n## Refus : cas sensibles\n\n| id | attendu | refus mots-clés | refus LLM |\n|---|---|---|---|\n");
        for (Labeled l : set) {
            if (REFUSAL_CASES.contains(l.email().id())) {
                md.append("| ").append(l.email().id()).append(" | ").append(l.expected()).append(" | ")
                        .append(chosen.get("pondérée + refus mots-clés").get(l.email().id())).append(" | ")
                        .append(chosen.get("pondérée + refus LLM").get(l.email().id())).append(" |\n");
            }
        }

        md.append("\n## Réponses brutes du modèle (LLM seul)\n\n| id | réponse |\n|---|---|\n");
        rawLlmOnly.forEach((id, a) -> md.append("| ").append(id).append(" | `")
                .append(a.replace("|", "/").replace("\n", " ").replace("`", "'")).append("` |\n"));

        System.out.println("\n" + md);
        Path out = Path.of("target", "experiences-routing-" + model.replaceAll("[^A-Za-z0-9.-]", "_") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.println("Résultats enregistrés dans " + out.toAbsolutePath());

        assertEquals(configs.size(), results.size());
    }

    private record Result(int ok, int total, int calls, long totalMs, long maxMs) {
    }

    private static RoutingStrategy weighted(MapContactDirectory contacts) {
        return WeightedRoutingStrategy.builder()
                .add(new KeywordRoutingStrategy(), 1.0)
                .add(new SenderRoutingStrategy(contacts), 0.6)
                .add(new MetadataRoutingStrategy(), 0.8)
                .build();
    }

    /**
     * @param keywordRefusal agents qui refusent par mots-clés
     * @param refusalLlm     si non null : agents qui s'auto-évaluent avec ce LLM
     */
    private static RoutingAgent build(RoutingStrategy strategy, RoutingStrategy tieBreaker, boolean keywordRefusal,
            LLMClient refusalLlm, List<AgentProfile> profiles) {
        var bus = new SimpleMessageBus();
        var generator = new FakeDraftGenerator();
        AgentFactory factory;
        if (refusalLlm != null)
            factory = DefaultAgentFactory.withLlmSelfAssessment(generator, refusalLlm);
        else if (keywordRefusal)
            factory = DefaultAgentFactory.withKeywordSelfAssessment(generator, new InMemoryConversationMemory());
        else
            factory = new DefaultAgentFactory(generator);
        profiles.forEach(p -> bus.register(factory.create(p)));
        var router = new RoutingAgent(bus, strategy, tieBreaker, RoutingAgent.Settings.from(AppConfig.defaults()));
        bus.register(router);
        return router;
    }

    /** Decorator : compte les appels au modèle et mesure leur durée. */
    static final class TimedLlm implements LLMClient {
        private final LLMClient delegate;
        int calls;
        long totalMs;
        final List<String> answers = new ArrayList<>();

        TimedLlm(LLMClient delegate) {
            this.delegate = delegate;
        }

        void reset() {
            calls = 0;
            totalMs = 0;
            answers.clear();
        }

        @Override
        public LLMResponse generate(LLMRequest request) throws LLMException {
            long start = System.nanoTime();
            try {
                LLMResponse r = delegate.generate(request);
                answers.add(r.text());
                return r;
            } finally {
                calls++;
                totalMs += (System.nanoTime() - start) / 1_000_000;
            }
        }

        @Override
        public String modelName() {
            return delegate.modelName();
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
