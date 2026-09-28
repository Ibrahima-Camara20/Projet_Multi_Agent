package fr.m2ia.mailmas.agent;

import java.util.Set;

/** Indices déclaratifs de routing associés à un agent (adresses, rôles, mots-clés). */
public record RoutingHints(Set<String> senderPatterns, Set<String> senderRoles, Set<String> keywords) {
    public RoutingHints {
        senderPatterns = senderPatterns != null ? Set.copyOf(senderPatterns) : Set.of();
        senderRoles = senderRoles != null ? Set.copyOf(senderRoles) : Set.of();
        keywords = keywords != null ? Set.copyOf(keywords) : Set.of();
    }
    public static RoutingHints none() { return new RoutingHints(null, null, null); }
}
