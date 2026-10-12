package fr.m2ia.mailmas.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.model.Contact;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Annuaire des contacts, stocké dans un fichier JSON (par défaut config/contacts.json) :
 *
 * <pre>
 * [
 *   { "email": "anna.muller@partner-lab.eu", "name": "Anna Müller", "roles": ["research-partner"] }
 * ]
 * </pre>
 *
 * <p>Les adresses sont comparées sans tenir compte de la casse, et les rôles sont mis en
 * minuscules : ils doivent correspondre aux <code>senderRoles</code> des profils d'agents.
 * Un fichier absent donne un annuaire vide (le routing se fonde alors sur les seuls motifs
 * d'adresse).</p>
 *
 * <p>L'annuaire est chargé en mémoire ; {@link #reload()} relit le fichier après une
 * modification manuelle, et {@link #save}/{@link #delete} le mettent à jour depuis l'interface.</p>
 */
public class JsonContactDirectory implements ContactDirectory {

    private final Path file;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Remplacé d'un bloc à chaque modification : les lectures concurrentes restent cohérentes. */
    private volatile Map<String, Contact> contacts = Map.of();

    public JsonContactDirectory(Path file) {
        this.file = file;
        reload();
    }

    /** Fichier contacts.json placé à côté du dossier des agents (config/contacts.json). */
    public static JsonContactDirectory from(AppConfig config) {
        return new JsonContactDirectory(config.agentsDir().resolveSibling("contacts.json"));
    }

    @Override
    public Optional<Contact> findByEmail(String email) {
        return email == null ? Optional.empty() : Optional.ofNullable(contacts.get(key(email)));
    }

    /** Tous les contacts, triés par adresse (affichage dans l'interface). */
    public List<Contact> findAll() {
        return contacts.values().stream().sorted(Comparator.comparing(Contact::email)).toList();
    }

    /** Ajoute un contact, ou remplace celui qui a la même adresse. */
    public synchronized void save(Contact contact) {
        Contact c = normalize(contact.email(), contact.displayName(), List.copyOf(contact.roles()));
        Map<String, Contact> copy = new HashMap<>(contacts);
        copy.put(key(c.email()), c);
        write(copy);
        contacts = Map.copyOf(copy);
    }

    public synchronized void delete(String email) {
        Map<String, Contact> copy = new HashMap<>(contacts);
        if (copy.remove(key(email)) != null) {
            write(copy);
            contacts = Map.copyOf(copy);
        }
    }

    /** Relit le fichier (après une modification manuelle). */
    public synchronized void reload() {
        contacts = read();
    }

    private Map<String, Contact> read() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        List<ContactEntry> entries;
        try {
            entries = json.readValue(file.toFile(), new TypeReference<List<ContactEntry>>() {});
        } catch (IOException e) {
            throw new RepositoryException("Annuaire illisible : " + file + " (" + e.getMessage() + ")", e);
        }
        Map<String, Contact> result = new HashMap<>();
        for (ContactEntry e : entries == null ? List.<ContactEntry>of() : entries) {
            if (e == null || e.email() == null) {
                throw new RepositoryException("Contact sans adresse e-mail dans " + file);
            }
            Contact c;
            try {
                c = normalize(e.email(), e.name(), e.roles());
            } catch (IllegalArgumentException ex) {
                throw new RepositoryException(ex.getMessage() + " dans " + file, ex);
            }
            if (result.putIfAbsent(key(c.email()), c) != null) {
                throw new RepositoryException("Adresse en double dans " + file + " : " + c.email());
            }
        }
        return Map.copyOf(result);
    }

    /** Écriture dans un fichier temporaire puis remplacement : jamais de fichier à moitié écrit. */
    private void write(Map<String, Contact> data) {
        List<ContactEntry> entries = data.values().stream()
                .sorted(Comparator.comparing(Contact::email))
                .map(ContactEntry::of)
                .toList();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            json.writeValue(tmp.toFile(), entries);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RepositoryException("Impossible d'enregistrer l'annuaire " + file, e);
        }
    }

    private static Contact normalize(String email, String name, List<String> roles) {
        String address = email == null ? "" : email.strip();
        if (!address.contains("@")) {
            throw new IllegalArgumentException("Adresse e-mail invalide : \"" + email + "\"");
        }
        Set<String> cleanRoles = new LinkedHashSet<>();
        if (roles != null) {
            for (String r : roles) {
                if (r != null && !r.isBlank()) {
                    cleanRoles.add(r.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        String display = name == null || name.isBlank() ? address : name.strip();
        return new Contact(address, display, cleanRoles);
    }

    private static String key(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}