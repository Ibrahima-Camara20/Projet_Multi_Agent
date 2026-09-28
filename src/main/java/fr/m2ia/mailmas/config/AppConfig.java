package fr.m2ia.mailmas.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Paramètres de l'application, lus depuis config/app.properties.
 * Toute valeur absente prend sa valeur par défaut.
 */
public record AppConfig(
        String llmUrl,
        String llmModel,
        int llmTimeoutSeconds,
        double routingThreshold,
        double ambiguityMargin,
        int maxRoutingAttempts,
        int contextTokenBudget,
        int maxHistoryEntries,
        Path agentsDir,
        Path inboxDir) {

    public static AppConfig defaults() {
        return fromProperties(new Properties());
    }

    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return fromProperties(p);
    }

    static AppConfig fromProperties(Properties p) {
        return new AppConfig(
                p.getProperty("llm.url", "http://localhost:11434"),
                p.getProperty("llm.model", "qwen3.5:4b"),
                Integer.parseInt(p.getProperty("llm.timeoutSeconds", "90")),
                Double.parseDouble(p.getProperty("routing.threshold", "0.4")),
                Double.parseDouble(p.getProperty("routing.ambiguityMargin", "0.1")),
                Integer.parseInt(p.getProperty("routing.maxAttempts", "3")),
                Integer.parseInt(p.getProperty("context.tokenBudget", "1500")),
                Integer.parseInt(p.getProperty("memory.maxHistoryEntries", "6")),
                Path.of(p.getProperty("paths.agents", "config/agents")),
                Path.of(p.getProperty("paths.inbox", "config/inbox")));
    }
}