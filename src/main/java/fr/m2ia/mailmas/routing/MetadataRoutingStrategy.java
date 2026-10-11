package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Classe les agents selon les <b>métadonnées</b> de l'e-mail ({@link Email#metadata()}) :
 * en-têtes de liste de diffusion ({@code list-id}), dossier ou étiquette posés par le client de
 * messagerie ou par l'utilisateur, catégorie, etc.
 *
 * <p>
 * Deux indices, sans nouveau champ dans le profil :
 * </p>
 * <ul>
 * <li>une valeur égale à l'identifiant de l'agent (ex. {@code label = student}) : étiquette
 * posée explicitement, indice fort ({@code labelScore}, 0,9 par défaut) ;</li>
 * <li>un mot-clé du profil trouvé dans une valeur (ex. {@code list-id =
 * supermas-wp3.partner-lab.eu} contient « SuperMAS ») : indice moyen ({@code keywordScore},
 * 0,5 par défaut).</li>
 * </ul>
 * <p>
 * Utile pour les e-mails automatiques (listes, notifications) dont l'objet et l'expéditeur ne
 * disent rien du contexte.
 * </p>
 */
public class MetadataRoutingStrategy implements RoutingStrategy {

    public static final String NAME = "metadata";

    private final double labelScore;
    private final double keywordScore;

    public MetadataRoutingStrategy() {
        this(0.9, 0.5);
    }

    public MetadataRoutingStrategy(double labelScore, double keywordScore) {
        this.labelScore = TextMatching.clamp(labelScore);
        this.keywordScore = TextMatching.clamp(keywordScore);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        Map<String, String> meta = new TreeMap<>(); // ordre stable pour les justifications
        email.metadata().forEach((k, v) -> meta.put(k, TextMatching.normalize(v)));

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        for (AgentProfile p : candidates) {
            if (p.fallback())
                continue;
            double score = 0;
            String reason = "aucune métadonnée utile";
            String id = TextMatching.normalize(p.id());
            for (var e : meta.entrySet()) {
                if (e.getValue().equals(id)) {
                    score = labelScore;
                    reason = "étiquette " + e.getKey() + " = " + p.id();
                    break;
                }
            }
            if (score == 0) {
                search: for (var e : meta.entrySet()) {
                    for (String kw : p.routingHints().keywords().stream().sorted().toList()) {
                        if (TextMatching.containsTerm(e.getValue(), kw)) {
                            score = keywordScore;
                            reason = "mot-clé « " + kw + " » dans " + e.getKey();
                            break search;
                        }
                    }
                }
            }
            ranking.add(new RoutingDecision.Candidate(p.id(), score, reason));
        }
        return new RoutingDecision(NAME, ranking);
    }
}
