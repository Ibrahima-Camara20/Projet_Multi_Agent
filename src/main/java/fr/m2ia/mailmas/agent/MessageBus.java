package fr.m2ia.mailmas.agent;

import java.util.Collection;

/**
 * Mediator : point unique de communication entre agents.
 * Les agents ne se référencent jamais entre eux, ce qui permet d'en ajouter/retirer à chaud.
 */
public interface MessageBus {

    void register(Agent agent);

    void unregister(String agentId);

    /** Envoie un message et renvoie la réponse du destinataire (communication synchrone). */
    AgentMessage send(AgentMessage message);

    Collection<AgentProfile> registeredAgents();

    /** Observer : l'interface graphique et le journal s'abonnent aux échanges. */
    void addListener(MessageListener listener);
}
