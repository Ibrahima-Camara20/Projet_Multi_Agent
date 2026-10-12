package fr.m2ia.mailmas.generation;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.ContextQuery;
import fr.m2ia.mailmas.context.FileContextProvider;
import fr.m2ia.mailmas.llm.OllamaLLMClient;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.persistence.JsonAgentRepository;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scénario SuperMAS du sujet avec le vrai modèle, la vraie configuration et le vrai contexte.
 * Génère deux réponses à l'e-mail d'Anna : sans contexte puis avec contexte, pour les comparer.
 * Ignoré si Ollama n'est pas lancé.
 */
class SuperMasScenarioTest {

    @Test
    void annaEmailWithoutThenWithContext() throws Exception {
        assumeTrue(ollamaIsRunning(), "Ollama n'est pas lancé : test ignoré");

        AppConfig config = AppConfig.load(Path.of("config/app.properties"));
        AgentProfile agent = new JsonAgentRepository(config.agentsDir())
                .findById("research-supermas").orElseThrow();
        Email email = new Email("001", "supermas-wp3", "anna.muller@partner-lab.eu", List.of("moi@univ.fr"),
                "SuperMAS - WP3 deliverable",
                "Hi, could you confirm the date for the WP3 deliverable and whether you can also "
                        + "take care of the evaluation section? Best, Anna",
                null, null);

        ContextProvider noContext = new ContextProvider() {
            public boolean supports(String sourceId) { return false; }
            public List<ContextItem> retrieve(ContextQuery query) { return List.of(); }
        };

        String without = run(config, noContext, agent, email);
        String with = run(config, new FileContextProvider(Path.of("config/context")), agent, email);

        System.out.println("\n===== SANS CONTEXTE =====\n" + without);
        System.out.println("\n===== AVEC CONTEXTE =====\n" + with);
        assertFalse(with.isBlank());
    }

    private static String run(AppConfig config, ContextProvider context, AgentProfile agent, Email email)
            throws Exception {
        var memory = new InMemoryConversationMemory();
        memory.recordReceived(email);
        var generator = DefaultDraftGenerator.from(config, OllamaLLMClient.from(config), context, memory);

        long start = System.currentTimeMillis();
        DraftReply draft = generator.generate(email, agent, List.of(), 1);
        long seconds = (System.currentTimeMillis() - start) / 1000;
        return "(" + seconds + " s) Objet : " + draft.subject() + "\n\n" + draft.body();
    }

    private static boolean ollamaIsRunning() {
        try {
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}