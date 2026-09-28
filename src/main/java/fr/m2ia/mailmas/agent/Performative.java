package fr.m2ia.mailmas.agent;

/** Actes de communication (inspirés de FIPA-ACL). */
public enum Performative {
    REQUEST,   // demander à un agent de produire une réponse
    PROPOSE,   // renvoyer une proposition de réponse
    REFUSE,    // l'agent ne se juge pas compétent
    INFORM,    // information (ex. e-mail finalement envoyé)
    FAILURE    // erreur pendant le traitement (LLM indisponible...)
}
