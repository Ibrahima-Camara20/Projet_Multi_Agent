package fr.m2ia.mailmas.support;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.RoutingHints;
import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.persistence.AgentRepository;
import fr.m2ia.mailmas.persistence.ContactDirectory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Doublures de test pour les composants de la personne B (aucune dépendance au vrai LLM). */
public final class TestSupport {

    private TestSupport() {
    }

    public static Email email(String id, String from, String subject, String body) {
        return new Email(id, null, from, List.of("moi@univ.fr"), subject, body, null, null);
    }

    public static Email email(String id, String thread, String from, String subject, String body) {
        return new Email(id, thread, from, List.of("moi@univ.fr"), subject, body, null, null);
    }

    public static AgentProfile profile(String id, Set<String> patterns, Set<String> roles, Set<String> keywords) {
        return new AgentProfile(id, id, "agent " + id, "prompt " + id, List.of(),
                new RoutingHints(patterns, roles, keywords), false);
    }

    public static AgentProfile keywords(String id, String... kws) {
        return profile(id, Set.of(), Set.of(), Set.of(kws));
    }

    public static AgentProfile fallback(String id) {
        return new AgentProfile(id, id, "repli", "prompt", List.of(), RoutingHints.none(), true);
    }

    /** Générateur factice : enregistre chaque appel et renvoie une proposition prévisible. */
    public static final class FakeDraftGenerator implements DraftGenerator {

        public record Call(String emailId, String agentId, List<UserFeedback> feedback, int attempt) {
        }

        public final List<Call> calls = new ArrayList<>();
        public LLMException failure; // si non null, chaque appel lève cette exception

        @Override
        public DraftReply generate(Email email, AgentProfile agent, List<UserFeedback> previousFeedback,
                int attempt) throws LLMException {
            calls.add(new Call(email.id(), agent.id(), List.copyOf(previousFeedback), attempt));
            if (failure != null)
                throw failure;
            return new DraftReply("d-" + email.id() + "-" + attempt, email.id(), agent.id(),
                    "Re: " + email.subject(), "[" + agent.id() + "] proposition " + attempt, attempt, null);
        }

        public Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    /**
     * Doublure de test minimale de {@code DraftGenerator} branchée sur un {@link LLMClient}
     * (ex. {@code StubLLMClient}) : system prompt du profil + e-mail, sans contexte ni
     * historique.
     */
    public static final class StubDraftGenerator implements DraftGenerator {
        private final LLMClient llm;

        public StubDraftGenerator(LLMClient llm) {
            this.llm = llm;
        }

        @Override
        public DraftReply generate(Email email, AgentProfile agent, List<UserFeedback> previousFeedback,
                int attempt) throws LLMException {
            String text = llm.generate(agent.systemPrompt(), "", email.subject() + "\n\n" + email.body());
            return new DraftReply("d-" + email.id() + "-" + attempt, email.id(), agent.id(),
                    "Re: " + email.subject(), text, attempt, null);
        }
    }

    public static final class InMemoryAgentRepository implements AgentRepository {
        private final Map<String, AgentProfile> profiles = new java.util.LinkedHashMap<>();

        public InMemoryAgentRepository(List<AgentProfile> initial) {
            initial.forEach(this::save);
        }

        @Override
        public List<AgentProfile> findAll() {
            return List.copyOf(profiles.values());
        }

        @Override
        public Optional<AgentProfile> findById(String id) {
            return Optional.ofNullable(profiles.get(id));
        }

        @Override
        public void save(AgentProfile profile) {
            profiles.put(profile.id(), profile);
        }

        @Override
        public void delete(String id) {
            profiles.remove(id);
        }
    }

    public static final class MapContactDirectory implements ContactDirectory {
        private final Map<String, Contact> contacts = new HashMap<>();

        public MapContactDirectory add(String email, String name, String... roles) {
            contacts.put(email.toLowerCase(), new Contact(email, name, Set.of(roles)));
            return this;
        }

        @Override
        public Optional<Contact> findByEmail(String email) {
            return Optional.ofNullable(contacts.get(email == null ? null : email.toLowerCase()));
        }
    }
}
