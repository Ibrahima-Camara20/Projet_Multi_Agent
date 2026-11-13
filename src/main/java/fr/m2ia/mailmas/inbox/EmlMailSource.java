package fr.m2ia.mailmas.inbox;

import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.persistence.RepositoryException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * E-mails importés sous forme de fichiers .eml (« Télécharger le message » dans Gmail,
 * « Enregistrer sous » dans Thunderbird ou Outlook), rangés dans data/inbox/.
 *
 * <ul>
 *   <li><b>Confidentialité</b> : data/ est exclu de Git, comme l'historique des conversations.</li>
 *   <li><b>Robustesse</b> : un fichier illisible est ignoré (et signalé par {@link #problems()})
 *       au lieu de bloquer toute la boîte de réception.</li>
 *   <li><b>Fils</b> : reconstitués par les en-têtes References / In-Reply-To (voir {@link EmlParser}).</li>
 * </ul>
 */
public class EmlMailSource implements MailSource {

    private final Path dir;
    private volatile List<String> problems = List.of();

    public EmlMailSource(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    @Override
    public String name() {
        return "fichiers .eml";
    }

    @Override
    public List<Email> findAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Email> emails = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(EmlMailSource::isEml).sorted().toList()) {
                try {
                    Instant fileDate = Files.getLastModifiedTime(file).toInstant();
                    emails.add(EmlParser.parse(idOf(file), Files.readAllBytes(file), fileDate));
                } catch (IOException | RuntimeException e) {
                    errors.add(file.getFileName() + " : " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RepositoryException("Impossible de lister " + dir, e);
        }
        problems = List.copyOf(errors);
        emails.sort(Comparator.comparing(Email::receivedAt).thenComparing(Email::id));
        return emails;
    }

    /** Fichiers ignorés lors du dernier {@link #findAll()}, avec la raison. */
    public List<String> problems() {
        return problems;
    }

    /**
     * Copie un fichier .eml dans data/inbox/ (bouton « Importer » de l'interface).
     * Un fichier du même nom est remplacé : réimporter un message ne crée pas de doublon.
     */
    public Path importFile(Path source) {
        if (!isEml(source)) {
            throw new RepositoryException("Ce n'est pas un fichier .eml : " + source.getFileName());
        }
        try {
            Files.createDirectories(dir);
            return Files.copy(source, dir.resolve(source.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RepositoryException("Import impossible : " + source, e);
        }
    }

    /** Identifiant stable : « eml- » + nom du fichier, réduit à des caractères sûrs. */
    static String idOf(Path file) {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".eml".length());
        return "eml-" + name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static boolean isEml(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".eml");
    }
}