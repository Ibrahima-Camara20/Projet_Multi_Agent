package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.model.Contact;

import java.util.List;

/**
 * Représentation d'un contact telle qu'elle est écrite dans config/contacts.json.
 * Sert d'intermédiaire pour que le modèle (Contact) ne dépende pas du format de stockage.
 */
record ContactEntry(String email, String name, List<String> roles) {

    static ContactEntry of(Contact c) {
        return new ContactEntry(c.email(), c.displayName(), c.roles().stream().sorted().toList());
    }
}