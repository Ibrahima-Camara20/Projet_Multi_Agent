package fr.m2ia.mailmas.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.inbox.MailSource;
import fr.m2ia.mailmas.model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Boîte de réception en fichiers JSON : un fichier par e-mail.
 * Sert pour la démo (config/inbox/) et pour les e-mails ajoutés par l'utilisateur
 * (data/inbox/ : formulaire « Nouvel e-mail » et import de fichiers).
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
public class JsonInbox implements MailSource {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public JsonInbox(Path dir) {
        this.dir = dir;
    }

    /** Boîte de démonstration (config/inbox/). */
    public static JsonInbox from(AppConfig config) {
        return new JsonInbox(config.inboxDir());
    }

    @Override
    public String name() {
        return "fichiers JSON (" + dir.getFileName() + ")";
    }

    /** Tous les e-mails, du plus ancien au plus récent. */
    @Override
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

    @Override
    public Optional<Email> findById(String id) {
        return findAll().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    // ------------------------------------------------------------------ ajout d'e-mails

    /** Nouvel identifiant libre, ex. « form-20261111-093512 ». */
    public String newId() {
        String base = "form-" + ID_TIME.format(LocalDateTime.now());
        String id = base;
        int n = 2;
        while (Files.exists(dir.resolve(id + ".json"))) {
            id = base + "-" + n++;
        }
        return id;
    }

    /** Enregistre un e-mail (formulaire « Nouvel e-mail ») dans &lt;id&gt;.json. */
    public Email save(Email email) {
        checkId(email.id());
        EmailFile f = new EmailFile(email.id(), email.threadId(), email.from(), email.to(), email.subject(),
                email.body(), email.receivedAt().truncatedTo(ChronoUnit.SECONDS).toString(), email.metadata());
        try {
            Files.createDirectories(dir);
            json.writeValue(dir.resolve(email.id() + ".json").toFile(), f);
        } catch (IOException e) {
            throw new RepositoryException("Impossible d'enregistrer l'e-mail " + email.id(), e);
        }
        return email;
    }

    /** Lit et vérifie un fichier d'e-mail JSON, sans le copier (message d'erreur clair sinon). */
    public Email readFile(Path file) {
        return read(file);
    }

    /** Copie un fichier JSON vérifié dans la boîte, sous le nom &lt;id&gt;.json. */
    public Email importFile(Path source) {
        Email email = read(source);
        checkId(email.id());
        try {
            Files.createDirectories(dir);
            Files.copy(source, dir.resolve(email.id() + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RepositoryException("Import impossible : " + source, e);
        }
        return email;
    }

    /** L'identifiant sert de nom de fichier : lettres, chiffres, « - » et « _ » seulement. */
    private static void checkId(String id) {
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new RepositoryException("Identifiant d'e-mail invalide : \"" + id
                    + "\" (lettres, chiffres, - et _ seulement)");
        }
    }

    // ------------------------------------------------------------------ lecture

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