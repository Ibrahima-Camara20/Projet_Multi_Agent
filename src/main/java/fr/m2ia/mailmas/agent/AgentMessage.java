package fr.m2ia.mailmas.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Message échangé entre agents. conversationId relie une requête et ses réponses
 * (traçabilité, journal des décisions, explicabilité).
 */
public record AgentMessage(
        String id,
        Performative performative,
        String sender,
        String receiver,
        String conversationId,
        Payload payload,
        Instant sentAt) {

    public AgentMessage {
        Objects.requireNonNull(performative, "performative");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(payload, "payload");
        id = id != null ? id : UUID.randomUUID().toString();
        conversationId = conversationId != null ? conversationId : id;
        sentAt = sentAt != null ? sentAt : Instant.now();
    }

    public static AgentMessage of(Performative p, String sender, String receiver,
                                  String conversationId, Payload payload) {
        return new AgentMessage(null, p, sender, receiver, conversationId, payload, null);
    }

    /** Construit une réponse à ce message (émetteur et destinataire inversés, même conversation). */
    public AgentMessage reply(Performative p, Payload payload) {
        return of(p, receiver, sender, conversationId, payload);
    }
}
