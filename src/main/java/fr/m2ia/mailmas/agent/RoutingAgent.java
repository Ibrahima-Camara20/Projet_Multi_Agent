package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.routing.RoutingDecision;
import fr.m2ia.mailmas.routing.RoutingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent coordinateur : choisit l'agent spécialisé, lui délègue la rédaction par le
 * {@link MessageBus} et récupère sa proposition.
 *
 * <p>
 * <b>Algorithme</b> ({@link #process}) :
 * </p>
 * <ol>
 * <li>La {@link RoutingStrategy} classe les agents spécialisés enregistrés sur le bus.</li>
 * <li>Si le meilleur score est sous le seuil : <b>aucun agent adapté</b>, on passe
 * directement à l'agent de repli ({@code fallback = true}).</li>
 * <li>Si les deux meilleurs sont à moins de {@code ambiguityMargin} : <b>cas ambigu</b>. Le
 * meilleur est choisi et l'ambiguïté signalée à l'utilisateur, qui peut changer d'agent. Si
 * une stratégie de départage (ex. LLM) est configurée, elle est consultée sur les seuls
 * candidats à égalité et son choix passe en tête.</li>
 * <li>Ordre d'essai : candidats au-dessus du seuil (par score décroissant), puis l'agent de
 * repli, dans la limite de {@code maxAttempts}. Un REFUSE fait passer au suivant ; un FAILURE
 * (LLM indisponible...) arrête tout, car les autres agents échoueraient de la même façon.</li>
 * </ol>
 *
 * <p>
 * Le RoutingAgent est lui-même un {@link Agent} : il peut recevoir une {@code DraftRequest}
 * sur le bus et y répondre comme un agent spécialisé, ce qui permet plusieurs niveaux de
 * routage (un router qui délègue à des sous-routers).
 * </p>
 */
public class RoutingAgent implements Agent {

    /** Paramètres de coordination. */
    public record Settings(double threshold, double ambiguityMargin, int maxAttempts) {
        public Settings {
            if (threshold < 0 || threshold > 1)
                throw new IllegalArgumentException("threshold dans [0, 1]");
            if (ambiguityMargin < 0)
                throw new IllegalArgumentException("ambiguityMargin >= 0");
            if (maxAttempts < 1)
                throw new IllegalArgumentException("maxAttempts >= 1");
        }

        public static Settings from(AppConfig c) {
            return new Settings(c.routingThreshold(), c.ambiguityMargin(), c.maxRoutingAttempts());
        }

        public static Settings defaults() {
            return from(AppConfig.defaults());
        }
    }

    private final AgentProfile profile;
    private final MessageBus bus;
    private final RoutingStrategy strategy;
    private final RoutingStrategy tieBreaker; // peut être null
    private final Settings settings;

    public RoutingAgent(MessageBus bus, RoutingStrategy strategy, Settings settings) {
        this(bus, strategy, null, settings);
    }

    public RoutingAgent(MessageBus bus, RoutingStrategy strategy, RoutingStrategy tieBreaker, Settings settings) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.tieBreaker = tieBreaker;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.profile = new AgentProfile(Participants.ROUTER, "Routing Agent",
                "Coordinateur : choisit l'agent spécialisé et lui délègue la rédaction",
                null, List.of(), RoutingHints.none(), false);
    }

    @Override
    public AgentProfile profile() {
        return profile;
    }

    public RoutingStrategy strategy() {
        return strategy;
    }

    public Settings settings() {
        return settings;
    }

    /** Agents spécialisés actuellement disponibles (tout ce qui est sur le bus, sauf le router). */
    public List<AgentProfile> specialists() {
        return bus.registeredAgents().stream().filter(p -> !p.id().equals(id())).toList();
    }

    /** Classe les agents sans rien leur demander (aperçu dans l'interface, expériences). */
    public RoutingDecision decide(Email email) {
        return strategy.route(email, specialists());
    }

    /**
     * Routing complet puis délégation : première proposition pour un e-mail.
     *
     * @param feedback commentaires de rejet déjà connus (vide pour un nouvel e-mail)
     * @param attempt  numéro de la proposition demandée (1 pour un nouvel e-mail)
     */
    public RoutingOutcome process(Email email, List<UserFeedback> feedback, int attempt) {
        List<AgentProfile> candidates = specialists();
        RoutingDecision decision = strategy.route(email, candidates);

        boolean noSuitable = decision.noSuitableAgent(settings.threshold());
        boolean ambiguous = !noSuitable && decision.isAmbiguous(settings.ambiguityMargin());

        List<String> order = new ArrayList<>();
        String note = null;

        if (ambiguous && tieBreaker != null) {
            Optional<RoutingDecision.Candidate> winner = breakTie(email, decision, candidates);
            if (winner.isPresent()) {
                order.add(winner.get().agentId());
                note = "Cas ambigu départagé par " + tieBreaker.name() + " : " + winner.get().agentId();
            }
        }
        if (!noSuitable) {
            decision.ranking().stream()
                    .filter(c -> c.score() >= settings.threshold())
                    .map(RoutingDecision.Candidate::agentId)
                    .forEach(id -> addOnce(order, id));
        }
        candidates.stream().filter(AgentProfile::fallback).map(AgentProfile::id)
                .forEach(id -> addOnce(order, id));
        if (order.isEmpty()) {
            // ni candidat au-dessus du seuil ni agent de repli : on tente quand même le meilleur
            decision.ranking().stream().map(RoutingDecision.Candidate::agentId).forEach(id -> addOnce(order, id));
            if (!order.isEmpty())
                note = "Aucun agent de repli configuré : essai des agents sous le seuil";
        }

        if (order.isEmpty()) {
            return new RoutingOutcome(RoutingOutcome.Status.FAILED, decision, null, false, null,
                    "aucun agent spécialisé enregistré", List.of(), null);
        }
        return dispatch(email, feedback, attempt, decision, ambiguous,
                order.subList(0, Math.min(order.size(), settings.maxAttempts())), Participants.ROUTER, note);
    }

    /**
     * Demande directe à un agent donné, au nom de l'utilisateur : révision après rejet (même
     * agent) ou choix manuel d'un autre agent. Pas de nouveau routing, pas de refus pour
     * compétence.
     *
     * @param decision décision de routing initiale, conservée pour l'affichage
     */
    public RoutingOutcome dispatchTo(String agentId, Email email, List<UserFeedback> feedback, int attempt,
            RoutingDecision decision) {
        return dispatch(email, feedback, attempt, decision, false, List.of(agentId), Participants.USER, null);
    }

    private RoutingOutcome dispatch(Email email, List<UserFeedback> feedback, int attempt,
            RoutingDecision decision, boolean ambiguous, List<String> order, String requester, String note) {
        List<String> refusals = new ArrayList<>();
        String lastAgent = null;
        for (String agentId : order) {
            lastAgent = agentId;
            AgentMessage request = AgentMessage.of(Performative.REQUEST, requester, agentId, email.id(),
                    new Payload.DraftRequest(email, feedback, attempt));
            AgentMessage reply = bus.send(request);

            switch (reply.payload()) {
                case Payload.DraftProposal p when reply.performative() == Performative.PROPOSE -> {
                    return new RoutingOutcome(RoutingOutcome.Status.PROPOSED, decision, agentId, ambiguous,
                            p.draft(), null, refusals, note);
                }
                case Payload.Refusal r -> refusals.add(agentId + " : " + r.reason());
                case Payload.Failure f -> {
                    return new RoutingOutcome(RoutingOutcome.Status.FAILED, decision, agentId, ambiguous, null,
                            "échec de l'agent " + agentId + " : " + f.reason(), refusals, note);
                }
                default -> {
                    return new RoutingOutcome(RoutingOutcome.Status.FAILED, decision, agentId, ambiguous, null,
                            "réponse inattendue de " + agentId + " : " + reply.performative(), refusals, note);
                }
            }
        }
        return new RoutingOutcome(RoutingOutcome.Status.REFUSED, decision, lastAgent, ambiguous, null,
                "aucun agent n'a accepté de traiter l'e-mail", refusals, note);
    }

    private Optional<RoutingDecision.Candidate> breakTie(Email email, RoutingDecision decision,
            List<AgentProfile> candidates) {
        double best = decision.best().map(RoutingDecision.Candidate::score).orElse(0.0);
        Set<String> tied = decision.ranking().stream()
                .filter(c -> c.score() >= settings.threshold() && best - c.score() < settings.ambiguityMargin())
                .map(RoutingDecision.Candidate::agentId)
                .collect(Collectors.toSet());
        List<AgentProfile> contenders = candidates.stream().filter(p -> tied.contains(p.id())).toList();
        if (contenders.size() < 2)
            return Optional.empty();
        RoutingDecision d = tieBreaker.route(email, contenders);
        return d.best().filter(c -> c.score() > 0 && tied.contains(c.agentId()));
    }

    private static void addOnce(List<String> list, String id) {
        if (!list.contains(id))
            list.add(id);
    }

    /**
     * Le router répond lui-même à une {@code DraftRequest} reçue par le bus (routage à plusieurs
     * niveaux) : il route, délègue, puis renvoie la proposition obtenue.
     */
    @Override
    public AgentMessage handle(AgentMessage message) {
        if (!(message.payload() instanceof Payload.DraftRequest req) || req.email() == null) {
            return message.reply(Performative.REFUSE, new Payload.Refusal("message non supporté"));
        }
        RoutingOutcome out = process(req.email(), req.previousFeedback(), req.attempt());
        return switch (out.status()) {
            case PROPOSED -> message.reply(Performative.PROPOSE, new Payload.DraftProposal(out.draft()));
            case REFUSED -> message.reply(Performative.REFUSE, new Payload.Refusal(out.explanation()));
            case FAILED -> message.reply(Performative.FAILURE, new Payload.Failure(out.explanation()));
        };
    }
}
