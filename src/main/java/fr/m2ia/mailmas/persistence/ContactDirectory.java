package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.model.Contact;

import java.util.Optional;

/** Annuaire des expéditeurs connus (identité, rôles) utilisé par le routing. */
public interface ContactDirectory {
    Optional<Contact> findByEmail(String email);
}
