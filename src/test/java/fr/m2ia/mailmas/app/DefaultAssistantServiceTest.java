package fr.m2ia.mailmas.app;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.DefaultAgentFactory;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.SimpleMessageBus;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.SenderRoutingStrategy;
import fr.m2ia.mailmas.routing.ThreadAffinityStrategy;
import fr.m2ia.mailmas.routing.WeightedRoutingStrategy;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import fr.m2ia.mailmas.support.TestSupport.InMemoryAgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static fr.m2ia.mailmas.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cycle complet : submit → revise / reroute → accept, et rechargement des agents. */
class DefaultAssistantServiceTest {

    private FakeDraftGenerator generator;
    private InMemoryConversationMemory memory;
    private InMemoryAgentRepository repository;
    private SimpleMessageBus bus;
    private DefaultAssistantService service;

    private final Email tpQuestion = email("e1", "tp3", "lea@etu.univ.fr", "Rendu du TP3", "Le rapport est-il obligatoire ?");

    @BeforeEach
    void setUp() {
        generator = new FakeDraftGenerator();
        memory = new InMemoryConversationMemory();
        repository = new InMemoryAgentRepository(List.of(
                profile("student", Set.of("*@etu.univ.fr"), Set.of(), Set.of("TP", "rendu", "examen")),
                profile("teacher", Set.of("*@univ.fr"), Set.of(), Set.of("jury", "réunion")),
                fallback("generic")));
        bus = new SimpleMessageBus();
        var routing = WeightedRoutingStrategy.builder()
                .add(new KeywordRoutingStrategy(), 1.0)
                .add(new SenderRoutingStrategy(null), 0.9)
                .add(new ThreadAffinityStrategy(memory, 6), 0.7)
                .build();
        var router = new RoutingAgent(bus, routing, RoutingAgent.Settings.defaults());
        service = new DefaultAssistantService(bus, router, repository,
                DefaultAgentFactory.withKeywordSelfAssessment(generator, memory), memory);
    }

    @Test
    void submitRoutesAndProposesAndRecordsInMemory() {
        ProcessingResult r = service.submit(tpQuestion);

        assertEquals(ProcessingResult.Status.PROPOSED, r.status());
        assertEquals("student", r.selectedAgentId());
        assertEquals("student", r.routing().best().orElseThrow().agentId());
        assertEquals(1, r.draft().attempt());

        var history = memory.recent("tp3", 10);
        assertEquals(List.of(ConversationEntry.Kind.RECEIVED, ConversationEntry.Kind.DRAFT),
                history.stream().map(ConversationEntry::kind).toList());
    }

    @Test
    void reviseAsksSameAgentWithAllPreviousFeedback() {
        service.submit(tpQuestion);
        service.revise("e1", UserFeedback.reject("trop long"));
        ProcessingResult r = service.revise("e1", UserFeedback.reject("plus formel"));

        assertEquals("student", r.selectedAgentId());
        assertEquals(3, r.draft().attempt());
        var call = generator.last();
        assertEquals(3, call.attempt());
        assertEquals(List.of("trop long", "plus formel"), call.feedback().stream().map(UserFeedback::comment).toList());
        assertEquals("student", r.routing().best().orElseThrow().agentId(), "la décision initiale reste affichée");
        assertEquals(4, memory.recent("tp3", 10).size(), "1 reçu + 3 brouillons");
    }

    @Test
    void rerouteChangesAgentForFollowingRevisions() {
        service.submit(tpQuestion);
        ProcessingResult r = service.reroute("e1", "teacher");
        assertEquals("teacher", r.selectedAgentId());
        assertEquals(2, r.draft().attempt());
        assertFalse(r.ambiguous());

        service.revise("e1", UserFeedback.reject("plus court"));
        assertEquals("teacher", generator.last().agentId());
    }

    @Test
    void rerouteToUnknownAgentFailsWithoutLosingCurrentAgent() {
        service.submit(tpQuestion);
        ProcessingResult r = service.reroute("e1", "nobody");
        assertEquals(ProcessingResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("nobody"));
        assertEquals("student", service.assignedAgent("e1").orElseThrow());
    }

    @Test
    void acceptRecordsEditedTextAsSent() {
        service.submit(tpQuestion);
        service.accept("e1", UserFeedback.edit("Bonjour Léa, oui le rapport est obligatoire."));

        var last = memory.recent("tp3", 10).get(2);
        assertEquals(ConversationEntry.Kind.SENT, last.kind());
        assertEquals("Bonjour Léa, oui le rapport est obligatoire.", last.text());
        assertEquals(ProcessingResult.Status.FAILED, service.revise("e1", UserFeedback.reject("x")).status(),
                "plus de révision après validation");
        assertThrows(IllegalStateException.class, () -> service.accept("e1", UserFeedback.accept()));
    }

    @Test
    void acceptWithoutEditRecordsDraftAsIs() {
        service.submit(tpQuestion);
        service.accept("e1", UserFeedback.accept());
        assertEquals("[student] proposition 1", service.sentReply("e1").orElseThrow().body());
    }

    @Test
    void acceptRejectsInvalidCalls() {
        assertThrows(IllegalArgumentException.class, () -> service.accept("inconnu", UserFeedback.accept()));
        service.submit(tpQuestion);
        assertThrows(IllegalArgumentException.class, () -> service.accept("e1", UserFeedback.reject("non")));
    }

    @Test
    void llmFailureIsReportedAndCanBeRetried() {
        generator.failure = new LLMException("Ollama injoignable");
        ProcessingResult r = service.submit(tpQuestion);
        assertEquals(ProcessingResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("Ollama"));

        generator.failure = null;
        ProcessingResult retry = service.revise("e1", null);
        assertEquals(ProcessingResult.Status.PROPOSED, retry.status());
        assertEquals("student", retry.selectedAgentId());
    }

    @Test
    void unknownEmailInReviseGivesFailedResult() {
        assertEquals(ProcessingResult.Status.FAILED, service.revise("zzz", UserFeedback.reject("x")).status());
    }

    @Test
    void sameSenderDifferentContextIsCorrectedByRefusal() {
        // Un étudiant connu écrit pour un sujet sans rapport avec ses études :
        // le router choisit « student » d'après l'adresse, l'agent refuse, l'agent de repli répond.
        Email other = email("e9", "lea@etu.univ.fr", "Vente de mon vélo", "Bonjour, êtes-vous intéressée par mon vélo ?");
        ProcessingResult r = service.submit(other);
        assertEquals("generic", r.selectedAgentId());
        assertTrue(r.message().contains("student"), "le refus est expliqué : " + r.message());
    }

    @Test
    void followUpInThreadStaysWithSameAgent() {
        service.submit(tpQuestion);
        service.accept("e1", UserFeedback.accept());
        Email followUp = email("e2", "tp3", "lea@etu.univ.fr", "Re: merci", "Merci beaucoup !");
        assertEquals("student", service.submit(followUp).selectedAgentId());
    }

    @Test
    void reloadAgentsPicksUpNewProfileWithoutRestart() {
        repository.save(profile("research", Set.of(), Set.of(), Set.of("SuperMAS", "deliverable")));
        service.reloadAgents();
        var r = service.submit(email("e5", "anna@partner-lab.eu", "SuperMAS deliverable", "WP3"));
        assertEquals("research", r.selectedAgentId());
        assertTrue(bus.registeredAgents().stream().map(AgentProfile::id).anyMatch("router"::equals));
    }

    @Test
    void reloadAgentsRejectsTwoFallbacks() {
        repository.save(fallback("generic2"));
        assertThrows(IllegalStateException.class, service::reloadAgents);
    }
}
