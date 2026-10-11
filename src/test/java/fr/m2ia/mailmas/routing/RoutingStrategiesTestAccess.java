package fr.m2ia.mailmas.routing;

import java.util.Map;

/** Expose aux tests d'autres packages la stratégie à scores fixés. */
public final class RoutingStrategiesTestAccess {

    private RoutingStrategiesTestAccess() {
    }

    /** Stratégie qui renvoie des scores prédéfinis (0 pour les agents non cités). */
    public static RoutingStrategy fixed(String name, Map<String, Double> scores) {
        return RoutingStrategiesTest.fixed(name, scores);
    }

    /** Faux LLM qui renvoie la réponse calculée par la fonction. */
    public static fr.m2ia.mailmas.llm.LLMClient llm(
            java.util.function.Function<fr.m2ia.mailmas.llm.LLMRequest, String> answer) {
        return RoutingStrategiesTest.llm(answer);
    }
}
