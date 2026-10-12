package fr.m2ia.mailmas.generation;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.ContextQuery;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.LLMRequest;
import fr.m2ia.mailmas.llm.LLMResponse;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDraftGeneratorTest {

    /** Faux LLM : mémorise la dernière requête et renvoie une réponse fixe. */
    static class FakeLLM implements LLMClient {
        LLMRequest last;
        String answer = "Bonjour Anna,\n\nLa date limite est le 15 novembre 2026.\n\nCordialement,\n[Signature]";

        @Override
        public LLMResponse generate(LLMRequest request) {
            last = request;
            return new LLMResponse(answer, "fake", -1, -1);
        }

        @Override
        public String modelName() {
            return "fake";
        }

        String system() {
            return last.messages().get(0).content();
        }

        String user() {
            return last.messages().get(1).content();
        }
    }

    private final FakeLLM llm = new FakeLLM();
    private final InMemoryConversationMemory memory = new InMemoryConversationMemory();

    private final ContextProvider context = new ContextProvider() {
        public boolean supports(String s) {
            return true;
        }

        public List<ContextItem> retrieve(ContextQuery q) {
            return List.of(
                    new ContextItem("echeances.md", "D3.1 : date limite le 15 novembre 2026.", 0.9),
                    new ContextItem("gros.md", "x".repeat(8000), 0.1)); // trop gros pour le budget
        }
    };

    private final AgentProfile agent = new AgentProfile("research-supermas", "SuperMASProject", null,
            "Ne t'engage jamais sans validation.", List.of(), null, false);

    private final Email email = new Email("001", "wp3", "anna@partner-lab.eu", List.of(),
            "SuperMAS - WP3 deliverable", "Could you confirm the date for the WP3 deliverable?", null, null);

    private DefaultDraftGenerator generator() {
        return new DefaultDraftGenerator(llm, context, memory, new PromptBuilder(), 1500, 6);
    }

    @Test
    void systemPromptCombinesCommonRulesAndAgentInstructions() throws Exception {
        generator().generate(email, agent, List.of(), 1);
        assertTrue(llm.system().contains("N'invente aucune information"));
        assertTrue(llm.system().contains("Ne t'engage jamais sans validation."));
        assertTrue(llm.system().contains("SuperMASProject"));
    }

    @Test
    void userPromptContainsContextWithinBudgetAndTheEmail() throws Exception {
        generator().generate(email, agent, List.of(), 1);
        assertTrue(llm.user().contains("15 novembre 2026"));
        assertFalse(llm.user().contains("xxxxxxxxxx"), "le contexte trop gros doit être écarté");
        assertTrue(llm.user().contains("Could you confirm the date"));
        assertTrue(llm.user().contains("anna@partner-lab.eu"));
    }

    @Test
    void historyShowsPreviousMessagesButNotTheCurrentEmailTwice() throws Exception {
        var earlier = new Email("000", "wp3", "anna@partner-lab.eu", List.of(), "WP3", "Kick-off WP3 lundi ?", null,
                null);
        memory.recordReceived(earlier);
        memory.recordSent("wp3",
                new DraftReply("d0", "000", "research-supermas", "Re: WP3", "Oui, lundi 10h.", 1, null));
        memory.recordReceived(email); // AssistantService enregistre l'e-mail avant de générer

        generator().generate(email, agent, List.of(), 1);
        String user = llm.user();
        assertTrue(user.contains("Kick-off WP3 lundi ?"));
        assertTrue(user.contains("Oui, lundi 10h."));
        assertEquals(1, user.split("Could you confirm the date", -1).length - 1);
    }

    @Test
    void revisionIncludesRejectedDraftAndUserComments() throws Exception {
        memory.recordReceived(email);
        memory.recordDraft("wp3",
                new DraftReply("d1", "001", "research-supermas", "Re", "Version trop longue...", 1, null));

        generator().generate(email, agent, List.of(UserFeedback.reject("Trop long, et réponds en anglais")), 2);
        assertTrue(llm.user().contains("Version trop longue..."));
        assertTrue(llm.user().contains("Trop long, et réponds en anglais"));
        assertTrue(llm.last.options().temperature() > PromptBuilder.BASE_TEMPERATURE);
    }

    @Test
    void firstAttemptDoesNotMentionRevision() throws Exception {
        generator().generate(email, agent, null, 1);
        assertFalse(llm.user().contains("Remarques de l'utilisateur"));
        assertEquals(PromptBuilder.BASE_TEMPERATURE, llm.last.options().temperature());
    }

    @Test
    void buildsTheDraftReply() throws Exception {
        DraftReply d = generator().generate(email, agent, List.of(), 1);
        assertEquals("001", d.emailId());
        assertEquals("research-supermas", d.agentId());
        assertEquals("Re: SuperMAS - WP3 deliverable", d.subject());
        assertEquals(1, d.attempt());
        assertTrue(d.body().startsWith("Bonjour Anna"));
    }

    @Test
    void cleansWhatTheModelAddsDespiteTheRules() {
        assertEquals("Bonjour", DefaultDraftGenerator.clean("```text\nBonjour\n```"));
        assertEquals("Bonjour", DefaultDraftGenerator.clean("Objet : Re: TP\n\nBonjour"));
        assertEquals("Re: TP", DefaultDraftGenerator.replySubject("Re: TP"));
        assertEquals("Re: TP", DefaultDraftGenerator.replySubject("TP"));
    }

    @Test
    void emptyAnswerIsAnError() {
        llm.answer = "   ";
        assertThrows(LLMException.class, () -> generator().generate(email, agent, List.of(), 1));
    }

    @Test
    void contextErrorBecomesLLMException() {
        ContextProvider broken = new ContextProvider() {
            public boolean supports(String s) {
                return true;
            }

            public List<ContextItem> retrieve(ContextQuery q) {
                throw new IllegalArgumentException("source interdite");
            }
        };
        var gen = new DefaultDraftGenerator(llm, broken, memory, new PromptBuilder(), 1500, 6);
        var e = assertThrows(LLMException.class, () -> gen.generate(email, agent, List.of(), 1));
        assertTrue(e.getMessage().contains("source interdite"));
    }
}