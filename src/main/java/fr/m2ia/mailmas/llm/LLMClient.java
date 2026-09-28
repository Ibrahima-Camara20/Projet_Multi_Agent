package fr.m2ia.mailmas.llm;

import java.util.List;

/**
 * Abstraction du modèle de langage local (Ollama, llama.cpp, LM Studio...).
 * Changer de modèle = changer d'implémentation (Adapter), sans toucher aux agents.
 */
public interface LLMClient {

    LLMResponse generate(LLMRequest request) throws LLMException;

    String modelName();

    /** Raccourci conservant la signature prévue initialement dans le plan. */
    default String generate(String systemPrompt, String context, String email) throws LLMException {
        String user = (context == null || context.isBlank())
                ? email
                : "Contexte :\n" + context + "\n\nE-mail à traiter :\n" + email;
        return generate(new LLMRequest(
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(user)),
                GenerationOptions.defaults())).text();
    }
}
