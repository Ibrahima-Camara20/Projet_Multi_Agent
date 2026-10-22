package fr.m2ia.mailmas.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.ConversationEntry.Kind;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Mémoire des conversations conservée sur disque : un fichier par fil de
 * discussion,
 * au format JSON Lines (une entrée par ligne), dans data/conversations/.
 *
 * <ul>
 * <li><b>Ajout seulement</b> : chaque entrée est ajoutée en fin de fichier,
 * rien n'est réécrit.
 * Un arrêt brutal ne peut abîmer que la dernière ligne, qui est alors
 * ignorée.</li>
 * <li><b>Cache</b> : un fil est lu une seule fois, puis servi depuis la
 * mémoire.</li>
 * <li><b>Confidentialité</b> : ces fichiers contiennent des e-mails réels ; le
 * dossier data/
 * est exclu de Git et reste sur la machine de l'utilisateur.</li>
 * </ul>
 */
public class FileConversationMemory implements ConversationMemory {

    private static final Logger LOG = Logger.getLogger(FileConversationMemory.class.getName());

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, List<ConversationEntry>> cache = new HashMap<>();

    public FileConversationMemory(Path dir) {
        this.dir = dir;
    }

    /** Dossier data/conversations/ à côté du dossier config/. */
    public static FileConversationMemory from(AppConfig config) {
        Path configDir = config.agentsDir().toAbsolutePath().getParent();
        return new FileConversationMemory(configDir.resolveSibling("data").resolve("conversations"));
    }

    @Override
    public void recordReceived(Email email) {
        append(email.threadId(), new ConversationEntry(Kind.RECEIVED, email.from(), email.body(), email.receivedAt()));
    }

    @Override
    public void recordDraft(String threadId, DraftReply draft) {
        append(threadId, new ConversationEntry(Kind.DRAFT, draft.agentId(), draft.body(), draft.createdAt()));
    }

    @Override
    public void recordSent(String threadId, DraftReply sent) {
        append(threadId, new ConversationEntry(Kind.SENT, "user", sent.body(), sent.createdAt()));
    }

    @Override
    public synchronized List<ConversationEntry> recent(String threadId, int maxEntries) {
        List<ConversationEntry> all = load(threadId);
        return List.copyOf(all.subList(Math.max(0, all.size() - maxEntries), all.size()));
    }

    /**
     * Efface tout l'historique (bouton « réinitialiser » de l'interface,
     * démonstrations).
     */
    public synchronized void clear() {
        cache.clear();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                Files.delete(f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'effacer la mémoire " + dir, e);
        }
    }

    private synchronized void append(String threadId, ConversationEntry entry) {
        List<ConversationEntry> entries = load(threadId);
        try {
            Files.createDirectories(dir);
            Files.writeString(file(threadId), json.writeValueAsString(EntryLine.of(entry)) + "\n", UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible d'enregistrer dans la mémoire du fil " + threadId, e);
        }
        entries.add(entry);
    }

    private List<ConversationEntry> load(String threadId) {
        return cache.computeIfAbsent(threadId, this::read);
    }

    private List<ConversationEntry> read(String threadId) {
        Path file = file(threadId);
        List<ConversationEntry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Mémoire illisible : " + file, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                EntryLine l = json.readValue(line, EntryLine.class);
                entries.add(new ConversationEntry(Kind.valueOf(l.kind()), l.author(), l.text(), Instant.parse(l.at())));
            } catch (JsonProcessingException | RuntimeException e) {
                LOG.log(Level.WARNING, "Ligne {0} ignorée dans {1} : {2}",
                        new Object[] { i + 1, file, e.getMessage() });
            }
        }
        return entries;
    }

    /**
     * Nom de fichier sûr pour un identifiant de fil. Les caractères spéciaux sont
     * remplacés, et
     * un suffixe distingue deux fils qui donneraient le même nom (ex. « a/b » et «
     * a_b »).
     */
    static String fileName(String threadId) {
        String safe = threadId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        if (!safe.equals(threadId)) {
            safe += "-" + Integer.toHexString(threadId.hashCode());
        }
        return safe + ".jsonl";
    }

    private Path file(String threadId) {
        return dir.resolve(fileName(threadId));
    }
}