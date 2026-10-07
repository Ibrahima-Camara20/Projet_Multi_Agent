package fr.m2ia.mailmas.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Repository des profils d'agents, stockés dans un dossier :
 * <ul>
 *   <li><code>&lt;id&gt;.json</code> : configuration (nom, sources de contexte, indices de routing, repli)</li>
 *   <li><code>&lt;id&gt;.md</code> : system prompt, modifiable par l'utilisateur sans toucher au code</li>
 * </ul>
 * Un .md absent donne un system prompt vide.
 */
public class JsonAgentRepository implements AgentRepository {

    /** Minuscules, chiffres et tirets : l'identifiant sert de nom de fichier. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public JsonAgentRepository(Path dir) {
        this.dir = dir;
    }

    public static JsonAgentRepository from(AppConfig config) {
        return new JsonAgentRepository(config.agentsDir());
    }

    @Override
    public List<AgentProfile> findAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new RepositoryException("Impossible de lister le dossier " + dir, e);
        }
    }

    @Override
    public Optional<AgentProfile> findById(String id) {
        checkId(id);
        Path file = jsonFile(id);
        return Files.exists(file) ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public void save(AgentProfile profile) {
        checkId(profile.id());
        try {
            Files.createDirectories(dir);
            json.writeValue(jsonFile(profile.id()).toFile(), AgentFile.of(profile));
            Files.writeString(promptFile(profile.id()), profile.systemPrompt(), UTF_8);
        } catch (IOException e) {
            throw new RepositoryException("Impossible d'enregistrer l'agent " + profile.id(), e);
        }
    }

    @Override
    public void delete(String id) {
        checkId(id);
        try {
            Files.deleteIfExists(jsonFile(id));
            Files.deleteIfExists(promptFile(id));
        } catch (IOException e) {
            throw new RepositoryException("Impossible de supprimer l'agent " + id, e);
        }
    }

    private AgentProfile read(Path file) {
        String name = file.getFileName().toString();
        String fileId = name.substring(0, name.length() - ".json".length());
        try {
            AgentFile data = json.readValue(file.toFile(), AgentFile.class);
            Path prompt = promptFile(fileId);
            String systemPrompt = Files.exists(prompt) ? Files.readString(prompt, UTF_8).strip() : "";
            return data.toProfile(fileId, systemPrompt);
        } catch (IOException e) {
            throw new RepositoryException("Profil d'agent illisible : " + file + " (" + e.getMessage() + ")", e);
        }
    }

    private static void checkId(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Identifiant d'agent invalide : \"" + id
                    + "\" (minuscules, chiffres et tirets uniquement)");
        }
    }

    private Path jsonFile(String id) {
        return dir.resolve(id + ".json");
    }

    private Path promptFile(String id) {
        return dir.resolve(id + ".md");
    }
}