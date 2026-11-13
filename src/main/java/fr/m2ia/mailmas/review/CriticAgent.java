package fr.m2ia.mailmas.review;

import fr.m2ia.mailmas.agent.Agent;
import fr.m2ia.mailmas.agent.AgentMessage;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.Participants;
import fr.m2ia.mailmas.agent.Payload;
import fr.m2ia.mailmas.agent.Performative;
import fr.m2ia.mailmas.agent.RoutingHints;
import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.ContextQuery;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.persistence.AgentRepository;
import fr.m2ia.mailmas.persistence.ContactDirectory;
import fr.m2ia.mailmas.review.Finding.Severity;
import fr.m2ia.mailmas.routing.TextMatching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent critique : relit chaque proposition avant l'utilisateur et vérifie qu'elle respecte les
 * normes du système (règles communes du prompt).
 *
 * <p>Nos expériences ont montré qu'une consigne dans le prompt ne suffit pas : le modèle écrit
 * encore « comme convenu », répond en français à un e-mail anglais, ou divulgue un dossier quand
 * un e-mail manipulé le demande. Le critique rend ces violations <b>détectables</b>, ce qui permet
 * de les corriger (réécriture automatique) ou de les signaler, au lieu de compter sur la seule
 * relecture humaine. Il ne fait aucun appel au LLM : ses vérifications sont rapides, déterministes
 * et explicables.</p>
 *
 * <p>Protocole : il reçoit un REQUEST contenant {@link Payload.ReviewRequest} et répond par un
 * INFORM contenant {@link Payload.ReviewReport}.</p>
 */
public class CriticAgent implements Agent {

    /** Formules qui affirment une pièce jointe ou un accord qui n'existent pas. */
    static final List<String> FORBIDDEN = List.of(
            "ci-joint", "ci-jointe", "ci-joints", "en piece jointe", "en pieces jointes", "comme convenu",
            "comme nous en avions convenu", "comme nous l'avions convenu", "je vous transmets ci-dessous",
            "as agreed", "please find attached", "i have attached", "attached you will find");

    /** Engagements pris à la place de l'utilisateur : à vérifier par lui. */
    static final List<String> COMMITMENTS = List.of(
            "je vous confirme ma", "je confirme ma", "je vous confirme que je", "je confirme que je",
            "j'accepte", "je m'engage", "c'est d'accord", "je m'en occupe", "je prends en charge",
            "je souhaite renouveler", "je souhaite la renouveler", "je souhaite le renouveler",
            "je serai present", "je serai presente", "i accept", "i confirm that i", "i will take care",
            "i'll take care", "i can take care", "i will handle", "i will attend");

    /** Instructions adressées à l'assistant dans un e-mail : signe d'une tentative de manipulation. */
    static final List<String> INJECTION = List.of(
            "ignore tes consignes", "ignore vos consignes", "ignore les consignes", "ignore tes instructions",
            "ignore vos instructions", "ignore les instructions", "oublie tes consignes", "oublie tes instructions",
            "tu es maintenant", "vous etes maintenant", "nouvelles consignes", "ignore your instructions",
            "ignore previous instructions", "ignore all previous", "disregard your instructions", "you are now");

    /** Références de dossier internes (ex. OM-2026-118). */
    private static final Pattern REFERENCE = Pattern.compile("\\b[A-Z]{1,4}-\\d{4}-\\d{2,}\\b");

    private final AgentProfile profile = new AgentProfile(Participants.CRITIC, "Agent critique",
            "Relit chaque proposition : formules interdites, langue, engagements, dates sans source, "
                    + "manipulation et divulgation", null, List.of(), RoutingHints.none(), false);

    private final ContextProvider context;
    private final AgentRepository agents;
    private final ContactDirectory contacts;

    public CriticAgent(ContextProvider context, AgentRepository agents, ContactDirectory contacts) {
        this.context = Objects.requireNonNull(context, "context");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.contacts = Objects.requireNonNull(contacts, "contacts");
    }

    @Override
    public AgentProfile profile() {
        return profile;
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        if (!(message.payload() instanceof Payload.ReviewRequest req) || req.email() == null || req.draft() == null) {
            return message.reply(Performative.REFUSE, new Payload.Refusal("message non supporté"));
        }
        return message.reply(Performative.INFORM, new Payload.ReviewReport(review(req.email(), req.draft())));
    }

