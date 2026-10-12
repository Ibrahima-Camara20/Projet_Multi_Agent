package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingStrategy;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent spécialisé générique, entièrement piloté par son {@link AgentProfile}.
 *
 * <p>
 * Créer un nouvel agent = écrire un profil (JSON + system prompt), pas une classe. L'agent
 * décide <b>s'il</b> traite la demande ; la rédaction elle-même est déléguée au
 * {@link DraftGenerator} (Facade de la personne B), ce qui découple l'agent du LLM.
 * </p>
 *
 * <p>
 * <b>Autonomie : l'agent peut refuser</b> (REFUSE) :
 * </p>
 * <ul>
 * <li>un message qu'il ne sait pas traiter (autre chose qu'une {@code DraftRequest}) ;</li>
 * <li>une demande au-delà du nombre maximal de propositions pour un même e-mail ;</li>
 * <li>une délégation automatique du router pour un e-mail qu'il juge hors de son domaine.
 * L'agent s'auto-évalue avec sa propre stratégie (par défaut : mots-clés du contenu et
 * historique du fil), indépendante de celle du router. Exemple : un étudiant connu écrit à
 * propos d'une question administrative ; le router choisit l'agent « student » d'après
 * l'expéditeur, mais l'agent ne trouve aucun indice de son domaine dans le contenu et refuse ;
 * le router passe au candidat suivant.</li>
 * </ul>
 * <p>
 * Les demandes faites au nom de l'utilisateur ({@link Participants#USER} : révision, choix
 * manuel d'agent) ne sont jamais refusées pour raison de compétence, et l'agent de repli ne
 * refuse jamais un e-mail.
 * </p>
 */
public class SpecializedAgent implements Agent {

    public static final int DEFAULT_MAX_PROPOSALS = 5;

    private final AgentProfile profile;
    private final DraftGenerator generator;
    private final RoutingStrategy selfAssessment; // null : pas d'auto-évaluation
    private final double refusalThreshold;
    private final int maxProposals;

    /** Agent sans auto-évaluation : accepte toute demande de rédaction. */
    public SpecializedAgent(AgentProfile profile, DraftGenerator generator) {
        this(profile, generator, null, 0, DEFAULT_MAX_PROPOSALS);
    }

    /**
     * @param selfAssessment   stratégie avec laquelle l'agent évalue sa propre compétence
     *                         (null pour ne jamais refuser sur ce critère)
     * @param refusalThreshold score d'auto-évaluation en dessous duquel l'agent refuse
     * @param maxProposals     nombre maximal de propositions pour un même e-mail
     */
    public SpecializedAgent(AgentProfile profile, DraftGenerator generator,
            RoutingStrategy selfAssessment, double refusalThreshold, int maxProposals) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.selfAssessment = selfAssessment;
        this.refusalThreshold = refusalThreshold;
        if (maxProposals < 1)
            throw new IllegalArgumentException("maxProposals >= 1");
        this.maxProposals = maxProposals;
    }

    @Override
    public AgentProfile profile() {
        return profile;
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        if (!(message.payload() instanceof Payload.DraftRequest req)) {
            return refuse(message, "message non supporté : "
                    + message.payload().getClass().getSimpleName());
        }
        if (req.email() == null) {
            return refuse(message, "demande sans e-mail");
        }
        if (req.attempt() > maxProposals) {
            return refuse(message, "nombre maximal de propositions atteint (" + maxProposals + ")");
        }
        Optional<String> outOfScope = outOfScope(req.email(), message.sender());
        if (outOfScope.isPresent()) {
            return refuse(message, outOfScope.get());
        }

        try {
            DraftReply draft = generator.generate(req.email(), profile, req.previousFeedback(), req.attempt());
            if (draft == null) {
                return fail(message, "aucune proposition produite");
            }
            return message.reply(Performative.PROPOSE, new Payload.DraftProposal(draft));
        } catch (LLMException e) {
            return fail(message, "LLM : " + e.getMessage());
        } catch (RuntimeException e) {
            return fail(message, "erreur pendant la rédaction : " + e.getMessage());
        }
    }

    /** Raison du refus si l'agent se juge incompétent pour une délégation automatique. */
    private Optional<String> outOfScope(Email email, String requester) {
        if (selfAssessment == null || profile.fallback() || !Participants.ROUTER.equals(requester)
                || hasNoHints()) {
            return Optional.empty();
        }
        // Pas de candidat pour lui-même = la stratégie n'a pas d'avis (ex. LLM indisponible) : on accepte.
        var self = selfAssessment.route(email, List.of(profile)).ranking().stream()
                .filter(c -> c.agentId().equals(profile.id()))
                .findFirst();
        if (self.isEmpty())
            return Optional.empty();
        double score = self.get().score();
        if (score < refusalThreshold) {
            return Optional.of(String.format(Locale.ROOT,
                    "e-mail hors de mon domaine (auto-évaluation %.2f < %.2f)", score, refusalThreshold));
        }
        return Optional.empty();
    }

    private boolean hasNoHints() {
        RoutingHints h = profile.routingHints();
        return h.keywords().isEmpty() && h.senderPatterns().isEmpty() && h.senderRoles().isEmpty();
    }

    private static AgentMessage refuse(AgentMessage m, String reason) {
        return m.reply(Performative.REFUSE, new Payload.Refusal(reason));
    }

    private static AgentMessage fail(AgentMessage m, String reason) {
        return m.reply(Performative.FAILURE, new Payload.Failure(reason));
    }

    @Override
    public String toString() {
        return "SpecializedAgent[" + profile.id() + "]";
    }
}
