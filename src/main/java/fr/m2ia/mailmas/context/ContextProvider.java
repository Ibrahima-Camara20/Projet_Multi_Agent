package fr.m2ia.mailmas.context;

import java.util.List;

/**
 * Source de contexte (fichiers, notes utilisateur, RAG...).
 * Un agent n'accède qu'aux sources déclarées dans son profil : isolation des contextes.
 */
public interface ContextProvider {

    /** Vrai si ce fournisseur sait lire l'identifiant de source (ex. préfixe "file:"). */
    boolean supports(String sourceId);

    List<ContextItem> retrieve(ContextQuery query);
}
