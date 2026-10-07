package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.RoutingHints;

import java.util.List;

/**
 * Représentation d'un profil telle qu'elle est écrite dans config/agents/<id>.json.
 * Le system prompt n'y figure pas : il est dans le fichier .md associé.
 * Sert d'intermédiaire pour que AgentProfile ne dépende pas du format de stockage.
 */
record AgentFile(
        String id,
        String name,
        String description,
        List<String> contextSources,
        RoutingHints routingHints,
        boolean fallback) {

    static AgentFile of(AgentProfile p) {
        return new AgentFile(p.id(), p.name(), p.description(),
                p.contextSources(), p.routingHints(), p.fallback());
    }

    /**
     * @param fileId identifiant déduit du nom de fichier (fait foi si "id" est absent du JSON)
     */
    AgentProfile toProfile(String fileId, String systemPrompt) {
        if (id != null && !id.equals(fileId)) {
            throw new RepositoryException("Le fichier " + fileId + ".json déclare l'identifiant \""
                    + id + "\" : les deux doivent être identiques.");
        }
        return new AgentProfile(fileId, name, description, systemPrompt,
                contextSources, routingHints, fallback);
    }
}