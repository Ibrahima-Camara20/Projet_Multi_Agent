package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Classe les agents selon les mots-clés de leur profil trouvés dans l'objet et le corps.
 *
 * <p>
 * Score : chaque mot-clé distinct trouvé ajoute un poids (plus fort dans l'objet que dans le
 * corps), puis le total w est ramené dans [0, 1] par une saturation {@code 1 - exp(-k·w)}.
 * Contrairement à « mots trouvés / mots du profil », un agent n'est pas pénalisé parce que son
 * profil contient beaucoup de mots-clés, et quelques indices concordants suffisent pour un
 * score élevé. Avec les valeurs par défaut : 1 mot dans le corps → 0,45 ; 1 mot dans l'objet →
 * 0,59 ; 2 mots → 0,70 ; 3 mots → 0,83.
 * </p>
 *
 * <p>
 * La comparaison ignore la casse et les accents et porte sur des mots entiers
 * (voir {@link TextMatching#containsTerm}). L'agent de repli n'est pas classé.
 * </p>
 */
public class KeywordRoutingStrategy implements RoutingStrategy {

    public static final String NAME = "keyword";

    private final double subjectWeight;
    private final double steepness;

    public KeywordRoutingStrategy() {
        this(1.5, 0.6);
    }

    /**
     * @param subjectWeight poids d'un mot-clé trouvé dans l'objet (1 dans le corps)
     * @param steepness     k de la saturation : plus il est grand, plus le score monte vite
     */
    public KeywordRoutingStrategy(double subjectWeight, double steepness) {
        if (subjectWeight <= 0 || steepness <= 0)
            throw new IllegalArgumentException("paramètres > 0");
        this.subjectWeight = subjectWeight;
        this.steepness = steepness;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        String subject = TextMatching.normalize(email.subject());
        String body = TextMatching.normalize(email.body());

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        for (AgentProfile p : candidates) {
            if (p.fallback())
                continue;
            double weight = 0;
            List<String> hits = new ArrayList<>();
            for (String kw : p.routingHints().keywords().stream().sorted().toList()) {
                if (TextMatching.containsTerm(subject, kw)) {
                    weight += subjectWeight;
                    hits.add(kw + " (objet)");
                } else if (TextMatching.containsTerm(body, kw)) {
                    weight += 1;
                    hits.add(kw);
                }
            }
            double score = TextMatching.clamp(1 - Math.exp(-steepness * weight));
            String reason = hits.isEmpty() ? "aucun mot-clé"
                    : String.format(Locale.ROOT, "mots-clés : %s", String.join(", ", hits));
            ranking.add(new RoutingDecision.Candidate(p.id(), score, reason));
        }
        return new RoutingDecision(NAME, ranking);
    }
}
