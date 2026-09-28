package fr.m2ia.mailmas.agent;

@FunctionalInterface
public interface MessageListener {
    void onMessage(AgentMessage message);
}
