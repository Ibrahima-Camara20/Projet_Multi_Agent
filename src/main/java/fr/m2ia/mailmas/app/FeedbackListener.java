package fr.m2ia.mailmas.app;

/**
 * Observateur des décisions de l'utilisateur sur les propositions d'un agent (pattern Observer).
 * Permet de tenir une réputation des agents sans que le service la connaisse.
 */
@FunctionalInterface
public interface FeedbackListener {

    /** Ce que l'utilisateur a fait de la proposition d'un agent. */
    enum Outcome {
        /** Validée telle quelle. */
        ACCEPTED,
        /** Validée après modification du texte. */
        EDITED,
        /** Rejetée avec un commentaire (nouvelle proposition demandée au même agent). */
        REJECTED,
        /** L'utilisateur a confié l'e-mail à un autre agent. */
        REROUTED
    }

    void onFeedback(String agentId, Outcome outcome);
}