package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;

/**
 * Ce que l'interface affiche après un traitement.
 *
 * @param routing         décision complète du router (scores et justifications,
 *                        pour l'explicabilité)
 * @param selectedAgentId agent qui a finalement produit la proposition (ou
 *                        tenté de le faire)
 * @param ambiguous       vrai si les deux meilleurs agents étaient presque à
 *                        égalité :
 *                        l'interface le signale et propose de changer d'agent
 *                        (reroute)
 * @param draft           la proposition, ou null si status == FAILED
 * @param message         explication lisible en cas d'échec (LLM indisponible,
 *                        aucun agent...)
 */
public record ProcessingResult(
        Status status,
        Email email,
        RoutingDecision routing,
        String selectedAgentId,
        boolean ambiguous,
        DraftReply draft,
        String message) {

    public enum Status {
        PROPOSED, FAILED
    }

    public static ProcessingResult proposed(Email email, RoutingDecision routing, String agentId,
            boolean ambiguous, DraftReply draft) {
        return new ProcessingResult(Status.PROPOSED, email, routing, agentId, ambiguous, draft, null);
    }

    public static ProcessingResult failed(Email email, RoutingDecision routing, String agentId,
            String message) {
        return new ProcessingResult(Status.FAILED, email, routing, agentId, false, null, message);
    }
}