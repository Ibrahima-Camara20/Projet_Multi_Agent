package fr.m2ia.mailmas.model;

import java.time.Instant;

/** Un élément de l'historique d'un fil : message reçu, brouillon proposé, ou réponse envoyée. */
public record ConversationEntry(Kind kind, String author, String text, Instant at) {
    public enum Kind { RECEIVED, DRAFT, SENT }
}
