package fr.m2ia.mailmas.persistence;

/**
 * Erreur de lecture ou d'écriture des données persistées (fichier absent, JSON
 * invalide...).
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}