package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.agent.AgentFactory;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.MessageBus;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.RoutingOutcome;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.persistence.AgentRepository;
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
 */
public class DefaultAssistantService implements AssistantService {

    private final MessageBus bus;
    private final RoutingAgent router;
    private final AgentRepository repository;
    private final AgentFactory factory;
    private final ConversationMemory memory;

    private final Map<String, Case> cases = new ConcurrentHashMap<>();

    /**
     * Enregistre le router sur le bus et charge les agents du repository.
     */
    public DefaultAssistantService(MessageBus bus, RoutingAgent router, AgentRepository repository,
            AgentFactory factory, ConversationMemory memory) {
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
            for (AgentProfile p : profiles) {
                if (p.id().equals(router.id()))
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

    /** Agent actuellement responsable d'un e-mail. */
    public Optional<String> assignedAgent(String emailId) {
        return Optional.ofNullable(cases.get(emailId)).map(c -> c.agentId);
    }

    // ------------------------------------------------------------------ interne

    private ProcessingResult record(Case c, RoutingOutcome out) {
        if (out.proposed()) {
            c.lastDraft = out.draft();
            memory.recordDraft(c.email.threadId(), out.draft());
            return new ProcessingResult(ProcessingResult.Status.PROPOSED, c.email, c.decision, out.agentId(),
                    c.ambiguous, out.draft(), out.explanation());
        }
        return ProcessingResult.failed(c.email, c.decision, out.agentId(), out.explanation());
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

        Case(Email email) {
            this.email = email;
        }
    }
}
