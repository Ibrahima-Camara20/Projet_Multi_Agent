package fr.m2ia.mailmas.routing;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.LLMRequest;
import fr.m2ia.mailmas.llm.LLMResponse;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.support.TestSupport.MapContactDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static fr.m2ia.mailmas.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests unitaires des stratégies de routing. */
class RoutingStrategiesTest {

    private static Map<String, Double> scores(RoutingDecision d) {
        return d.ranking().stream().collect(Collectors.toMap(RoutingDecision.Candidate::agentId,
                RoutingDecision.Candidate::score));
    }

    // ------------------------------------------------------------ TextMatching

    @Test
    void normalizeRemovesAccentsCaseAndExtraSpaces() {
        assertEquals("reunion du jury", TextMatching.normalize("  Réunion   du\nJURY "));
    }

    @Test
    void termsMatchWholeWordsOnly() {
        String t = TextMatching.normalize("Rendu du TP3 (WP2) et CAPTP");
        assertTrue(TextMatching.containsTerm(t, "TP"), "TP3 contient le mot TP");
        assertTrue(TextMatching.containsTerm(t, "wp"));
        assertFalse(TextMatching.containsTerm(TextMatching.normalize("les ATP"), "TP"));
        assertTrue(TextMatching.containsTerm(TextMatching.normalize("le Work  Package 3"), "work package"));
        assertTrue(TextMatching.containsTerm(TextMatching.normalize("Ordre de mission"), "ordre de mission"));
        assertFalse(TextMatching.containsTerm(t, ""));
    }

    @Test
    void globPatternsOnAddresses() {
        assertTrue(TextMatching.matchesGlob("Anna.Muller@Partner-Lab.eu", "*@partner-lab.eu"));
        assertFalse(TextMatching.matchesGlob("anna@partner-lab.eu.evil.com", "*@partner-lab.eu"));
        assertFalse(TextMatching.matchesGlob("x@etu.univ.fr", "*@univ.fr"));
        assertTrue(TextMatching.matchesGlob("scolarite@univ.fr", "scolarite@univ.fr"));
        assertEquals("anna@lab.eu", TextMatching.emailAddress("Anna Müller <Anna@Lab.eu>"));
    }

    // ------------------------------------------------------------ Keyword

    @Test
    void keywordScoresSaturateAndFavourSubject() {
        var student = keywords("student", "TP", "examen", "rendu");
        var research = keywords("research", "SuperMAS");
        var generic = fallback("generic");
        Email e = email("1", "x@y.fr", "Rendu du TP", "Quand a lieu l'examen ?");

        RoutingDecision d = new KeywordRoutingStrategy().route(e, List.of(student, research, generic));

        assertEquals("student", d.best().orElseThrow().agentId());
        assertEquals(2, d.ranking().size(), "l'agent de repli n'est pas classé");
        double s = scores(d).get("student");
        assertTrue(s > 0.8 && s <= 1, "3 mots dont 2 dans l'objet : score élevé, " + s);
        assertEquals(0.0, scores(d).get("research"), 1e-9);
        assertTrue(d.best().get().reason().contains("TP (objet)"));
    }

    @Test
    void keywordMatchingIgnoresAccentsAndCase() {
        var admin = keywords("admin", "réunion", "scolarité");
        Email e = email("1", "x@y.fr", "REUNION", "Le service de scolarite");
        double s = scores(new KeywordRoutingStrategy().route(e, List.of(admin))).get("admin");
        assertTrue(s > 0.7, "les deux mots sont trouvés malgré accents et majuscules : " + s);
    }

