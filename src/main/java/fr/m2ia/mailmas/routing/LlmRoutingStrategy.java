package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.llm.ChatMessage;
import fr.m2ia.mailmas.llm.GenerationOptions;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.LLMRequest;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Demande au LLM local de choisir l'agent. Plus coûteuse (un appel au modèle) mais capable de
 * comprendre un e-mail sans mot-clé connu.
 *
 * <p>
 * Prévue comme <b>stratégie de départage</b> : le {@code RoutingAgent} ne l'appelle que pour
 * les cas ambigus, en ne lui présentant que les agents à égalité. Elle peut aussi être
 * utilisée seule pour comparer les configurations lors des expériences.
 * </p>
 *
 * <p>
 * Elle sert aussi à l'<b>auto-évaluation</b> d'un agent (un seul candidat : lui-même) : le
 * modèle peut répondre « aucun », et l'agent refuse alors la délégation.
 * </p>
 *
 * <p>
 * Le modèle doit répondre par un identifiant d'agent. Le candidat choisi reçoit
 * {@code chosenScore}, les autres 0 ; « aucun » donne 0 à tous. Si le LLM est indisponible ou
 * répond hors format, la décision est <b>vide</b> (« pas d'avis ») : le router garde sa
 * décision initiale et un agent qui s'auto-évalue accepte la demande, plutôt que de refuser à
 * cause d'une panne.
 * </p>
 */
public class LlmRoutingStrategy implements RoutingStrategy {

    public static final String NAME = "llm";

    private final LLMClient llm;
    private final double chosenScore;

    public LlmRoutingStrategy(LLMClient llm) {
        this(llm, 0.9);
    }

    public LlmRoutingStrategy(LLMClient llm, double chosenScore) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.chosenScore = TextMatching.clamp(chosenScore);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RoutingDecision route(Email email, Collection<AgentProfile> candidates) {
        List<AgentProfile> agents = candidates.stream().filter(p -> !p.fallback()).toList();
        if (agents.isEmpty())
            return new RoutingDecision(NAME, List.of());

        String answer;
        try {
            answer = llm.generate(buildRequest(email, agents)).text();
        } catch (LLMException | RuntimeException e) {
            return new RoutingDecision(NAME, List.of()); // pas d'avis
        }

        String chosen = parseAgentId(answer, agents);
        if (chosen == null) {
            if (saysNone(answer))
                return allZero(agents, "le LLM ne retient aucun agent");
            return new RoutingDecision(NAME, List.of()); // réponse hors format : pas d'avis
        }

        List<RoutingDecision.Candidate> ranking = new ArrayList<>();
        for (AgentProfile p : agents) {
            boolean ok = p.id().equals(chosen);
            ranking.add(new RoutingDecision.Candidate(p.id(), ok ? chosenScore : 0,
                    ok ? "choisi par le LLM (" + llm.modelName() + ")" : "non choisi par le LLM"));
        }
        return new RoutingDecision(NAME, ranking);
    }

    LLMRequest buildRequest(Email email, List<AgentProfile> agents) {
        StringBuilder sys = new StringBuilder();
        sys.append("Tu es l'agent de routage d'un assistant de messagerie. ")
                .append("Choisis l'agent le plus adapté pour rédiger la réponse à l'e-mail. ")
                .append("Réponds uniquement par l'identifiant de l'agent, sans autre texte, ")
                .append("ou par « aucun » si aucun agent ne convient.\n\nAgents :\n");
        for (AgentProfile p : agents) {
            sys.append("- ").append(p.id()).append(" : ").append(p.name());
            if (!p.description().isBlank())
                sys.append(" — ").append(p.description());
            sys.append('\n');
        }
        String user = "De : " + email.from() + "\nObjet : " + email.subject() + "\n\n" + email.body();
        return new LLMRequest(List.of(ChatMessage.system(sys.toString()), ChatMessage.user(user)),
                new GenerationOptions(0.0, 20));
    }

    /** Premier identifiant d'agent cité dans la réponse (les plus longs d'abord). */
    static String parseAgentId(String answer, List<AgentProfile> agents) {
        if (answer == null)
            return null;
        String text = answer.toLowerCase(Locale.ROOT);
        return agents.stream().map(AgentProfile::id)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(id -> Pattern.compile("(?<![\\w-])" + Pattern.quote(id.toLowerCase(Locale.ROOT))
                        + "(?![\\w-])").matcher(text).find())
                .findFirst().orElse(null);
    }

    static boolean saysNone(String answer) {
        String t = TextMatching.normalize(answer);
        return TextMatching.containsTerm(t, "aucun") || TextMatching.containsTerm(t, "none");
    }

    private static RoutingDecision allZero(List<AgentProfile> agents, String reason) {
        return new RoutingDecision(NAME, agents.stream()
                .map(p -> new RoutingDecision.Candidate(p.id(), 0, reason)).toList());
    }
}
