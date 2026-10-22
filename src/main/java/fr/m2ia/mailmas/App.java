package fr.m2ia.mailmas;

import fr.m2ia.mailmas.agent.AgentMessage;
import fr.m2ia.mailmas.agent.Payload;
import fr.m2ia.mailmas.app.ProcessingResult;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.OllamaLLMClient;
import fr.m2ia.mailmas.llm.StubLLMClient;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.memory.FileConversationMemory;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Démonstrateur en console : fait passer les e-mails de config/inbox dans tout le système.
 *
 *   App --reset         efface l'historique enregistré (data/conversations) avant de commencer
 *   App --fresh         n'utilise pas l'historique enregistré (mémoire temporaire, rien n'est écrit)
 * <pre>
 *   App                 tous les e-mails, avec Ollama
 *   App 001 005         seulement ces e-mails
 *   App --stub          sans modèle (StubLLMClient) : vérifie le routing et la chaîne en quelques secondes
 * </pre>
 */
public final class App {

    private static final String LINE = "=".repeat(78);

    public static void main(String[] args) throws Exception {
        boolean stub = Arrays.asList(args).contains("--stub");
        Set<String> only = Arrays.stream(args).filter(a -> !a.startsWith("--")).collect(Collectors.toSet());

        AppConfig config = AppConfig.load(Path.of("config/app.properties"));
        LLMClient llm;
        if (stub) {
            llm = new StubLLMClient();
        } else {
            if (!ollamaIsRunning(config.llmUrl())) {
                System.err.println("Ollama est injoignable sur " + config.llmUrl()
                        + ". Lancez Ollama, ou utilisez --stub pour tester sans modèle.");
                System.exit(1);
            }
            llm = OllamaLLMClient.from(config);
        }

        boolean fresh = Arrays.asList(args).contains("--fresh");
        ConversationMemory memory;
        if (fresh) {
            memory = new InMemoryConversationMemory();
        } else {
            FileConversationMemory fileMemory = FileConversationMemory.from(config);
            if (Arrays.asList(args).contains("--reset")) {
                fileMemory.clear();
            }
            memory = fileMemory;
        }
        MailAssistant assistant = MailAssistant.create(config, llm, !stub, memory);
        assistant.bus().addListener(App::printMessage);

        System.out.println("Modèle : " + llm.modelName() + "   |   routing : " + assistant.router().strategy().name());
        System.out.println("Mémoire : " + (fresh ? "temporaire (--fresh)" : "data/conversations"));
        System.out.println("Agents : " + assistant.agents().findAll().stream()
                .map(p -> p.id() + (p.fallback() ? " (repli)" : "")).collect(Collectors.joining(", ")));

        List<Email> emails = assistant.inbox().findAll().stream()
                .filter(e -> only.isEmpty() || only.contains(e.id()))
                .toList();
        for (Email email : emails) {
            System.out.println("\n" + LINE);
            System.out.println("[" + email.id() + "] " + email.subject());
            System.out.println("De : " + email.from());
            System.out.println(LINE);

            long start = System.currentTimeMillis();
            ProcessingResult result = assistant.service().submit(email);
            long seconds = (System.currentTimeMillis() - start) / 1000;

            printRouting(result.routing());
            System.out.printf(Locale.ROOT, "%n-> Agent : %s   |   ambigu : %s   |   %s   |   %d s%n",
                    result.selectedAgentId(), result.ambiguous() ? "OUI" : "non", result.status(), seconds);
            if (result.message() != null) {
                System.out.println("   Note : " + result.message());
            }
            if (result.draft() != null) {
                System.out.println("\n--- Proposition : " + result.draft().subject() + " ---");
                System.out.println(result.draft().body());
            }
        }
        System.out.println("\n" + emails.size() + " e-mail(s) traité(s).");
    }

    /** Les 3 meilleurs candidats, avec leur score et la justification. */
    private static void printRouting(RoutingDecision decision) {
        if (decision == null) {
            return;
        }
        System.out.println("Routing :");
        decision.ranking().stream().limit(3).forEach(c -> System.out.printf(Locale.ROOT,
                "   %.2f  %-18s %s%n", c.score(), c.agentId(), c.reason()));
    }

    /** Journal des échanges entre agents (Observer sur le MessageBus). */
    private static void printMessage(AgentMessage m) {
        String detail = switch (m.payload()) {
            case Payload.Refusal r -> " : " + r.reason();
            case Payload.Failure f -> " : " + f.reason();
            case Payload.DraftRequest r -> " (proposition n°" + r.attempt() + ")";
            case Payload.DraftProposal p -> "";
        };
        System.out.println("   [bus] " + m.performative() + " " + m.sender() + " -> " + m.receiver() + detail);
    }

    private static boolean ollamaIsRunning(String baseUrl) {
        try {
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/tags"))
                            .timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}