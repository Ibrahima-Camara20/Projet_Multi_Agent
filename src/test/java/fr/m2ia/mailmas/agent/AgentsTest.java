package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.StubLLMClient;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.routing.RoutingDecision;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import fr.m2ia.mailmas.support.TestSupport.StubDraftGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static fr.m2ia.mailmas.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests du MessageBus et de l'agent spécialisé générique. */
class AgentsTest {

    private final Email email = email("e1", "lea@etu.univ.fr", "Rendu du TP", "Bonjour");
    private final FakeDraftGenerator generator = new FakeDraftGenerator();

    private AgentMessage request(String from, String to, int attempt) {
        return AgentMessage.of(Performative.REQUEST, from, to, email.id(),
                new Payload.DraftRequest(email, List.of(), attempt));
    }

    // ------------------------------------------------------------ MessageBus

    @Test
    void busDeliversAndNotifiesListenersOfRequestAndReply() {
        var bus = new SimpleMessageBus();
        bus.register(new SpecializedAgent(keywords("student", "TP"), generator));
        List<AgentMessage> log = new ArrayList<>();
        bus.addListener(log::add);

        AgentMessage reply = bus.send(request(Participants.ROUTER, "student", 1));

        assertEquals(Performative.PROPOSE, reply.performative());
        assertEquals(2, log.size());
        assertEquals(Performative.REQUEST, log.get(0).performative());
        assertEquals(reply, log.get(1));
        assertEquals(log.get(0).conversationId(), reply.conversationId());
    }

    @Test
    void busTurnsUnknownReceiverAndAgentCrashIntoFailure() {
        var bus = new SimpleMessageBus();
        AgentMessage unknown = bus.send(request(Participants.ROUTER, "nobody", 1));
        assertEquals(Performative.FAILURE, unknown.performative());
        assertTrue(((Payload.Failure) unknown.payload()).reason().contains("nobody"));

        bus.register(new Agent() {
            @Override
            public AgentProfile profile() {
                return keywords("buggy");
            }

            @Override
            public AgentMessage handle(AgentMessage m) {
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(Performative.FAILURE, bus.send(request(Participants.ROUTER, "buggy", 1)).performative());
    }

    @Test
    void buggyListenerDoesNotBreakTheExchange() {
        var bus = new SimpleMessageBus();
        bus.register(new SpecializedAgent(keywords("student"), generator));
        bus.addListener(m -> {
            throw new RuntimeException("listener cassé");
        });
        assertEquals(Performative.PROPOSE, bus.send(request(Participants.USER, "student", 1)).performative());
    }

    @Test
    void registerReplacesAndUnregisterRemoves() {
        var bus = new SimpleMessageBus();
        bus.register(new SpecializedAgent(keywords("a"), generator));
        bus.register(new SpecializedAgent(keywords("b"), generator));
        bus.register(new SpecializedAgent(keywords("a", "nouveau"), generator));
        assertEquals(List.of("a", "b"), bus.registeredAgents().stream().map(AgentProfile::id).toList());
        assertTrue(bus.registeredAgents().iterator().next().routingHints().keywords().contains("nouveau"));
        bus.unregister("a");
        assertEquals(1, bus.registeredAgents().size());
    }

    // ------------------------------------------------------------ SpecializedAgent

    @Test
    void agentDelegatesToGeneratorWithFeedbackAndAttempt() {
        var agent = new SpecializedAgent(keywords("student"), generator);
        var fb = List.of(UserFeedback.reject("trop long"));
        AgentMessage reply = agent.handle(AgentMessage.of(Performative.REQUEST, Participants.USER, "student",
                "c1", new Payload.DraftRequest(email, fb, 2)));

        assertEquals(Performative.PROPOSE, reply.performative());
        assertEquals(Participants.USER, reply.receiver());
        assertEquals("student", reply.sender());
        assertEquals(2, generator.last().attempt());
        assertEquals("trop long", generator.last().feedback().get(0).comment());
    }

    @Test
    void agentRefusesUnsupportedMessages() {
        var agent = new SpecializedAgent(keywords("student"), generator);
        AgentMessage reply = agent.handle(AgentMessage.of(Performative.REQUEST, "x", "student", null,
                new Payload.Refusal("?")));
        assertEquals(Performative.REFUSE, reply.performative());
        assertTrue(generator.calls.isEmpty());
    }

    @Test
    void agentReportsLlmErrorsAsFailure() {
        generator.failure = new LLMException("Ollama injoignable");
        AgentMessage reply = new SpecializedAgent(keywords("student"), generator)
                .handle(request(Participants.ROUTER, "student", 1));
        assertEquals(Performative.FAILURE, reply.performative());
        assertTrue(((Payload.Failure) reply.payload()).reason().contains("Ollama"));
    }

    @Test
    void agentRefusesBeyondMaxProposals() {
        var agent = new SpecializedAgent(keywords("student"), generator, null, 0, 3);
        assertEquals(Performative.PROPOSE, agent.handle(request(Participants.USER, "student", 3)).performative());
        assertEquals(Performative.REFUSE, agent.handle(request(Participants.USER, "student", 4)).performative());
    }

    @Test
    void agentRefusesOutOfScopeDelegationFromRouterOnly() {
        // l'agent « admin » s'auto-évalue à 0 pour cet e-mail
        var admin = new SpecializedAgent(keywords("admin", "inscription", "attestation"), generator,
                selfScore(0.0), 0.1, 5);

        AgentMessage fromRouter = admin.handle(request(Participants.ROUTER, "admin", 1));
        assertEquals(Performative.REFUSE, fromRouter.performative());
        assertTrue(((Payload.Refusal) fromRouter.payload()).reason().contains("hors de mon domaine"));

        AgentMessage fromUser = admin.handle(request(Participants.USER, "admin", 1));
        assertEquals(Performative.PROPOSE, fromUser.performative(), "l'utilisateur a choisi cet agent");
    }

    @Test
    void agentAcceptsInScopeDelegationAndFallbackNeverRefuses() {
        var student = new SpecializedAgent(keywords("student", "TP"), generator, selfScore(0.8), 0.1, 5);
        assertEquals(Performative.PROPOSE, student.handle(request(Participants.ROUTER, "student", 1)).performative());

        var generic = new SpecializedAgent(fallback("generic"), generator, selfScore(0.0), 0.99, 5);
        assertEquals(Performative.PROPOSE, generic.handle(request(Participants.ROUTER, "generic", 1)).performative());
    }

    @Test
    void agentWorksWithStubLlmClient() {
        // SpecializedAgent avec StubLLMClient, comme prévu dans le plan (étape 2)
        var agent = new SpecializedAgent(keywords("student"), new StubDraftGenerator(new StubLLMClient()));
        AgentMessage reply = agent.handle(request(Participants.ROUTER, "student", 1));
        assertEquals(Performative.PROPOSE, reply.performative());
        var draft = ((Payload.DraftProposal) reply.payload()).draft();
        assertTrue(draft.body().startsWith("[STUB]"));
        assertEquals("student", draft.agentId());
        assertEquals("e1", draft.emailId());
    }

    /** Stratégie d'auto-évaluation qui donne toujours le même score. */
    private static RoutingStrategy selfScore(double score) {
        return new RoutingStrategy() {
            @Override
            public String name() {
                return "fixe";
            }

            @Override
            public RoutingDecision route(Email e, Collection<AgentProfile> candidates) {
                return new RoutingDecision("fixe", candidates.stream()
                        .map(p -> new RoutingDecision.Candidate(p.id(), score, "fixe")).toList());
            }
        };
    }
}
