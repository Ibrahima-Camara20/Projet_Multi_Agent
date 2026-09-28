package fr.m2ia.mailmas.model;

/** Décision de l'utilisateur sur une proposition (le système n'envoie jamais seul). */
public record UserFeedback(Decision decision, String comment, String editedBody) {

    public enum Decision { ACCEPT, EDIT, REJECT }

    public static UserFeedback accept() { return new UserFeedback(Decision.ACCEPT, null, null); }
    public static UserFeedback reject(String comment) { return new UserFeedback(Decision.REJECT, comment, null); }
    public static UserFeedback edit(String editedBody) { return new UserFeedback(Decision.EDIT, null, editedBody); }
}
