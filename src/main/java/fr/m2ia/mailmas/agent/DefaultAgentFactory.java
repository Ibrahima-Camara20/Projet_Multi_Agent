package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.LlmRoutingStrategy;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.routing.ThreadAffinityStrategy;
import fr.m2ia.mailmas.routing.WeightedRoutingStrategy;

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

    /**
     * Auto-évaluation par règles : chaque agent juge le <b>contenu</b> de l'e-mail (ses
     * mots-clés) et sa <b>connaissance du fil</b> (il y a déjà répondu). L'expéditeur n'est
     * volontairement pas pris en compte : une même personne peut écrire dans des contextes
     * différents, c'est précisément le cas que ce refus corrige.
     *
     * <p>
     * Compromis mesuré (RoutingScenariosTest) : corrige « étudiant connu qui écrit pour vendre
     * son vélo », mais refuse aussi les messages courts sans mot-clé (« la salle a changé ? »).
     * Les deux cas ont les mêmes indices de surface ; seule une lecture sémantique les distingue
     * (voir {@link #withLlmSelfAssessment}).
     * </p>
     */
    public static DefaultAgentFactory withKeywordSelfAssessment(DraftGenerator generator, ConversationMemory memory) {
        RoutingStrategy self = WeightedRoutingStrategy.builder()
                .add(new KeywordRoutingStrategy(), 1.0)
                .add(new ThreadAffinityStrategy(memory, 10), 1.0)
                .build();
        return new DefaultAgentFactory(generator, self, DEFAULT_REFUSAL_THRESHOLD,
                SpecializedAgent.DEFAULT_MAX_PROPOSALS);
    }

    /**
     * Auto-évaluation sémantique : avant d'accepter une délégation du router, l'agent demande
     * au LLM local si l'e-mail relève de son domaine (description du profil) et refuse si le
     * modèle répond « aucun ». Plus juste que les mots-clés, mais un appel LLM de plus par
     * délégation.
     */
    public static DefaultAgentFactory withLlmSelfAssessment(DraftGenerator generator, LLMClient llm) {
        return new DefaultAgentFactory(generator, new LlmRoutingStrategy(llm), 0.5,
                SpecializedAgent.DEFAULT_MAX_PROPOSALS);
    }

    @Override
    public Agent create(AgentProfile profile) {
        return new SpecializedAgent(profile, generator, selfAssessment, refusalThreshold, maxProposals);
    }
}
