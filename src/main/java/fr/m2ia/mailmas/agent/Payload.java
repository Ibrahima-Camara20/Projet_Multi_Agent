package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;

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
}