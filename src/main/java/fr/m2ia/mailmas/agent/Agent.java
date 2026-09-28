package fr.m2ia.mailmas.agent;

/**
 * Un agent du système. Il ne connaît pas les autres agents directement :
 * il reçoit des messages via le MessageBus (Mediator) et renvoie une réponse.
 */
public interface Agent {

    AgentProfile profile();

    default String id() { return profile().id(); }

    /** Traite un message et renvoie la réponse (PROPOSE, REFUSE, FAILURE...). */
    AgentMessage handle(AgentMessage message);
}