    /** Toutes les vérifications, dans l'ordre de gravité. */
    public List<Finding> review(Email email, DraftReply draft) {
        String body = draft.body() == null ? "" : draft.body();
        String received = email.subject() + "\n" + email.body();
        List<Finding> findings = new ArrayList<>();

        String injection = TextChecks.firstTerm(received, INJECTION);
        if (injection != null) {
            findings.add(new Finding(Severity.BLOCK, "INJECTION", "L'e-mail contient une instruction adressée à "
                    + "l'assistant (« " + injection + " ») : possible tentative de manipulation."));
        }

        Set<String> references = references(body);
        references.removeAll(references(received));                 // une référence donnée par l'expéditeur peut être reprise
        if (!references.isEmpty() && unknownSender(email.from())) {
            findings.add(new Finding(Severity.BLOCK, "DIVULGATION", "La réponse cite une référence interne ("
                    + String.join(", ", references) + ") à un expéditeur inconnu."));
        }

        String forbidden = TextChecks.firstTerm(body, FORBIDDEN);
        if (forbidden != null) {
            findings.add(new Finding(Severity.REWRITE, "FORMULE", "La réponse écrit « " + forbidden
                    + " » : elle affirme une pièce jointe ou un accord qui n'existent pas."));
        }

        String expected = TextChecks.language(received);
        if (!TextChecks.language(body).equals(expected)) {
            findings.add(new Finding(Severity.REWRITE, "LANGUE", "La réponse n'est pas dans la langue de l'e-mail ("
                    + (expected.equals("en") ? "anglais" : "français") + ")."));
        }

        String commitment = TextChecks.firstTerm(body, COMMITMENTS);
        if (commitment != null) {
            findings.add(new Finding(Severity.WARNING, "ENGAGEMENT", "La réponse engage l'utilisateur (« "
                    + commitment + " ») : à confirmer avant l'envoi."));
        }

        Set<String> unsupported = TextChecks.datesAndTimes(body);
        unsupported.removeAll(TextChecks.datesAndTimes(received + "\n" + sources(email, draft.agentId())));
        if (!unsupported.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "DATE", "Date ou heure absente de l'e-mail et du contexte : "
                    + String.join(", ", unsupported) + "."));
        }
        return findings;
    }

    /** Remarque transmise à l'agent rédacteur pour la réécriture automatique. */
    public static String rewriteInstruction(Email email, List<Finding> findings) {
        List<String> lines = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity() != Severity.REWRITE) {
                continue;
            }
            switch (f.code()) {
                case "FORMULE" -> lines.add("N'écris ni « ci-joint », ni « en pièce jointe », ni « comme convenu » : "
                        + "aucun document n'est joint et aucun accord n'existe. Écris plutôt ce que l'utilisateur fera.");
                case "LANGUE" -> lines.add(TextChecks.language(email.subject() + "\n" + email.body()).equals("en")
                        ? "Write the whole reply in English: the e-mail is in English."
                        : "Rédige toute la réponse en français : l'e-mail est en français.");
                default -> lines.add(f.message());
            }
        }
        return "Relecture automatique : " + String.join(" ", lines);
    }

    // ------------------------------------------------------------------ interne

    private static Set<String> references(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = REFERENCE.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Inconnu : ni dans l'annuaire, ni couvert par un motif d'adresse d'un agent (ex. *@univ.fr). */
    private boolean unknownSender(String from) {
        String address = TextMatching.emailAddress(from);
        if (contacts.findByEmail(address).isPresent()) {
            return false;
        }
        return agents.findAll().stream()
                .flatMap(p -> p.routingHints().senderPatterns().stream())
                .noneMatch(glob -> TextMatching.matchesGlob(address, glob));
    }

    /** Tout le contexte de l'agent rédacteur : ce que la réponse a le droit de citer. */
    private String sources(Email email, String agentId) {
        Optional<AgentProfile> agent = agentId == null ? Optional.empty() : agents.findById(agentId);
        if (agent.isEmpty()) {
            return "";
        }
        try {
            return context.retrieve(new ContextQuery(email, agent.get(), Integer.MAX_VALUE / 4)).stream()
                    .map(ContextItem::content).collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "";                                               // pas de contexte : rien n'est « sourcé »
        }
    }
}