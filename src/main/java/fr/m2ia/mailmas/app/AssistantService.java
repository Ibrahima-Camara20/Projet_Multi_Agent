package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;

/**
 * Point d'entrée unique de l'interface graphique vers le système multi-agent.
 * Implémenté par la personne A (au-dessus du RoutingAgent), appelé par l'UI de la personne B.
 */
public interface AssistantService {

    /** Nouvel e-mail : routing + première proposition. */
    ProcessingResult submit(Email email);

    /** Rejet avec commentaire : l'agent choisi génère une nouvelle proposition. */
    ProcessingResult revise(String emailId, UserFeedback feedback);

    /** Validation (ou validation après édition) : la réponse est enregistrée comme envoyée. */
    void accept(String emailId, UserFeedback feedback);
}
