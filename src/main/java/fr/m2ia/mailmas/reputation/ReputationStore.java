package fr.m2ia.mailmas.reputation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.m2ia.mailmas.app.FeedbackListener;
import fr.m2ia.mailmas.config.AppConfig;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Réputation de chaque agent, mise à jour à chaque décision de l'utilisateur et conservée dans
 * data/reputation.json (exclu de Git, comme l'historique des conversations).
 *
 * <p>Les données nécessaires existaient déjà : chaque validation, modification, rejet ou
 * changement d'agent est un témoignage sur la qualité d'un agent. La réputation les rend
 * visibles (écran Agents) et pourrait servir, plus tard, à décider quelles réponses envoyer
 * sans relecture.</p>
 *
 * <p>Si le fichier ne peut pas être écrit, la réputation reste en mémoire : elle ne doit jamais
 * empêcher l'utilisateur de valider une réponse.</p>
 */
public class ReputationStore implements FeedbackListener {

    private static final Logger LOG = Logger.getLogger(ReputationStore.class.getName());

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, Reputation> reputations = new TreeMap<>();

    /** @param file fichier JSON, ou null pour une réputation en mémoire seulement (tests) */
    public ReputationStore(Path file) {
        this.file = file;
        load();
    }

    /** Fichier data/reputation.json à côté du dossier config/. */
    public static ReputationStore from(AppConfig config) {
        Path configDir = config.agentsDir().toAbsolutePath().getParent();
        return new ReputationStore(configDir.resolveSibling("data").resolve("reputation.json"));
    }

    public synchronized Reputation get(String agentId) {
        return reputations.getOrDefault(agentId, Reputation.NONE);
    }

    public synchronized Map<String, Reputation> all() {
        return Map.copyOf(reputations);
    }

    @Override
    public synchronized void onFeedback(String agentId, Outcome outcome) {
        if (agentId == null) {
            return;
        }
        Reputation r = get(agentId);
        reputations.put(agentId, switch (outcome) {
            case ACCEPTED -> r.plus(1, 0);
            case EDITED -> r.plus(0.5, 0.5);
            case REJECTED, REROUTED -> r.plus(0, 1);
        });
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            reputations.putAll(json.readValue(file.toFile(), new TypeReference<Map<String, Reputation>>() { }));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Réputation illisible, repart de zéro : " + file, e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), reputations);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Réputation non enregistrée : " + file, e);
        }
    }
}
