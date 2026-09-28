package fr.m2ia.mailmas.model;

import java.time.Instant;

/**
 * Proposition de réponse produite par un agent spécialisé.
 * attempt = numéro de la proposition pour cet e-mail (1, 2, ... après rejets).
 */
public record DraftReply(
        String id,
        String emailId,
        String agentId,
        String subject,
        String body,
        int attempt,
        Instant createdAt) {

    public DraftReply {
        createdAt = createdAt != null ? createdAt : Instant.now();
        if (attempt < 1)
            throw new IllegalArgumentException("attempt >= 1");
    }

    /**
     * Copie avec un autre texte : sert à enregistrer la version corrigée par
     * l'utilisateur.
     */
    public DraftReply withBody(String newBody) {
        return new DraftReply(id, emailId, agentId, subject, newBody, attempt, Instant.now());
    }
}