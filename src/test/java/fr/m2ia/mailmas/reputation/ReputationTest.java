package fr.m2ia.mailmas.reputation;

import fr.m2ia.mailmas.MailAssistant;
import fr.m2ia.mailmas.app.FeedbackListener.Outcome;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.llm.StubLLMClient;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.UserFeedback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReputationTest {

    @TempDir
    Path dir;

    @Test
    void formulaOfTheCourse() {
        assertEquals(0.5, Reputation.NONE.score(), 1e-9, "sans donnée : pas d'avis");
        // exemple du cours : 9 positifs, 6 négatifs -> 10/17
        assertEquals(10.0 / 17, new Reputation(9, 6).score(), 1e-9);
        assertTrue(new Reputation(20, 0).score() > 0.95);
    }

    @Test
    void outcomesUpdateTheCounters() {
        ReputationStore store = new ReputationStore(null);
        store.onFeedback("student", Outcome.ACCEPTED);
        store.onFeedback("student", Outcome.ACCEPTED);
        store.onFeedback("student", Outcome.EDITED);
        store.onFeedback("student", Outcome.REJECTED);
        store.onFeedback("teacher", Outcome.REROUTED);

        assertEquals(new Reputation(2.5, 1.5), store.get("student"));
        assertEquals(new Reputation(0, 1), store.get("teacher"));
        assertEquals(Reputation.NONE, store.get("administration"));
    }

    @Test
    void reputationIsKeptOnDisk() {
        Path file = dir.resolve("data").resolve("reputation.json");
        new ReputationStore(file).onFeedback("student", Outcome.ACCEPTED);

        assertEquals(new Reputation(1, 0), new ReputationStore(file).get("student"));
    }

    @Test
    void theServiceReportsTheUserDecisions() throws Exception {
        ReputationStore store = new ReputationStore(null);
        MailAssistant a = MailAssistant.create(AppConfig.load(Path.of("config/app.properties")), new StubLLMClient(),
                false, new InMemoryConversationMemory(), false, store);

        a.service().submit(a.inbox().findById("002").orElseThrow());
        a.service().revise("002", UserFeedback.reject("Plus court"));      // échec pour student
        a.service().accept("002", UserFeedback.accept());                  // succès pour student

        a.service().submit(a.inbox().findById("003").orElseThrow());
        a.service().reroute("003", "administration");                      // échec pour teacher
        a.service().accept("003", UserFeedback.edit("Texte modifié"));     // moitié pour administration

        assertEquals(new Reputation(1, 1), store.get("student"));
        assertEquals(new Reputation(0, 1), store.get("teacher"));
        assertEquals(new Reputation(0.5, 0.5), store.get("administration"));
        assertSame(store, a.reputation());
    }
}