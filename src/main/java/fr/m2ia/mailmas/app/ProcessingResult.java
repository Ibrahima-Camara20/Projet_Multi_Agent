package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;

/** Ce que l'interface affiche : l'e-mail, le choix du router (avec justification) et la proposition. */
public record ProcessingResult(Email email, RoutingDecision routing, String selectedAgentId, DraftReply draft) {}
