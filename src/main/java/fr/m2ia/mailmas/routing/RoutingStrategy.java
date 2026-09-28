package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

import java.util.Collection;

/**
 * Strategy : manière de classer les agents pour un e-mail donné
 * (règles, mots-clés, LLM, embeddings, combinaison pondérée...).
 *
 * <p>
 * Toutes les stratégies produisent des scores dans [0, 1] avec le même sens
 * (1 = certain), afin de pouvoir les combiner. Une stratégie qui a besoin
 * d'autres
 * informations (ContactDirectory, ConversationMemory) les reçoit par son
 * constructeur :
 * la signature de route() ne change pas.
 * </p>
 */
public interface RoutingStrategy {

    String name();

    RoutingDecision route(Email email, Collection<AgentProfile> candidates);
}