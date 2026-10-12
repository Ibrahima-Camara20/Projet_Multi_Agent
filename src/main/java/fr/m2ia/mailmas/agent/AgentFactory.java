package fr.m2ia.mailmas.agent;

/**
 * Factory : construit l'agent correspondant à un profil.
 *
 * <p>
 * Le service ne connaît pas la classe concrète des agents. Par défaut tous les profils
 * donnent un {@link SpecializedAgent} ; une factory peut créer une sous-classe pour un profil
 * au comportement différent (agent critique, agent de validation...) sans modifier le reste.
 * </p>
 */
@FunctionalInterface
public interface AgentFactory {

    Agent create(AgentProfile profile);
}
