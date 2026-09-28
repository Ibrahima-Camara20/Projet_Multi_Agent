package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;

/**
 * Point d'entrée unique de l'interface graphique vers le système multi-agent.
 * Implémenté par la personne A (au-dessus du RoutingAgent), appelé par l'UI de
 * la personne B.
 *
 * <p>
 * <b>Méthodes bloquantes</b> : submit, revise et reroute appellent le LLM
 * (plusieurs dizaines
 * de secondes). L'interface doit les appeler dans un thread d'arrière-plan.
 * </p>
 *
 * <p>
 * <b>Mémoire</b> : c'est l'implémentation de ce service, et elle seule, qui
 * écrit dans
 * ConversationMemory (e-mail reçu, brouillons, réponse envoyée).
 * </p>
 *
 * <p>
 * <b>Refus d'un agent</b> : le router essaie le candidat suivant du classement
 * (au-dessus du seuil), puis l'agent de repli. Le nombre d'essais est borné par
 * la configuration.
 * </p>
 */
public interface AssistantService {

    /** Nouvel e-mail : routing + première proposition. */
    ProcessingResult submit(Email email);

    /** Rejet avec commentaire : le même agent génère une nouvelle proposition. */
    ProcessingResult revise(String emailId, UserFeedback feedback);

    /**
     * L'utilisateur choisit lui-même un autre agent (cas ambigu ou mauvais
     * routing).
     */
    ProcessingResult reroute(String emailId, String agentId);

    /**
     * Validation, avec ou sans modification : la réponse est enregistrée comme
     * envoyée.
     */
    void accept(String emailId, UserFeedback feedback);

    /**
     * Recharge les profils (après création ou modification d'un agent dans
     * l'interface).
     */
    void reloadAgents();
}