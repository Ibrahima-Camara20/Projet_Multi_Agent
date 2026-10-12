package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.routing.RoutingDecision;

import java.util.List;

/**
 * Résultat d'une coordination par le {@link RoutingAgent}.
 *
 * @param status    PROPOSED (un agent a proposé), REFUSED (tous les agents essayés ont
 *                  refusé), FAILED (erreur : LLM indisponible, agent inconnu...)
 * @param decision  décision de routing (classement, scores, justifications)
 * @param agentId   agent qui a proposé, ou dernier agent sollicité (null si aucun)
 * @param ambiguous vrai si les deux meilleurs candidats étaient trop proches
 * @param draft     proposition (null sauf si PROPOSED)
 * @param reason    explication lisible en cas d'échec ou de refus
 * @param refusals  refus successifs rencontrés (« student : hors domaine »), pour l'explicabilité
 * @param note      information complémentaire (ex. départage d'un cas ambigu par le LLM)
 */
public record RoutingOutcome(
        Status status,
        RoutingDecision decision,
        String agentId,
        boolean ambiguous,
        DraftReply draft,
        String reason,
        List<String> refusals,
        String note) {

    public enum Status {
        PROPOSED, REFUSED, FAILED
    }

    public RoutingOutcome {
        refusals = refusals != null ? List.copyOf(refusals) : List.of();
    }

    public boolean proposed() {
        return status == Status.PROPOSED;
    }

    /** Texte d'explication à afficher : refus rencontrés et note éventuelle, ou raison de l'échec. */
    public String explanation() {
        StringBuilder sb = new StringBuilder();
        if (!proposed() && reason != null)
            sb.append(reason);
        if (note != null)
            append(sb, note);
        if (!refusals.isEmpty())
            append(sb, "Refus : " + String.join(" ; ", refusals));
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (!sb.isEmpty())
            sb.append(". ");
        sb.append(s);
    }
}
