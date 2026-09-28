package fr.m2ia.mailmas.llm;

/**
 * Faux LLM déterministe : permet à la personne A de développer et tester
 * les agents et le routing avant que le vrai client local soit prêt.
 */
public class StubLLMClient implements LLMClient {

    @Override
    public LLMResponse generate(LLMRequest request) {
        String last = request.messages().get(request.messages().size() - 1).content();
        String preview = last.length() > 60 ? last.substring(0, 60) + "..." : last;
        return new LLMResponse("[STUB] Réponse à : " + preview, modelName(), -1, -1);
    }

    @Override
    public String modelName() { return "stub"; }
}
