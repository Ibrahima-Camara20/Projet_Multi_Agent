package fr.m2ia.mailmas;

import fr.m2ia.mailmas.agent.AgentMessage;
import fr.m2ia.mailmas.agent.Payload;
import fr.m2ia.mailmas.agent.Performative;
import fr.m2ia.mailmas.context.Context;
import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.llm.StubLLMClient;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;
import fr.m2ia.mailmas.routing.RoutingDecision.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Vérifie que les contrats communs se comportent comme convenu. */
class ContractsTest {

    private final Email email = new Email("e1", null, "etu@univ.fr", List.of("moi@univ.fr"),
            "Question TP", "Bonjour, quand est le rendu ?", null, null);

    @Test
    void emailWithoutThreadIsItsOwnThread() {
        assertEquals("e1", email.threadId());
    }

    @Test
    void routingDecisionDetectsAmbiguityAndFallback() {
        var d = new RoutingDecision("test", List.of(
                new Candidate("teacher", 0.55, "domaine univ"),
                new Candidate("student", 0.60, "mot-clé TP")));
        assertEquals("student", d.best().orElseThrow().agentId());
        assertTrue(d.isAmbiguous(0.1));
        assertTrue(d.noSuitableAgent(0.7));
    }

    @Test
    void replyKeepsConversationAndSwapsParticipants() {
        var req = AgentMessage.of(Performative.REQUEST, "router", "student", null,
                new Payload.DraftRequest(email, null, 1));
        var rep = req.reply(Performative.REFUSE, new Payload.Refusal("hors domaine"));
        assertEquals(req.conversationId(), rep.conversationId());
        assertEquals("router", rep.receiver());
    }

    @Test
    void contextRespectsTokenBudget() {
        var ctx = Context.withinBudget(List.of(
                new ContextItem("a", "x".repeat(400), 0.9),   // ~100 tokens
                new ContextItem("b", "y".repeat(400), 0.2)), 150);
        assertEquals(1, ctx.items().size());
        assertEquals("a", ctx.items().get(0).source());
    }

    @Test
    void stubLlmAnswers() throws Exception {
        assertTrue(new StubLLMClient().generate("sys", "", "Bonjour").startsWith("[STUB]"));
    }
}
