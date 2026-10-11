package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Combinaison pondérée de plusieurs stratégies (Composite de Strategy).
 *
 * <p>
 * <b>Formule</b> : chaque stratégie i donne un score s<sub>i</sub> et a un poids
 * w<sub>i</sub> ∈ ]0, 1] qui exprime la confiance qu'on lui accorde. Le score final est un
 * « OU probabiliste » pondéré :
 * </p>
 *
 * <pre>
 *     score = 1 - Π (1 - w_i · s_i)
 * </pre>
 *
 * <p>
 * Pourquoi pas une moyenne pondérée ? Avec une moyenne, un indice fort mais isolé (mots-clés
 * très clairs d'un expéditeur inconnu) est dilué par les stratégies qui n'ont rien trouvé et
 * passe sous le seuil. Avec le OU probabiliste, un indice fort suffit, plusieurs indices
 * concordants se renforcent, et le résultat reste dans [0, 1] quel que soit le nombre de
 * stratégies : on peut en ajouter sans retoucher les seuils.
 * </p>
 *
 * <p>
 * La justification de chaque candidat reprend celle de chaque stratégie qui a contribué.
 * </p>
 */
public final class WeightedRoutingStrategy implements RoutingStrategy {

    /** Une stratégie et sa pondération. */
    public record Part(RoutingStrategy strategy, double weight) {
        public Part {
            Objects.requireNonNull(strategy, "strategy");
            if (weight <= 0 || weight > 1)
                throw new IllegalArgumentException("poids dans ]0, 1] : " + weight);
        }
    }

    private final List<Part> parts;
    private final String name;

    public WeightedRoutingStrategy(List<Part> parts) {
        if (parts == null || parts.isEmpty())
            throw new IllegalArgumentException("au moins une stratégie");
        this.parts = List.copyOf(parts);
        this.name = "weighted(" + this.parts.stream()
                .map(p -> String.format(Locale.ROOT, "%s×%.1f", p.strategy().name(), p.weight()))
                .collect(Collectors.joining(", ")) + ")";
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Part> parts() {
        return parts;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        Map<String, Double> remaining = new LinkedHashMap<>(); // Π (1 - w·s)
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        for (AgentProfile p : candidates) {
            if (p.fallback())
                continue;
            remaining.put(p.id(), 1.0);
            reasons.put(p.id(), new ArrayList<>());
        }

        for (Part part : parts) {
            RoutingDecision d = part.strategy().route(email, candidates);
            for (RoutingDecision.Candidate c : d.ranking()) {
                if (c.score() <= 0 || !remaining.containsKey(c.agentId()))
                    continue;
                remaining.merge(c.agentId(), 1 - part.weight() * c.score(), (a, b) -> a * b);
                reasons.get(c.agentId()).add(String.format(Locale.ROOT, "%s %.2f (%s)",
                        part.strategy().name(), c.score(), c.reason()));
            }
        }

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        remaining.forEach((id, rest) -> {
            List<String> r = reasons.get(id);
            ranking.add(new RoutingDecision.Candidate(id, TextMatching.clamp(1 - rest),
                    r.isEmpty() ? "aucun indice" : String.join(" ; ", r)));
        });
        return new RoutingDecision(name, ranking);
    }

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        public Builder add(RoutingStrategy strategy, double weight) {
            parts.add(new Part(strategy, weight));
            return this;
        }

        public WeightedRoutingStrategy build() {
            return new WeightedRoutingStrategy(parts);
        }
    }
}
