package fr.m2ia.mailmas.agent;

import java.util.List;
import java.util.Objects;

/**
 * Configuration d'un agent spécialisé, chargée depuis un fichier (config/agents/*.json)
 * et modifiable depuis l'interface : créer un agent = créer un profil, pas une classe.
 *
 * @param systemPrompt   texte du system prompt (éditable par l'utilisateur)
 * @param contextSources identifiants de sources de contexte (ex. "file:config/context/supermas/")
 * @param routingHints   indices exploités par les stratégies de routing
 * @param fallback       vrai pour l'agent de repli (GenericAgent)
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
