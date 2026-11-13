package fr.m2ia.mailmas.review;

import java.util.Objects;

/**
 * Un problème relevé par l'agent critique dans une proposition.
 *
 * @param severity ce que le système en fait
 * @param code     catégorie stable (FORMULE, LANGUE, ENGAGEMENT, DATE, INJECTION, DIVULGATION)
 * @param message  explication lisible, affichée à l'utilisateur
 */
public record Finding(Severity severity, String code, String message) {

    public enum Severity {
        /** Défaut corrigible : l'agent rédacteur réécrit automatiquement, une fois. */
        REWRITE,
        /** À vérifier par l'utilisateur : signalé, rien n'est modifié. */
        WARNING,
        /** Risque de manipulation ou de divulgation : la rédaction est confiée à l'agent de repli. */
        BLOCK
    }

    public Finding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}