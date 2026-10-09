package fr.m2ia.mailmas.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite : regroupe plusieurs fournisseurs de contexte derrière une seule interface.
 * Ajouter une nouvelle source (notes utilisateur, anciens e-mails, RAG...) revient à ajouter
 * un fournisseur dans la liste, sans modifier le DraftGenerator.
 */
public class CompositeContextProvider implements ContextProvider {

    private final List<ContextProvider> providers;

    public CompositeContextProvider(List<ContextProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public boolean supports(String sourceId) {
        return providers.stream().anyMatch(p -> p.supports(sourceId));
    }

    @Override
    public List<ContextItem> retrieve(ContextQuery query) {
        List<ContextItem> all = new ArrayList<>();
        for (ContextProvider provider : providers) {
            all.addAll(provider.retrieve(query));
        }
        return all;
    }
}