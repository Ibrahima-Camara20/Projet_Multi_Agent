package fr.m2ia.mailmas.persistence;

import java.util.List;
import java.util.Map;

/**
 * Représentation d'un e-mail telle qu'elle est écrite dans config/inbox/*.json.
 * La date reste une chaîne ISO-8601 ici : elle est convertie par JsonInbox, ce qui évite
 * d'ajouter un module Jackson pour les dates.
 */
record EmailFile(
        String id,
        String threadId,
        String from,
        List<String> to,
        String subject,
        String body,
        String receivedAt,
        Map<String, String> metadata) {
}