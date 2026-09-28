package fr.m2ia.mailmas.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * E-mail reçu ou envoyé. Objet immuable partagé par tous les modules.
 * threadId permet de retrouver le fil de discussion (mémoire, routing).
 */
public record Email(
        String id,
        String threadId,
        String from,
        List<String> to,
        String subject,
        String body,
        Instant receivedAt,
        Map<String, String> metadata) {

    public Email {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        threadId = threadId != null ? threadId : id;   // un e-mail isolé forme son propre fil
        to = to != null ? List.copyOf(to) : List.of();
        subject = subject != null ? subject : "";
        body = body != null ? body : "";
        receivedAt = receivedAt != null ? receivedAt : Instant.now();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
