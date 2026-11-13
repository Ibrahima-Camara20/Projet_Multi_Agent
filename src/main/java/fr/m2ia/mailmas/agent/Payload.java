package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.review.Finding;

import java.util.List;

/**
 * Contenu typé d'un AgentMessage. Interface scellée : un switch sur Payload
 * oblige le compilateur à vérifier que tous les cas sont traités.
 */
public sealed interface Payload {

    /**
     * Demande de rédaction ; previousFeedback contient les rejets précédents de
     * l'utilisateur.
     */
    record DraftRequest(Email email, List<UserFeedback> previousFeedback, int attempt) implements Payload {
        public DraftRequest {
            previousFeedback = previousFeedback != null ? List.copyOf(previousFeedback) : List.of();
        }
    }

    record DraftProposal(DraftReply draft) implements Payload {
    }

    record Refusal(String reason) implements Payload {
    }

    record Failure(String reason) implements Payload {
    }

    /** Demande de relecture adressée à l'agent critique : l'e-mail reçu et la proposition à vérifier. */
    record ReviewRequest(Email email, DraftReply draft) implements Payload {
    }

    /** Résultat de la relecture (liste vide : rien à signaler). */
    record ReviewReport(List<Finding> findings) implements Payload {
        public ReviewReport {
            findings = findings != null ? List.copyOf(findings) : List.of();
        }
    }
}