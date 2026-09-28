package fr.m2ia.mailmas.context;

/** Un morceau de contexte (extrait de fichier, note, ancien e-mail) avec sa pertinence. */
public record ContextItem(String source, String content, double relevance) {

    /** Estimation grossière (~4 caractères par token), suffisante pour gérer un budget. */
    public int estimatedTokens() { return Math.max(1, content.length() / 4); }
}
