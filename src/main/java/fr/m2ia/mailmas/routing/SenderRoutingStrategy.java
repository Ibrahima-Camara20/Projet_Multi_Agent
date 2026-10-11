package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.persistence.ContactDirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Classe les agents selon l'expéditeur : motif d'adresse ({@code senderPatterns}) et rôle
 * connu dans l'annuaire ({@code senderRoles}, via {@link ContactDirectory}).
 *
 * <p>
 * Les deux indices se combinent comme des probabilités indépendantes :
 * {@code 1 - (1 - motif)(1 - rôle)}. Un rôle connu est un indice un peu plus fort qu'un
 * domaine d'adresse (0,8 contre 0,7 par défaut) car il a été renseigné par l'utilisateur.
 * </p>
 *
 * <p>
 * Cette stratégie seule ne suffit pas : une même personne peut écrire dans des contextes
 * différents. Elle est donc combinée aux autres dans {@link WeightedRoutingStrategy}.
 * </p>
 */
public class SenderRoutingStrategy implements RoutingStrategy {

    public static final String NAME = "sender";

    private final ContactDirectory directory; // peut être null : seuls les motifs comptent
    private final double patternScore;
    private final double roleScore;

    public SenderRoutingStrategy(ContactDirectory directory) {
        this(directory, 0.7, 0.8);
    }

    public SenderRoutingStrategy(ContactDirectory directory, double patternScore, double roleScore) {
        this.directory = directory;
        this.patternScore = TextMatching.clamp(patternScore);
        this.roleScore = TextMatching.clamp(roleScore);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        String address = TextMatching.emailAddress(email.from());
        Optional<Contact> contact = directory == null ? Optional.empty() : directory.findByEmail(address);

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        for (AgentProfile p : candidates) {
            if (p.fallback())
                continue;
            List<String> reasons = new ArrayList<>();
            double pattern = 0;
            for (String glob : p.routingHints().senderPatterns().stream().sorted().toList()) {
                if (TextMatching.matchesGlob(address, glob)) {
                    pattern = patternScore;
                    reasons.add("adresse correspond à " + glob);
                    break;
                }
            }
            double role = 0;
            if (contact.isPresent()) {
                for (String r : p.routingHints().senderRoles().stream().sorted().toList()) {
                    if (contact.get().hasRole(r)) {
                        role = roleScore;
                        reasons.add("rôle connu : " + r);
                        break;
                    }
                }
            }
            double score = TextMatching.clamp(1 - (1 - pattern) * (1 - role));
            ranking.add(new RoutingDecision.Candidate(p.id(), score,
                    reasons.isEmpty() ? "expéditeur non reconnu" : String.join(", ", reasons)));
        }
        return new RoutingDecision(NAME, ranking);
    }
}
