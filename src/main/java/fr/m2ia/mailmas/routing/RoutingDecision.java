package fr.m2ia.mailmas.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Résultat du routing : classement de tous les candidats avec score et justification.
 * Permet de traiter l'ambiguïté, l'absence d'agent adapté, la confiance et l'explicabilité.
 */
public record RoutingDecision(String strategyName, List<Candidate> ranking) {

    /** score dans [0, 1]. */
    public record Candidate(String agentId, double score, String reason) {
        public Candidate {
            if (score < 0 || score > 1) throw new IllegalArgumentException("score in [0,1]");
        }
    }

    public RoutingDecision {
        ranking = ranking.stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .toList();
    }

    public Optional<Candidate> best() {
        return ranking.isEmpty() ? Optional.empty() : Optional.of(ranking.get(0));
    }

    /** Aucun agent n'atteint le seuil : il faut passer par l'agent de repli. */
    public boolean noSuitableAgent(double threshold) {
        return best().map(c -> c.score() < threshold).orElse(true);
    }

    /** Les deux meilleurs sont trop proches : cas ambigu. */
    public boolean isAmbiguous(double margin) {
        return ranking.size() >= 2 && ranking.get(0).score() - ranking.get(1).score() < margin;
    }
}