    @Test
    void manyKeywordsInProfileAreNotPenalised() {
        var small = keywords("small", "jury");
        var big = keywords("big", "jury", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9");
        Map<String, Double> s = scores(new KeywordRoutingStrategy()
                .route(email("1", "x@y.fr", "", "le jury"), List.of(small, big)));
        assertEquals(s.get("small"), s.get("big"), 1e-9);
    }

    // ------------------------------------------------------------ Sender

    @Test
    void senderUsesPatternsAndKnownRoles() {
        var research = profile("research", Set.of("*@partner-lab.eu"), Set.of("research-partner"), Set.of());
        var teacher = profile("teacher", Set.of("*@univ.fr"), Set.of("teacher"), Set.of());
        var contacts = new MapContactDirectory()
                .add("anna@partner-lab.eu", "Anna", "research-partner")
                .add("paul@gmail.com", "Paul", "teacher");
        var strategy = new SenderRoutingStrategy(contacts);

        Map<String, Double> anna = scores(strategy.route(email("1", "Anna <anna@partner-lab.eu>", "", ""),
                List.of(research, teacher)));
        assertEquals(1 - 0.3 * 0.2, anna.get("research"), 1e-9, "motif ET rôle se renforcent");
        assertEquals(0.0, anna.get("teacher"), 1e-9);

        Map<String, Double> paul = scores(strategy.route(email("2", "paul@gmail.com", "", ""),
                List.of(research, teacher)));
        assertEquals(0.8, paul.get("teacher"), 1e-9, "le rôle suffit même avec une adresse personnelle");
    }

    @Test
    void senderWorksWithoutDirectory() {
        var teacher = profile("teacher", Set.of("*@univ.fr"), Set.of("teacher"), Set.of());
        var d = new SenderRoutingStrategy(null).route(email("1", "paul@univ.fr", "", ""), List.of(teacher));
        assertEquals(0.7, d.best().orElseThrow().score(), 1e-9);
    }

    // ------------------------------------------------------------ Thread affinity

    @Test
    void threadAffinityFavoursLastAgentOfTheThread() {
        var memory = new InMemoryConversationMemory();
        memory.recordDraft("t1", new DraftReply("d1", "e1", "student", "Re", "a", 1, null));
        memory.recordDraft("t1", new DraftReply("d2", "e1", "teacher", "Re", "b", 2, null));
        var strategy = new ThreadAffinityStrategy(memory, 6);
        var profiles = List.of(keywords("student"), keywords("teacher"));

        Map<String, Double> inThread = scores(strategy.route(email("e2", "t1", "x@y.fr", "Merci", "Merci !"), profiles));
        assertEquals(0.6, inThread.get("teacher"), 1e-9);
        assertEquals(0.0, inThread.get("student"), 1e-9);

        Map<String, Double> otherThread = scores(strategy.route(email("e3", "t2", "x@y.fr", "", ""), profiles));
        assertEquals(0.0, otherThread.get("teacher"), 1e-9);
    }

    // ------------------------------------------------------------ Metadata

    @Test
    void metadataLabelEqualToAgentIdIsStrongSignal() {
        Email e = new Email("1", null, "noreply@x.fr", null, "Notification", "", null, Map.of("label", "Student"));
        Map<String, Double> s = scores(new MetadataRoutingStrategy()
                .route(e, List.of(keywords("student"), keywords("teacher"), fallback("generic"))));
        assertEquals(0.9, s.get("student"), 1e-9);
        assertEquals(0.0, s.get("teacher"), 1e-9);
        assertEquals(2, s.size(), "l'agent de repli n'est pas classé");
    }

    @Test
    void metadataValuesAreSearchedForProfileKeywords() {
        Email e = new Email("1", null, "noreply@lists.org", null, "Weekly digest", "3 new messages", null,
                Map.of("list-id", "supermas-wp3.partner-lab.eu"));
        RoutingDecision d = new MetadataRoutingStrategy()
                .route(e, List.of(keywords("research", "SuperMAS"), keywords("student", "TP")));
        assertEquals("research", d.best().orElseThrow().agentId());
        assertEquals(0.5, d.best().get().score(), 1e-9);
        assertTrue(d.best().get().reason().contains("list-id"));
    }

    @Test
    void metadataWithoutUsefulValueGivesZero() {
        Email e = email("1", "x@y.fr", "s", "b");
        assertEquals(0.0, new MetadataRoutingStrategy().route(e, List.of(keywords("student", "TP")))
                .best().orElseThrow().score(), 1e-9);
    }

    // ------------------------------------------------------------ Weighted

    @Test
    void weightedIsProbabilisticOrOfWeightedScores() {
        var a = keywords("a");
        var b = keywords("b");
        RoutingStrategy s1 = fixed("s1", Map.of("a", 0.5, "b", 0.0));
        RoutingStrategy s2 = fixed("s2", Map.of("a", 0.5, "b", 0.8));
        var w = WeightedRoutingStrategy.builder().add(s1, 1.0).add(s2, 0.5).build();

        Map<String, Double> s = scores(w.route(email("1", "x", "", ""), List.of(a, b)));
        assertEquals(1 - (1 - 0.5) * (1 - 0.25), s.get("a"), 1e-9);
        assertEquals(0.4, s.get("b"), 1e-9, "un seul indice n'est pas dilué par la stratégie muette");
        assertTrue(w.name().startsWith("weighted("));
    }

    @Test
    void weightedKeepsReasonsOfEachContributingStrategy() {
        var a = keywords("a");
        var w = WeightedRoutingStrategy.builder()
                .add(fixed("s1", Map.of("a", 0.5)), 1.0)
                .add(fixed("s2", Map.of("a", 0.0)), 1.0).build();
        String reason = w.route(email("1", "x", "", ""), List.of(a)).best().orElseThrow().reason();
        assertTrue(reason.contains("s1"));
        assertFalse(reason.contains("s2"), "une stratégie qui n'a rien trouvé n'apparaît pas");
    }

    @Test
    void weightedRejectsInvalidWeights() {
        assertThrows(IllegalArgumentException.class,
                () -> WeightedRoutingStrategy.builder().add(new KeywordRoutingStrategy(), 1.5).build());
        assertThrows(IllegalArgumentException.class, () -> WeightedRoutingStrategy.builder().build());
    }

    // ------------------------------------------------------------ LLM

    @Test
    void llmStrategyParsesAgentIdFromAnswer() {
        var profiles = List.of(keywords("teacher"), keywords("research-supermas"), keywords("research"));
        var d = new LlmRoutingStrategy(llm(r -> "Agent : research-supermas.")).route(email("1", "x", "s", "b"), profiles);
        assertEquals("research-supermas", d.best().orElseThrow().agentId());
        assertEquals(0.9, d.best().get().score(), 1e-9);
    }

    @Test
    void llmStrategyAnswerNoneGivesZeroToAll() {
        var profiles = List.of(keywords("teacher"), keywords("student"));
        var none = new LlmRoutingStrategy(llm(r -> "Aucun.")).route(email("1", "x", "", ""), profiles);
        assertEquals(2, none.ranking().size());
        assertEquals(0.0, none.best().orElseThrow().score(), 1e-9);
    }

    @Test
    void llmStrategyHasNoOpinionWhenUnavailableOrUnclear() {
        var profiles = List.of(keywords("teacher"), keywords("student"));
        var unclear = new LlmRoutingStrategy(llm(r -> "je ne sais pas")).route(email("1", "x", "", ""), profiles);
        assertTrue(unclear.ranking().isEmpty());

        var down = new LlmRoutingStrategy(llm(r -> {
            throw new IllegalStateException("connexion refusée");
        })).route(email("1", "x", "", ""), profiles);
        assertTrue(down.ranking().isEmpty());
    }

    // ------------------------------------------------------------ helpers

    static RoutingStrategy fixed(String name, Map<String, Double> scores) {
        return new RoutingStrategy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public RoutingDecision route(Email email, java.util.Collection<AgentProfile> candidates) {
                return new RoutingDecision(name, candidates.stream()
                        .filter(p -> !p.fallback())
                        .map(p -> new RoutingDecision.Candidate(p.id(), scores.getOrDefault(p.id(), 0.0),
                                name + " pour " + p.id()))
                        .toList());
            }
        };
    }

    static LLMClient llm(Function<LLMRequest, String> answer) {
        return new LLMClient() {
            @Override
            public LLMResponse generate(LLMRequest request) throws LLMException {
                return new LLMResponse(answer.apply(request), "fake", -1, -1);
            }

            @Override
            public String modelName() {
                return "fake";
            }
        };
    }
}
