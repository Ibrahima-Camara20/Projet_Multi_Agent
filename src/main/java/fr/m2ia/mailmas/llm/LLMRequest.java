package fr.m2ia.mailmas.llm;

import java.util.List;

/** Requête complète : system prompt, historique éventuel, message courant. */
public record LLMRequest(List<ChatMessage> messages, GenerationOptions options) {
    public LLMRequest {
        messages = List.copyOf(messages);
        options = options != null ? options : GenerationOptions.defaults();
    }
}
