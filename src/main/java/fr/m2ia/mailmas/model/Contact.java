package fr.m2ia.mailmas.model;

import java.util.Set;

/** Identité connue d'un expéditeur : utile au routing (rôle) et au contexte. */
public record Contact(String email, String displayName, Set<String> roles) {
    public Contact {
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }
    public boolean hasRole(String role) { return roles.contains(role); }
}
