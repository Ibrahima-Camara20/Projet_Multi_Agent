package fr.m2ia.mailmas.context;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

/** Ce que l'on cherche : pour quel e-mail, pour quel agent, avec quel budget de tokens. */
public record ContextQuery(Email email, AgentProfile agent, int tokenBudget) {}
