package fr.m2ia.mailmas.agent;

/**
 * Identifiants réservés des participants qui ne sont pas des agents spécialisés.
 *
 * <ul>
 * <li>{@link #ROUTER} : le Routing Agent (coordinateur). Une demande qu'il envoie est une
 * délégation automatique : l'agent destinataire peut la refuser s'il se juge incompétent.</li>
 * <li>{@link #USER} : l'utilisateur. Une demande « au nom de l'utilisateur » (révision après
 * rejet, choix manuel d'un agent) n'est jamais refusée pour raison de compétence.</li>
 * </ul>
 */
public final class Participants {

    public static final String ROUTER = "router";
    public static final String USER = "user";

    private Participants() {
    }
}
