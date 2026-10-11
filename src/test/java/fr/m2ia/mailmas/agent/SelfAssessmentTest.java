package fr.m2ia.mailmas.agent;

import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingStrategiesTestAccess;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.m2ia.mailmas.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Auto-évaluation des agents créés par DefaultAgentFactory (règles ou LLM). */
class SelfAssessmentTest {

    private final Email email = email("e1", "lea@etu.univ.fr", "Rendu du TP", "Bonjour");
    private final FakeDraftGenerator generator = new FakeDraftGenerator();

    private AgentMessage request(String from, String to, int attempt) {
        return AgentMessage.of(Performative.REQUEST, from, to, email.id(),
                new Payload.DraftRequest(email, List.of(), attempt));
    }

    @Test
    void keywordSelfAssessmentRefusesOutOfScopeFromRouter() {
        var factory = DefaultAgentFactory.withKeywordSelfAssessment(generator, new InMemoryConversationMemory());
        Agent admin = factory.create(keywords("admin", "inscription", "attestation"));
        Agent student = factory.create(keywords("student", "TP"));
        assertEquals(Performative.REFUSE, admin.handle(request(Participants.ROUTER, "admin", 1)).performative());
        assertEquals(Performative.PROPOSE, admin.handle(request(Participants.USER, "admin", 1)).performative());
        assertEquals(Performative.PROPOSE, student.handle(request(Participants.ROUTER, "student", 1)).performative());
    }

    @Test
    void factoryWithSelfAssessmentAcceptsFollowUpInKnownThread() {
        var memory = new InMemoryConversationMemory();
        var factory = DefaultAgentFactory.withKeywordSelfAssessment(generator, memory);
        Agent teacher = factory.create(keywords("teacher", "jury"));
        Email followUp = email("e2", "jury-s1", "paul@univ.fr", "Re: ok", "Parfait, merci !");
        AgentMessage req = AgentMessage.of(Performative.REQUEST, Participants.ROUTER, "teacher", null,
                new Payload.DraftRequest(followUp, List.of(), 1));

        assertEquals(Performative.REFUSE, teacher.handle(req).performative(), "aucun indice : refus");

        memory.recordDraft("jury-s1", new DraftReply("d1", "e1", "teacher", "Re", "x", 1, null));
        assertEquals(Performative.PROPOSE, teacher.handle(req).performative(), "l'agent connaît ce fil");
    }

    @Test
    void llmSelfAssessmentRefusesOnlyWhenModelSaysNone() {
        var answer = new String[] { "aucun" };
        var llm = RoutingStrategiesTestAccess.llm(r -> answer[0]);
        Agent teacher = DefaultAgentFactory.withLlmSelfAssessment(generator, llm).create(keywords("teacher", "jury"));

        assertEquals(Performative.REFUSE, teacher.handle(request(Participants.ROUTER, "teacher", 1)).performative());
        answer[0] = "teacher";
        assertEquals(Performative.PROPOSE, teacher.handle(request(Participants.ROUTER, "teacher", 1)).performative());
        answer[0] = "[STUB] réponse sans identifiant";
        assertEquals(Performative.PROPOSE, teacher.handle(request(Participants.ROUTER, "teacher", 1)).performative(),
                "pas d'avis du LLM : l'agent accepte");
    }
}
