package fr.m2ia.mailmas.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implémentation synchrone du {@link MessageBus} (Mediator).
 *
 * <p>
 * Les agents ne se connaissent pas : ils ne s'adressent des messages que par leur identifiant,
 * et le bus se charge de la livraison. Chaque message (requête et réponse) est diffusé aux
 * {@link MessageListener} abonnés (Observer) : journal des décisions, interface graphique...
 * </p>
 *
 * <p>
 * Robustesse : un destinataire inconnu, une exception levée par un agent ou une réponse
 * {@code null} sont convertis en message {@link Performative#FAILURE} ; une exception dans un
 * listener est journalisée mais n'interrompt jamais l'échange.
 * </p>
 *
 * <p>
 * Thread-safety : l'enregistrement des agents et l'envoi de messages peuvent être faits depuis
 * plusieurs threads (l'interface appelle le service dans un thread d'arrière-plan). Les
 * listeners sont appelés dans le thread de l'émetteur : l'interface doit repasser dans son
 * propre thread (ex. {@code Platform.runLater}) avant de modifier ses composants.
 * </p>
 */
public class SimpleMessageBus implements MessageBus {

    private static final Logger LOG = Logger.getLogger(SimpleMessageBus.class.getName());

    /** Ordre d'enregistrement conservé (affichage stable dans l'interface). */
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    /** Enregistre un agent ; un agent de même identifiant est remplacé (rechargement d'un profil). */
    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        synchronized (agents) {
            agents.put(agent.id(), agent);
        }
    }

    @Override
    public void unregister(String agentId) {
        synchronized (agents) {
            agents.remove(agentId);
        }
    }

    @Override
    public AgentMessage send(AgentMessage message) {
        Objects.requireNonNull(message, "message");
        notifyListeners(message);

        Agent receiver;
        synchronized (agents) {
            receiver = agents.get(message.receiver());
        }

        AgentMessage reply;
        if (receiver == null) {
            reply = message.reply(Performative.FAILURE,
                    new Payload.Failure("agent inconnu : " + message.receiver()));
        } else {
            reply = deliver(receiver, message);
        }

        notifyListeners(reply);
        return reply;
    }

    private AgentMessage deliver(Agent receiver, AgentMessage message) {
        try {
            AgentMessage reply = receiver.handle(message);
            if (reply == null) {
                return message.reply(Performative.FAILURE,
                        new Payload.Failure("l'agent " + receiver.id() + " n'a pas répondu"));
            }
            return reply;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Erreur dans l'agent " + receiver.id(), e);
            return message.reply(Performative.FAILURE,
                    new Payload.Failure("erreur dans l'agent " + receiver.id() + " : " + e.getMessage()));
        }
    }

    @Override
    public Collection<AgentProfile> registeredAgents() {
        synchronized (agents) {
            return agents.values().stream().map(Agent::profile).toList();
        }
    }

    /** Vrai si un agent porte cet identifiant. */
    public boolean isRegistered(String agentId) {
        synchronized (agents) {
            return agents.containsKey(agentId);
        }
    }

    @Override
    public void addListener(MessageListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(AgentMessage message) {
        for (MessageListener l : listeners) {
            try {
                l.onMessage(message);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Erreur dans un MessageListener", e);
            }
        }
    }
}
