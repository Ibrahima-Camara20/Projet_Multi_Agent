package fr.m2ia.mailmas.inbox;

import fr.m2ia.mailmas.model.Email;

import java.util.List;
import java.util.Optional;

/**
 * Une source d'e-mails entrants : fichiers JSON (démo, formulaire « Nouvel e-mail ») et
 * fichiers .eml importés.
 *
 * <p>Le reste du système (interface, démonstrateur, service) ne connaît que cette interface :
 * ajouter une source ne change ni les agents ni le routing.</p>
 */
public interface MailSource {

    /** Nom affiché (ex. « fichiers .eml »). */
    String name();

    /** Tous les e-mails de la source, du plus ancien au plus récent. Relu à chaque appel. */
    List<Email> findAll();

    default Optional<Email> findById(String id) {
        return findAll().stream().filter(e -> e.id().equals(id)).findFirst();
    }
}