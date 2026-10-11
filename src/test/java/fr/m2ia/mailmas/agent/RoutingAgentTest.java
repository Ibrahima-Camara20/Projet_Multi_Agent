package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.m2ia.mailmas.routing.RoutingStrategiesTestAccess.fixed;
import static fr.m2ia.mailmas.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Coordination par le RoutingAgent : seuil, repli, ambiguïté, refus, échec. */
class RoutingAgentTest {

    private final FakeDraftGenerator generator = new FakeDraftGenerator();
    private final SimpleMessageBus bus = new SimpleMessageBus();
    private final RoutingAgent.Settings settings = new RoutingAgent.Settings(0.4, 0.1, 3);
    private final Email email = email("e1", "x@y.fr", "objet", "corps");

    private void register(AgentProfile... profiles) {
        for (AgentProfile p : profiles)
            bus.register(new SpecializedAgent(p, generator));
    }

    private RoutingAgent router(RoutingStrategy strategy) {
        RoutingAgent r = new RoutingAgent(bus, strategy, settings);
        bus.register(r);
        return r;
    }

    @Test
    void bestAgentAboveThresholdProduces() {
        register(keywords("student"), keywords("teacher"), fallback("generic"));
        var out = router(fixed("f", Map.of("student", 0.9, "teacher", 0.3))).process(email, List.of(), 1);

        assertEquals(RoutingOutcome.Status.PROPOSED, out.status());
        assertEquals("student", out.agentId());
        assertFalse(out.ambiguous());
        assertEquals("student", out.draft().agentId());
        assertEquals(1, generator.calls.size());
    }

    @Test
    void routerIsNotItsOwnCandidate() {
        register(keywords("student"));
        RoutingAgent r = router(fixed("f", Map.of()));
        assertEquals(List.of("student"), r.specialists().stream().map(AgentProfile::id).toList());
    }

    @Test
    void noSuitableAgentGoesToFallback() {
        register(keywords("student"), fallback("generic"));
        var out = router(fixed("f", Map.of("student", 0.2))).process(email, List.of(), 1);
        assertEquals("generic", out.agentId());
        assertTrue(out.proposed());
    }

    @Test
    void closeScoresAreFlaggedAmbiguousButBestIsKept() {
        register(keywords("student"), keywords("teacher"), fallback("generic"));
        var out = router(fixed("f", Map.of("student", 0.62, "teacher", 0.58))).process(email, List.of(), 1);
        assertTrue(out.ambiguous());
        assertEquals("student", out.agentId());
    }

    @Test
    void tieBreakerDecidesAmongTiedCandidatesOnly() {
        register(keywords("student"), keywords("teacher"), keywords("admin"), fallback("generic"));
        List<String> seen = new ArrayList<>();
        RoutingStrategy tie = new RoutingStrategy() {
            public String name() {
                return "llm";
            }

            public fr.m2ia.mailmas.routing.RoutingDecision route(Email e,
                    java.util.Collection<AgentProfile> c) {
                c.forEach(p -> seen.add(p.id()));
                return fixed("llm", Map.of("teacher", 0.9)).route(e, c);
            }
        };
        RoutingAgent r = new RoutingAgent(bus, fixed("f", Map.of("student", 0.62, "teacher", 0.58, "admin", 0.2)),
                tie, settings);
        bus.register(r);

        var out = r.process(email, List.of(), 1);
        assertEquals("teacher", out.agentId());
        assertEquals(List.of("student", "teacher"), seen.stream().sorted().toList());
        assertTrue(out.note().contains("llm"));
    }

    @Test
    void refusalPassesToNextCandidateThenFallback() {
        // « admin » est classé premier mais refuse (aucun de ses mots-clés dans l'e-mail)
        bus.register(new SpecializedAgent(keywords("admin", "inscription"), generator,
                new KeywordRoutingStrategy(), 0.1, 5));
        bus.register(new SpecializedAgent(keywords("teacher", "jury"), generator,
                new KeywordRoutingStrategy(), 0.1, 5));
        register(fallback("generic"));
        var out = router(fixed("f", Map.of("admin", 0.9, "teacher", 0.5))).process(email, List.of(), 1);

        assertEquals("generic", out.agentId());
        assertEquals(2, out.refusals().size());
        assertTrue(out.refusals().get(0).startsWith("admin"));
        assertTrue(out.explanation().contains("Refus"));
    }

    @Test
    void attemptsAreBounded() {
        for (String id : List.of("a", "b", "c", "d"))
            bus.register(new SpecializedAgent(keywords(id, "zzz"), generator, new KeywordRoutingStrategy(), 0.1, 5));
        var out = router(fixed("f", Map.of("a", 0.9, "b", 0.8, "c", 0.7, "d", 0.6))).process(email, List.of(), 1);
        assertEquals(RoutingOutcome.Status.REFUSED, out.status());
        assertEquals(3, out.refusals().size(), "maxAttempts = 3");
    }

    @Test
    void failureStopsImmediately() {
        generator.failure = new LLMException("timeout");
        register(keywords("student"), keywords("teacher"), fallback("generic"));
        var out = router(fixed("f", Map.of("student", 0.9, "teacher", 0.8))).process(email, List.of(), 1);
        assertEquals(RoutingOutcome.Status.FAILED, out.status());
        assertEquals(1, generator.calls.size(), "on n'essaie pas les autres agents si le LLM est en panne");
        assertTrue(out.explanation().contains("timeout"));
    }

    @Test
    void withoutFallbackBestAgentIsStillTried() {
        register(keywords("student"));
        var out = router(fixed("f", Map.of("student", 0.1))).process(email, List.of(), 1);
        assertEquals("student", out.agentId());
        assertTrue(out.proposed());
    }

    @Test
    void emptyOrganisationFailsCleanly() {
        var out = router(fixed("f", Map.of())).process(email, List.of(), 1);
        assertEquals(RoutingOutcome.Status.FAILED, out.status());
    }

    @Test
    void dispatchToSendsOnBehalfOfUser() {
        register(keywords("student"));
        List<AgentMessage> log = new ArrayList<>();
        bus.addListener(log::add);
        var out = router(fixed("f", Map.of())).dispatchTo("student", email, List.of(), 2, null);
        assertTrue(out.proposed());
        assertEquals(Participants.USER, log.get(0).sender());
        assertEquals(2, generator.last().attempt());
    }

    @Test
    void routerAnswersDraftRequestsOnTheBus() {
        register(keywords("student"), fallback("generic"));
        router(fixed("f", Map.of("student", 0.9)));
        AgentMessage reply = bus.send(AgentMessage.of(Performative.REQUEST, "upper-router", Participants.ROUTER,
                null, new Payload.DraftRequest(email, List.of(), 1)));
        assertEquals(Performative.PROPOSE, reply.performative());
        assertEquals("upper-router", reply.receiver());
    }
}
