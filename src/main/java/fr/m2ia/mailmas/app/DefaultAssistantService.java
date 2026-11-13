package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.agent.Agent;
import fr.m2ia.mailmas.agent.AgentFactory;
import fr.m2ia.mailmas.agent.AgentMessage;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.MessageBus;
import fr.m2ia.mailmas.agent.Participants;
import fr.m2ia.mailmas.agent.Payload;
import fr.m2ia.mailmas.agent.Performative;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.RoutingOutcome;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.persistence.AgentRepository;
import fr.m2ia.mailmas.review.CriticAgent;
import fr.m2ia.mailmas.review.Finding;
import fr.m2ia.mailmas.review.Finding.Severity;
import fr.m2ia.mailmas.routing.RoutingDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Point d'entrée de l'interface graphique (Facade) : cache le bus, le router et les agents.
 *
 * <p>
 * <b>Cycle de vie d'un e-mail</b> : {@code submit} (routing + 1re proposition) →
 * éventuellement {@code revise} (rejet avec commentaire, même agent) ou {@code reroute}
 * (l'utilisateur choisit un autre agent), autant de fois que nécessaire → {@code accept}
 * (validation, avec ou sans modification). Le système ne fait que proposer : rien n'est
 * « envoyé » sans {@code accept}.
 * </p>
 *
 * <p>
 * <b>État</b> : pour chaque e-mail en cours, le service garde l'e-mail, la décision de routing,
 * l'agent retenu, tous les commentaires de rejet, le numéro de proposition et la dernière
 * proposition. Les commentaires sont cumulés : à la 3e proposition, l'agent reçoit les deux
 * rejets précédents.
 * </p>
 *
 * <p>
 * <b>Mémoire</b> : ce service est le seul à écrire dans {@link ConversationMemory} (e-mail
 * reçu, chaque brouillon, réponse envoyée) ; le DraftGenerator ne fait que lire.
 * </p>
 *
 * <p>
 * <b>Relecture</b> (si un agent critique est fourni) : chaque proposition lui est envoyée sur le
 * bus avant d'être rendue à l'interface. Défaut corrigible (formule interdite, mauvaise langue) :
 * l'agent rédacteur réécrit une fois, automatiquement. Risque de manipulation ou de divulgation :
 * la rédaction est confiée à l'agent de repli, qui n'a accès à aucun contexte. Le reste est
 * signalé à l'utilisateur ({@link #currentReview(String)}).
 * </p>
 */
public class DefaultAssistantService implements AssistantService {

    private final MessageBus bus;
    private final RoutingAgent router;
    private final AgentRepository repository;
    private final AgentFactory factory;
    private final ConversationMemory memory;
    private final Agent critic; // peut être null : pas de relecture
    private final List<FeedbackListener> feedbackListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Map<String, Case> cases = new ConcurrentHashMap<>();

    /**
     * Enregistre le router sur le bus et charge les agents du repository.
     */
    public DefaultAssistantService(MessageBus bus, RoutingAgent router, AgentRepository repository,
            AgentFactory factory, ConversationMemory memory) {
        this(bus, router, repository, factory, memory, null);
    }

    /**
     * @param critic agent critique enregistré sur le bus, ou null pour désactiver la relecture
     */
    public DefaultAssistantService(MessageBus bus, RoutingAgent router, AgentRepository repository,
            AgentFactory factory, ConversationMemory memory, Agent critic) {
        this.critic = critic;
        this.bus = Objects.requireNonNull(bus, "bus");
        this.router = Objects.requireNonNull(router, "router");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.memory = Objects.requireNonNull(memory, "memory");
        loadAgents();
    }

    // ------------------------------------------------------------------ AssistantService

    @Override
    public ProcessingResult submit(Email email) {
        Objects.requireNonNull(email, "email");
        memory.recordReceived(email);

        Case c = new Case(email);
        cases.put(email.id(), c);
        synchronized (c) {
            c.attempt = 1;
            RoutingOutcome out = router.process(email, List.of(), c.attempt);
            c.decision = out.decision();
            c.ambiguous = out.ambiguous();
            // Si tous les agents ont refusé, personne n'est responsable : l'utilisateur choisira (reroute).
            // Si l'agent a échoué (LLM indisponible), il reste responsable : revise() réessaiera.
            if (out.status() != RoutingOutcome.Status.REFUSED)
                c.agentId = out.agentId();
            return record(c, out);
        }
    }

    @Override
    public ProcessingResult revise(String emailId, UserFeedback feedback) {
        Case c = cases.get(emailId);
        if (c == null)
            return ProcessingResult.failed(null, null, null, "e-mail inconnu : " + emailId);
        synchronized (c) {
            if (c.closed)
                return ProcessingResult.failed(c.email, c.decision, c.agentId, "réponse déjà validée");
            if (c.agentId == null)
                return ProcessingResult.failed(c.email, c.decision, null,
                        "aucun agent à qui redemander : choisissez un agent (reroute)");
            if (feedback != null)
                c.feedback.add(feedback);
            if (feedback != null && feedback.decision() == UserFeedback.Decision.REJECT && c.lastDraft != null)
                notifyFeedback(c.lastDraft.agentId(), FeedbackListener.Outcome.REJECTED);
            c.attempt++;
            RoutingOutcome out = router.dispatchTo(c.agentId, c.email, c.feedback, c.attempt, c.decision);
            return record(c, out);
        }
    }

    @Override
    public ProcessingResult reroute(String emailId, String agentId) {
        Case c = cases.get(emailId);
        if (c == null)
            return ProcessingResult.failed(null, null, agentId, "e-mail inconnu : " + emailId);
        synchronized (c) {
            if (c.closed)
                return ProcessingResult.failed(c.email, c.decision, c.agentId, "réponse déjà validée");
            if (c.lastDraft != null && !agentId.equals(c.lastDraft.agentId()))
                notifyFeedback(c.lastDraft.agentId(), FeedbackListener.Outcome.REROUTED);
            c.attempt++;
            RoutingOutcome out = router.dispatchTo(agentId, c.email, c.feedback, c.attempt, c.decision);
            if (out.proposed()) {
                c.agentId = agentId; // les révisions suivantes iront à l'agent choisi
                c.ambiguous = false;
            }
            return record(c, out);
        }
    }

    @Override
    public void accept(String emailId, UserFeedback feedback) {
        Case c = cases.get(emailId);
        if (c == null)
            throw new IllegalArgumentException("e-mail inconnu : " + emailId);
        synchronized (c) {
            if (c.closed)
                throw new IllegalStateException("réponse déjà validée pour " + emailId);
            if (c.lastDraft == null)
                throw new IllegalStateException("aucune proposition à valider pour " + emailId);
            if (feedback != null && feedback.decision() == UserFeedback.Decision.REJECT)
                throw new IllegalArgumentException("un rejet passe par revise(), pas accept()");

            DraftReply sent = c.lastDraft;
            if (feedback != null && feedback.decision() == UserFeedback.Decision.EDIT
                    && feedback.editedBody() != null) {
                sent = sent.withBody(feedback.editedBody());
            }
            memory.recordSent(c.email.threadId(), sent);
            c.sent = sent;
            c.closed = true;
            boolean edited = !sent.body().strip().equals(c.lastDraft.body().strip());
            notifyFeedback(c.lastDraft.agentId(),
                    edited ? FeedbackListener.Outcome.EDITED : FeedbackListener.Outcome.ACCEPTED);
        }
    }

    /**
     * Remplace les agents spécialisés du bus par ceux du repository. Le router reste en place ;
     * les e-mails en cours gardent leur agent (s'il a été supprimé, la révision échouera avec
     * un message clair et l'utilisateur pourra choisir un autre agent).
     */
    @Override
    public void reloadAgents() {
        loadAgents();
    }

    private void loadAgents() {
        List<AgentProfile> profiles = repository.findAll();
        long fallbacks = profiles.stream().filter(AgentProfile::fallback).count();
        if (fallbacks > 1) {
            throw new IllegalStateException("un seul agent de repli autorisé, trouvé " + fallbacks);
        }
        synchronized (bus) {
            for (AgentProfile p : List.copyOf(bus.registeredAgents())) {
                if (!p.id().equals(router.id()))
                    bus.unregister(p.id());
            }
            bus.register(router);
            if (critic != null)
                bus.register(critic);
            for (AgentProfile p : profiles) {
                if (p.id().equals(router.id()) || p.id().equals(Participants.CRITIC))
                    throw new IllegalStateException("identifiant réservé : " + p.id());
                bus.register(factory.create(p));
            }
        }
    }

    // ------------------------------------------------------------------ consultation (UI)

    /** Dernière proposition faite pour un e-mail, s'il y en a une. */
    public Optional<DraftReply> currentDraft(String emailId) {
        return Optional.ofNullable(cases.get(emailId)).map(c -> c.lastDraft);
    }

    /** Réponse validée pour un e-mail, s'il a été accepté. */
    public Optional<DraftReply> sentReply(String emailId) {
        return Optional.ofNullable(cases.get(emailId)).map(c -> c.sent);
    }

    /** Être prévenu des décisions de l'utilisateur sur les propositions (réputation des agents). */
    public void addFeedbackListener(FeedbackListener listener) {
        feedbackListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyFeedback(String agentId, FeedbackListener.Outcome outcome) {
        for (FeedbackListener l : feedbackListeners) {
            try {
                l.onFeedback(agentId, outcome);
            } catch (RuntimeException e) {
                // un observateur en panne ne doit pas empêcher le traitement de l'e-mail
            }
        }
    }

    /** Remarques de l'agent critique sur la dernière proposition (vide : rien à signaler). */
    public List<Finding> currentReview(String emailId) {
        return Optional.ofNullable(cases.get(emailId)).map(c -> c.findings).orElse(List.of());
    }

    /** Agent actuellement responsable d'un e-mail. */
    public Optional<String> assignedAgent(String emailId) {
        return Optional.ofNullable(cases.get(emailId)).map(c -> c.agentId);
    }

    // ------------------------------------------------------------------ interne

    private ProcessingResult record(Case c, RoutingOutcome out) {
        c.findings = List.of();
        if (out.proposed() && critic != null) {
            out = reviewAndFix(c, out);
        }
        if (out.proposed()) {
            c.lastDraft = out.draft();
            memory.recordDraft(c.email.threadId(), out.draft());
            return new ProcessingResult(ProcessingResult.Status.PROPOSED, c.email, c.decision, out.agentId(),
                    c.ambiguous, out.draft(), out.explanation());
        }
        return ProcessingResult.failed(c.email, c.decision, out.agentId(), out.explanation());
    }

    /**
     * Fait relire la proposition par l'agent critique et applique sa décision : réécriture
     * automatique (une seule fois) ou passage à l'agent de repli. Met à jour c.findings.
     */
    private RoutingOutcome reviewAndFix(Case c, RoutingOutcome out) {
        List<Finding> findings = askCritic(c.email, out.draft());
        String note = null;

        if (has(findings, Severity.BLOCK)) {
            Optional<String> fallback = repository.findAll().stream().filter(AgentProfile::fallback)
                    .map(AgentProfile::id).findFirst();
            if (fallback.isPresent() && !fallback.get().equals(out.agentId())) {
                // La proposition bloquée n'est PAS enregistrée et l'agent de repli repart de zéro (essai 1) :
                // sinon elle lui serait montrée comme « proposition précédente » et la fuite passerait
                // par la mémoire du fil. L'isolation doit couvrir le contexte ET l'historique.
                c.attempt = 1;
                RoutingOutcome safe = router.redispatch(fallback.get(), c.email, List.of(), c.attempt, c.decision);
                if (safe.proposed()) {
                    note = "Relecture : risque de manipulation ou de divulgation, proposition confiée à l'agent "
                            + "de repli " + fallback.get() + " qui n'a accès à aucun contexte";
                    List<Finding> kept = new ArrayList<>(findings.stream()
                            .filter(f -> f.severity() == Severity.BLOCK).toList());
                    askCritic(c.email, safe.draft()).stream()
                            .filter(f -> kept.stream().noneMatch(k -> k.code().equals(f.code())))
                            .forEach(kept::add);
                    findings = kept;
                    c.agentId = fallback.get();
                    c.ambiguous = false;
                    out = safe;
                }
            }
        } else if (has(findings, Severity.REWRITE)) {
            memory.recordDraft(c.email.threadId(), out.draft());
            List<UserFeedback> feedback = new ArrayList<>(c.feedback);
            feedback.add(UserFeedback.reject(CriticAgent.rewriteInstruction(c.email, findings)));
            c.attempt++;
            RoutingOutcome again = router.redispatch(out.agentId(), c.email, feedback, c.attempt, c.decision);
            if (again.proposed()) {
                note = "Relecture : proposition réécrite automatiquement ("
                        + String.join(", ", findings.stream().filter(f -> f.severity() == Severity.REWRITE)
                                .map(f -> f.code().toLowerCase()).toList()) + ")";
                findings = askCritic(c.email, again.draft());
                out = again;
            }
        }
        c.findings = List.copyOf(findings);
        if (note == null) {
            return out;
        }
        String full = out.note() == null ? note : out.note() + ". " + note;
        return new RoutingOutcome(out.status(), out.decision(), out.agentId(), out.ambiguous(), out.draft(),
                out.reason(), out.refusals(), full);
    }

    private List<Finding> askCritic(Email email, DraftReply draft) {
        AgentMessage reply = bus.send(AgentMessage.of(Performative.REQUEST, Participants.ROUTER, critic.id(),
                email.id(), new Payload.ReviewRequest(email, draft)));
        return reply.payload() instanceof Payload.ReviewReport r ? r.findings() : List.of();
    }

    private static boolean has(List<Finding> findings, Severity severity) {
        return findings.stream().anyMatch(f -> f.severity() == severity);
    }

    /** État d'un e-mail en cours de traitement. */
    private static final class Case {
        final Email email;
        final List<UserFeedback> feedback = new ArrayList<>();
        RoutingDecision decision;
        String agentId;
        boolean ambiguous;
        int attempt;
        DraftReply lastDraft;
        DraftReply sent;
        boolean closed;
        List<Finding> findings = List.of();

        Case(Email email) {
            this.email = email;
        }
    }
}