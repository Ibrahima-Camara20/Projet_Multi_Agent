package fr.m2ia.mailmas.agent;

import java.util.List;
import java.util.Objects;

/**
 * Configuration d'un agent spécialisé : créer un agent = créer un profil, pas
 * une classe.
 *
 * <p>
 * Stockage (géré par AgentRepository) :
 * <code>config/agents/&lt;id&gt;.json</code> pour la
 * configuration et <code>config/agents/&lt;id&gt;.md</code> pour le system
 * prompt, plus simple
 * à modifier par l'utilisateur. Le repository lit le .md et le place dans
 * systemPrompt.
 * </p>
 *
 * @param systemPrompt   texte du system prompt
 * @param contextSources identifiants de sources de contexte (ex.
 *                       "file:config/context/supermas/")
 * @param routingHints   indices exploités par les stratégies de routing
 * @param fallback       vrai pour l'agent de repli (un seul dans le système)
 */
public record AgentProfile(
        String id,
        String name,
        String description,
        String systemPrompt,
        List<String> contextSources,
        RoutingHints routingHints,
        boolean fallback) {

    public AgentProfile {
        Objects.requireNonNull(id, "id");
        name = name != null ? name : id;
        description = description != null ? description : "";
        systemPrompt = systemPrompt != null ? systemPrompt : "";
        contextSources = contextSources != null ? List.copyOf(contextSources) : List.of();
        routingHints = routingHints != null ? routingHints : RoutingHints.none();
    }
}