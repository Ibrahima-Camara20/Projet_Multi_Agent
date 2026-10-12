package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Favorise l'agent qui a déjà traité le fil de discussion : une suite d'échange (« Merci, et
 * pour la date ? ») ne contient souvent aucun mot-clé, mais appartient au même contexte.
 *
 * <p>
 * L'information vient de {@link ConversationMemory} : les brouillons y sont enregistrés avec
 * l'identifiant de l'agent auteur. Seul le dernier agent ayant proposé une réponse dans le fil
 * (parmi les {@code lookback} entrées les plus récentes) reçoit un score.
 * </p>
 */
public class ThreadAffinityStrategy implements RoutingStrategy {

    public static final String NAME = "thread";

    private final ConversationMemory memory;
    private final int lookback;
    private final double affinityScore;

    public ThreadAffinityStrategy(ConversationMemory memory, int lookback) {
        this(memory, lookback, 0.6);
    }

    public ThreadAffinityStrategy(ConversationMemory memory, int lookback, double affinityScore) {
        this.memory = Objects.requireNonNull(memory, "memory");
        if (lookback < 1)
            throw new IllegalArgumentException("lookback >= 1");
        this.lookback = lookback;
        this.affinityScore = TextMatching.clamp(affinityScore);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        List<ConversationEntry> history = memory.recent(email.threadId(), lookback);
        String previousAgent = null;
        for (int i = history.size() - 1; i >= 0 && previousAgent == null; i--) {
            ConversationEntry e = history.get(i);
            if (e.kind() == ConversationEntry.Kind.DRAFT) {
                previousAgent = e.author();
            }
        }

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        for (AgentProfile p : candidates) {
            if (p.fallback())
                continue;
            boolean same = p.id().equals(previousAgent);
            ranking.add(new RoutingDecision.Candidate(p.id(), same ? affinityScore : 0,
                    same ? "a déjà répondu dans ce fil" : "pas d'historique dans ce fil"));
        }
        return new RoutingDecision(NAME, ranking);
    }
}
