package fr.m2ia.mailmas.generation;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;

import java.util.List;

/**
 * Facade fournie par la personne B et utilisée par les agents de la personne A.
 * Enchaîne : récupération du contexte -> historique du fil -> construction du
 * prompt -> appel LLM.
 * L'agent décide QUOI faire, le générateur sait COMMENT produire le texte.
 *
 * <p>
 * Le générateur <b>lit</b> ConversationMemory mais n'y écrit jamais :
 * l'enregistrement est la responsabilité d'AssistantService.
 * </p>
 */
public interface DraftGenerator {

    DraftReply generate(Email email, AgentProfile agent, List<UserFeedback> previousFeedback, int attempt)
            throws LLMException;
}