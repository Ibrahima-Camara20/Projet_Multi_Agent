package fr.m2ia.mailmas.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.m2ia.mailmas.config.AppConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Adapter : traduit une LLMRequest vers l'API /api/chat d'Ollama, et sa réponse
 * en LLMResponse.
 * Changer de moteur (llama.cpp, LM Studio...) = écrire un autre Adapter, sans
 * toucher au reste.
 */
public class OllamaLLMClient implements LLMClient {

    /**
     * Fenêtre de contexte demandée à Ollama : system prompt + contexte + historique
     * + e-mail + réponse.
     */
    static final int CONTEXT_WINDOW = 8192;

    private final URI chatUri;
    private final String model;
    private final Duration timeout;
    private final boolean disableThinking;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param disableThinking désactive le mode "raisonnement" des modèles qui en
     *                        ont un
     *                        (réponses beaucoup plus rapides). Mettre false si le
     *                        modèle refuse l'option.
     */
    public OllamaLLMClient(String baseUrl, String model, Duration timeout, boolean disableThinking) {
        this.chatUri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        this.model = model;
        this.timeout = timeout;
        this.disableThinking = disableThinking;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public static OllamaLLMClient from(AppConfig config) {
        return new OllamaLLMClient(config.llmUrl(), config.llmModel(),
                Duration.ofSeconds(config.llmTimeoutSeconds()), true);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public LLMResponse generate(LLMRequest request) throws LLMException {
        HttpRequest httpRequest = HttpRequest.newBuilder(chatUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new LLMException("Ollama injoignable sur " + chatUri + ". Vérifiez qu'Ollama est lancé.", e);
        } catch (HttpTimeoutException e) {
            throw new LLMException("Le modèle n'a pas répondu en " + timeout.toSeconds() + " s.", e);
        } catch (IOException e) {
            throw new LLMException("Erreur réseau avec Ollama : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Appel au LLM interrompu.", e);
        }

        if (response.statusCode() != 200) {
            throw new LLMException("Ollama a répondu " + response.statusCode() + " : " + response.body());
        }
        return fromJson(response.body());
    }

    /**
     * Construit le corps JSON attendu par /api/chat. Package-private pour les
     * tests.
     */
    String toJson(LLMRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        if (disableThinking) {
            root.put("think", false);
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.addObject()
                    .put("role", m.role().name().toLowerCase(Locale.ROOT))
                    .put("content", m.content());
        }
        root.putObject("options")
                .put("temperature", request.options().temperature())
                .put("num_predict", request.options().maxTokens())
                .put("num_ctx", CONTEXT_WINDOW);
        return root.toString();
    }

    /** Lit la réponse d'Ollama. Package-private pour les tests. */
    LLMResponse fromJson(String body) throws LLMException {
        try {
            JsonNode root = json.readTree(body);
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode()) {
                throw new LLMException("Réponse Ollama inattendue : " + body);
            }
            // Sécurité : certains modèles renvoient quand même leur raisonnement entre
            // balises
            String text = content.asText().replaceAll("(?s)<think>.*?</think>", "").strip();
            return new LLMResponse(text,
                    root.path("model").asText(model),
                    root.path("prompt_eval_count").asInt(-1),
                    root.path("eval_count").asInt(-1));
        } catch (JsonProcessingException e) {
            throw new LLMException("JSON invalide renvoyé par Ollama.", e);
        }
    }
}