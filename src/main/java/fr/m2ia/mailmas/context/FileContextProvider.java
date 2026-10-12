package fr.m2ia.mailmas.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Fournit le contexte d'un agent à partir de fichiers texte (.md, .txt).
 *
 * <p>
 * Sources acceptées dans le profil : <code>file:chemin/dossier/</code> ou
 * <code>file:chemin/fichier.md</code>. Chaque fichier est découpé en morceaux
 * (paragraphes
 * regroupés), et chaque morceau reçoit une pertinence selon les mots qu'il
 * partage avec
 * l'e-mail (recherche lexicale simple ; les embeddings sont une extension
 * possible).
 * </p>
 *
 * <p>
 * Isolation : seules les sources déclarées dans le profil de l'agent sont lues,
 * et
 * uniquement si elles se trouvent sous le dossier racine autorisé.
 * </p>
 */
public class FileContextProvider implements ContextProvider {

    public static final String PREFIX = "file:";

    /** Taille maximale d'un morceau (~300 tokens). */
    static final int MAX_CHUNK_CHARS = 1200;

    /**
     * Pertinence minimale : un morceau sans mot commun reste envoyé si le budget le
     * permet.
     */
    static final double BASE_RELEVANCE = 0.1;

    private static final Set<String> EXTENSIONS = Set.of(".md", ".txt");

    private static final Set<String> STOP_WORDS = Set.of(
            "les", "des", "une", "est", "pour", "dans", "que", "qui", "sur", "pas", "par", "avec",
            "vous", "nous", "votre", "vos", "notre", "nos", "aux", "ces", "ses", "son", "sont",
            "mais", "elle", "ils", "leur", "tout", "plus", "bien", "etre", "avoir", "fait",
            "bonjour", "merci", "cordialement", "bonne", "journee",
            "the", "and", "for", "you", "your", "are", "with", "this", "that", "could", "would",
            "can", "will", "from", "have", "has", "was", "not", "all", "also", "best", "regards",
            "hello", "thanks", "dear");

    private final Path root;

    /** @param root dossier racine autorisé (en pratique config/context) */
    public FileContextProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public boolean supports(String sourceId) {
        return sourceId != null && sourceId.startsWith(PREFIX);
    }

    @Override
    public List<ContextItem> retrieve(ContextQuery query) {
        Set<String> emailWords = keywords(query.email().subject() + " " + query.email().body());
        List<ContextItem> items = new ArrayList<>();
        for (String source : query.agent().contextSources()) {
            if (!supports(source)) {
                continue;
            }
            for (Path file : files(resolve(source))) {
                List<String> chunks = chunks(read(file));
                String label = root.relativize(file).toString().replace('\\', '/');
                for (int i = 0; i < chunks.size(); i++) {
                    String id = chunks.size() > 1 ? label + "#" + (i + 1) : label;
                    items.add(new ContextItem(id, chunks.get(i), relevance(emailWords, chunks.get(i))));
                }
            }
        }
        return items;
    }

    /** Résout la source et vérifie qu'elle reste sous la racine autorisée. */
    Path resolve(String source) {
        Path path = Path.of(source.substring(PREFIX.length())).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Source de contexte hors du dossier autorisé ("
                    + root + ") : " + source);
        }
        return path;
    }

    private static List<Path> files(Path path) {
        if (Files.isRegularFile(path)) {
            return isSupported(path) ? List.of(path) : List.of();
        }
        if (!Files.isDirectory(path)) {
            return List.of(); // dossier pas encore créé : l'agent n'a simplement pas de contexte
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .filter(FileContextProvider::isSupported)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de parcourir " + path, e);
        }
    }

    private static boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Fichier de contexte illisible (doit être en UTF-8) : " + file, e);
        }
    }

    /**
     * Regroupe les paragraphes en morceaux d'au plus MAX_CHUNK_CHARS caractères.
     */
    static List<String> chunks(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + 2 + p.length() > MAX_CHUNK_CHARS) {
                result.add(current.toString());
                current.setLength(0);
            }
            while (p.length() > MAX_CHUNK_CHARS) { // paragraphe trop long : on le coupe
                result.add(p.substring(0, MAX_CHUNK_CHARS));
                p = p.substring(MAX_CHUNK_CHARS).strip();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Part des mots-clés de l'e-mail présents dans le morceau, ramenée dans
     * [BASE_RELEVANCE, 1].
     */
    static double relevance(Set<String> emailWords, String chunk) {
        if (emailWords.isEmpty()) {
            return BASE_RELEVANCE;
        }
        Set<String> chunkWords = keywords(chunk);
        long found = emailWords.stream().filter(chunkWords::contains).count();
        return BASE_RELEVANCE + (1 - BASE_RELEVANCE) * found / emailWords.size();
    }

    /**
     * Mots significatifs : minuscules, sans accents, au moins 3 caractères, hors
     * mots vides.
     */
    static Set<String> keywords(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }
}