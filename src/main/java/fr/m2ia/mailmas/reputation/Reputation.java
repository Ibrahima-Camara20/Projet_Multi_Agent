package fr.m2ia.mailmas.reputation;

/**
 * Réputation d'un agent, selon le modèle vu en cours (agrégation de type Beta) :
 * <pre>
 *   R = (1 + p) / (2 + p + n)
 * </pre>
 * où p compte les interactions réussies et n les échecs. Sans aucune donnée, R = 0,5 :
 * le système n'a pas d'avis. Plus les validations s'accumulent, plus R s'approche de 1.
 *
 * <p>Ici, une interaction est une proposition jugée par l'utilisateur : validée telle quelle
 * (succès), validée après modification (moitié succès, moitié échec), rejetée ou confiée à un
 * autre agent (échec).</p>
 *
 * @param positive p, somme des succès
 * @param negative n, somme des échecs
 */
public record Reputation(double positive, double negative) {

    public static final Reputation NONE = new Reputation(0, 0);

    public Reputation {
        if (positive < 0 || negative < 0) {
            throw new IllegalArgumentException("compteurs négatifs");
        }
    }

    /** R = (1 + p) / (2 + p + n), entre 0 et 1. */
    public double score() {
        return (1 + positive) / (2 + positive + negative);
    }

    /** Nombre de propositions jugées. */
    public double judged() {
        return positive + negative;
    }

    public Reputation plus(double p, double n) {
        return new Reputation(positive + p, negative + n);
    }
}
