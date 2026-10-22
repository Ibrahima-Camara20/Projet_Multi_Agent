package fr.m2ia.mailmas;

import fr.m2ia.mailmas.app.ProcessingResult;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.llm.StubLLMClient;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de bout en bout de la VRAIE configuration (profils, contacts, contexte,
 * boîte de réception,
 * routing pondéré), avec un faux LLM : vérifie que toutes les pièces A et B
 * sont bien reliées.
 */
class MailAssistantTest {

    private static MailAssistant assistant() throws Exception {
        return MailAssistant.create(AppConfig.load(Path.of("config/app.properties")), new StubLLMClient(), false,
                new InMemoryConversationMemory()); // mémoire temporaire : rien n'est écrit sur disque
    }

    @Test
    void theSixInboxEmailsGoToTheExpectedAgents() throws Exception {
        MailAssistant a = assistant();
        Map<String, ProcessingResult> results = new HashMap<>();
        for (Email e : a.inbox().findAll()) {
            results.put(e.id(), a.service().submit(e));
        }

        assertEquals("research-supermas", results.get("001").selectedAgentId());
        assertEquals("student", results.get("002").selectedAgentId());
        assertEquals("teacher", results.get("003").selectedAgentId());
        assertEquals("administration", results.get("004").selectedAgentId());
        assertTrue(results.get("005").ambiguous(), "005 est volontairement ambigu");
        assertEquals("generic", results.get("006").selectedAgentId());
        results.values().forEach(r -> assertEquals(ProcessingResult.Status.PROPOSED, r.status()));
    }

    @Test
    void agentsCommunicateThroughTheBus() throws Exception {
        MailAssistant a = assistant();
        List<String> log = new ArrayList<>();
        a.bus().addListener(m -> log.add(m.performative() + " " + m.sender() + "->" + m.receiver()));

        a.service().submit(a.inbox().findById("002").orElseThrow());
        assertEquals(List.of("REQUEST router->student", "PROPOSE student->router"), log);
    }

    @Test
    void fullCycleSubmitReviseAccept() throws Exception {
        MailAssistant a = assistant();
        Email anna = a.inbox().findById("001").orElseThrow();

        ProcessingResult first = a.service().submit(anna);
        ProcessingResult second = a.service().revise(anna.id(), UserFeedback.reject("Plus court"));
        assertEquals(1, first.draft().attempt());
        assertEquals(2, second.draft().attempt());
        assertEquals("research-supermas", second.selectedAgentId());

        a.service().accept(anna.id(), UserFeedback.edit("Texte final validé"));
        var history = a.memory().recent(anna.threadId(), 10);
        assertEquals("Texte final validé", history.get(history.size() - 1).text());
    }
}