package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.routing.RoutingStrategy;

import java.util.Objects;

/** Crée un {@link SpecializedAgent} pour chaque profil. */
public class DefaultAgentFactory implements AgentFactory {

    /** Seuil par défaut des règles : l'agent ne refuse que s'il ne trouve aucun indice de son domaine. */
    public static final double DEFAULT_REFUSAL_THRESHOLD = 0.1;

    private final DraftGenerator generator;
    private final RoutingStrategy selfAssessment;
    private final double refusalThreshold;
    private final int maxProposals;

    /** Agents qui ne refusent jamais une demande de rédaction. */
    public DefaultAgentFactory(DraftGenerator generator) {
        this(generator, null, 0, SpecializedAgent.DEFAULT_MAX_PROPOSALS);
    }

    public DefaultAgentFactory(DraftGenerator generator, RoutingStrategy selfAssessment,
            double refusalThreshold, int maxProposals) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.selfAssessment = selfAssessment;
        this.refusalThreshold = refusalThreshold;
        this.maxProposals = maxProposals;
    }

    @Override
    public Agent create(AgentProfile profile) {
        return new SpecializedAgent(profile, generator, selfAssessment, refusalThreshold, maxProposals);
    }
}
