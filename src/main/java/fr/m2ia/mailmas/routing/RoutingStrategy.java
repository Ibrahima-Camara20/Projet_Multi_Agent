package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

import java.util.Collection;

/**
 * Strategy : manière de classer les agents pour un e-mail donné
 * (règles, mots-clés, LLM, embeddings, combinaison pondérée...).
 */
public interface RoutingStrategy {

    String name();

    RoutingDecision route(Email email, Collection<AgentProfile> candidates);
}
