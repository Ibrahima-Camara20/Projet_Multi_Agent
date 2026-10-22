package fr.m2ia.mailmas.memory;

import fr.m2ia.mailmas.model.ConversationEntry;

/**
 * Une ligne d'un fichier de fil (format JSON Lines). La date reste une chaîne
 * ISO-8601 :
 * pas besoin d'un module Jackson pour les dates.
 */
record EntryLine(String kind, String author, String text, String at) {

    static EntryLine of(ConversationEntry e) {
        return new EntryLine(e.kind().name(), e.author(), e.text(), e.at().toString());
    }
}