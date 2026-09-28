package fr.m2ia.mailmas.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Contexte final envoyé au LLM, déjà filtré selon le budget. */
public record Context(List<ContextItem> items) {

    public Context { items = List.copyOf(items); }

    public static Context empty() { return new Context(List.of()); }

    public int estimatedTokens() {
        return items.stream().mapToInt(ContextItem::estimatedTokens).sum();
    }

    /** Garde les éléments les plus pertinents tant que le budget le permet. */
    public static Context withinBudget(List<ContextItem> candidates, int tokenBudget) {
        int used = 0;
        List<ContextItem> kept = new ArrayList<>();
        for (ContextItem it : candidates.stream()
                .sorted(Comparator.comparingDouble(ContextItem::relevance).reversed()).toList()) {
            if (used + it.estimatedTokens() > tokenBudget) continue;
            kept.add(it);
            used += it.estimatedTokens();
        }
        return new Context(kept);
    }

    public String render() {
        return items.stream()
                .map(i -> "[" + i.source() + "]\n" + i.content())
                .collect(Collectors.joining("\n\n"));
    }
}
