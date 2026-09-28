package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.agent.AgentProfile;

import java.util.List;
import java.util.Optional;

/** Repository : profils d'agents persistés (fichiers JSON dans config/agents/). */
public interface AgentRepository {
    List<AgentProfile> findAll();
    Optional<AgentProfile> findById(String id);
    void save(AgentProfile profile);
    void delete(String id);
}
