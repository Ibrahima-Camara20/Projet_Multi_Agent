package fr.m2ia.mailmas.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.m2ia.mailmas.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OllamaLLMClientTest {

    private final OllamaLLMClient client = new OllamaLLMClient("http://localhost:11434/", "qwen3.5:4b",
            Duration.ofSeconds(90), true);

    private final LLMRequest request = new LLMRequest(List.of(
            ChatMessage.system("Tu es un assistant."),
            ChatMessage.user("Bonjour")), new GenerationOptions(0.2, 100));

    @Test
    void buildsTheJsonExpectedByOllama() throws Exception {
        JsonNode json = new ObjectMapper().readTree(client.toJson(request));
        assertEquals("qwen3.5:4b", json.get("model").asText());
        assertFalse(json.get("stream").asBoolean());
        assertFalse(json.get("think").asBoolean());
        assertEquals("system", json.get("messages").get(0).get("role").asText());
        assertEquals("Bonjour", json.get("messages").get(1).get("content").asText());
        assertEquals(100, json.get("options").get("num_predict").asInt());
        assertEquals(OllamaLLMClient.CONTEXT_WINDOW, json.get("options").get("num_ctx").asInt());
    }

    @Test
    void readsTheAnswerAndTokenCounts() throws Exception {
        String body = """
                {"model":"qwen3.5:4b","message":{"role":"assistant","content":"  Bonjour !  "},
                 "done":true,"prompt_eval_count":12,"eval_count":4}""";
        LLMResponse r = client.fromJson(body);
        assertEquals("Bonjour !", r.text());
        assertEquals(12, r.promptTokens());
        assertEquals(4, r.completionTokens());
    }

    @Test
    void removesReasoningTags() throws Exception {
        String body = """
                {"message":{"content":"<think>je réfléchis...</think>Réponse finale"}}""";
        assertEquals("Réponse finale", client.fromJson(body).text());
    }

    /** Test réel : ignoré si Ollama n'est pas lancé. */
    @Test
    void realOllamaAnswers() throws Exception {
        assumeTrue(ollamaIsRunning(), "Ollama n'est pas lancé : test ignoré");
        OllamaLLMClient real = OllamaLLMClient.from(AppConfig.defaults());

        long start = System.currentTimeMillis();
        String answer = real.generate(
                "Tu rédiges des réponses courtes et polies à des e-mails universitaires.",
                "",
                "Bonjour, quelle est la date limite du rendu du TP ? Merci, Léa");
        long seconds = (System.currentTimeMillis() - start) / 1000;

        System.out.println("Réponse en " + seconds + " s :\n" + answer);
        assertFalse(answer.isBlank());
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