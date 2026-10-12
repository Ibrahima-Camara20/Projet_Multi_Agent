package fr.m2ia.mailmas.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Boîte de réception simulée : un fichier JSON par e-mail dans config/inbox/.
 *
 * <pre>
 * { "id": "001", "threadId": "supermas-wp3", "from": "anna.muller@partner-lab.eu",
 *   "to": ["moi@univ.fr"], "subject": "...", "body": "...",
 *   "receivedAt": "2026-09-28T09:15:00Z", "metadata": {} }
 * </pre>
 *
 * <p>Les e-mails sont relus à chaque appel : un fichier ajouté pendant que l'application
 * tourne apparaît au prochain rafraîchissement de l'interface.</p>
 */
public class JsonInbox {

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JsonInbox(Path dir) {
        this.dir = dir;
    }

    public static JsonInbox from(AppConfig config) {
        return new JsonInbox(config.inboxDir());
    }

    /** Tous les e-mails, du plus ancien au plus récent. */
    public List<Email> findAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Email> emails = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Email email = read(file);
                if (!ids.add(email.id())) {
                    throw new RepositoryException("Identifiant d'e-mail en double (" + email.id() + ") : " + file);
                }
                emails.add(email);
            }
        } catch (IOException e) {
            throw new RepositoryException("Impossible de lister la boîte de réception " + dir, e);
        }
        emails.sort(Comparator.comparing(Email::receivedAt).thenComparing(Email::id));
        return emails;
    }

    public Optional<Email> findById(String id) {
        return findAll().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private Email read(Path file) {
        EmailFile f;
        try {
            f = json.readValue(file.toFile(), EmailFile.class);
        } catch (IOException e) {
            throw new RepositoryException("E-mail illisible : " + file + " (" + e.getMessage() + ")", e);
        }
        if (f == null || f.id() == null || f.id().isBlank()) {
            throw new RepositoryException("E-mail sans identifiant : " + file);
        }
        if (f.from() == null || f.from().isBlank()) {
            throw new RepositoryException("E-mail sans expéditeur : " + file);
        }
        return new Email(f.id(), f.threadId(), f.from(), f.to(), f.subject(), f.body(),
                parseDate(f.receivedAt(), file), f.metadata());
    }

    private static Instant parseDate(String value, Path file) {
        if (value == null || value.isBlank()) {
            return null;   // l'e-mail prend alors l'heure de lecture
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new RepositoryException("Date invalide dans " + file + " : \"" + value
                    + "\" (format attendu : 2026-09-28T09:15:00Z)", e);
        }
    }
}