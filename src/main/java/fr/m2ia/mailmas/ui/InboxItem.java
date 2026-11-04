package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.app.ProcessingResult;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;

/**
 * État d'un e-mail dans l'interface. Modifié uniquement dans le thread JavaFX
 * (par MainController), lu par les vues.
 */
final class InboxItem {

    enum Status {
        TO_PROCESS("à traiter"),
        WORKING("en rédaction…"),
        PROPOSED("proposition prête"),
        FAILED("échec"),
        SENT("validé");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    final Email email;
    Status status = Status.TO_PROCESS;
    /** Dernier résultat du système (routing, agent, proposition). */
    ProcessingResult result;
    /** Durée du dernier traitement, en secondes. */
    long seconds;
    /** Début du traitement en cours (pour afficher le temps écoulé). */
    long startedAt;
    /** Message d'erreur si le traitement a échoué. */
    String error;
    /** Réponse validée par l'utilisateur. */
    DraftReply sent;

    InboxItem(Email email) {
        this.email = email;
    }

    String agentId() {
        return result != null ? result.selectedAgentId() : null;
    }

    boolean ambiguous() {
        return result != null && result.ambiguous() && status == Status.PROPOSED;
    }
}