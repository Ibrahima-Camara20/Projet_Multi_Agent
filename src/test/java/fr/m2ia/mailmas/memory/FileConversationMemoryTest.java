package fr.m2ia.mailmas.memory;

import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.ConversationEntry.Kind;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FileConversationMemoryTest {

    @TempDir
    Path dir;

    private final Email email = new Email("001", "wp3", "anna@partner-lab.eu", List.of(), "WP3",
            "Hi,\ncould you confirm the date?\n\"Thanks\"", Instant.parse("2026-09-28T09:15:00Z"), null);

    private static DraftReply draft(String body, int attempt) {
        return new DraftReply("d" + attempt, "001", "research-supermas", "Re: WP3", body, attempt,
                Instant.parse("2026-10-01T10:00:00Z"));
    }

    private void fillThread(ConversationMemory memory) {
        memory.recordReceived(email);
        memory.recordDraft("wp3", draft("Première proposition", 1));
        memory.recordSent("wp3", draft("Texte envoyé", 1));
    }

    @Test
    void keepsEntriesInOrderWithTheirKindAndAuthor() {
        var memory = new FileConversationMemory(dir);
        fillThread(memory);

        List<ConversationEntry> entries = memory.recent("wp3", 10);
        assertEquals(List.of(Kind.RECEIVED, Kind.DRAFT, Kind.SENT),
                entries.stream().map(ConversationEntry::kind).toList());
        assertEquals("anna@partner-lab.eu", entries.get(0).author());
        assertEquals("research-supermas", entries.get(1).author());
        assertEquals("user", entries.get(2).author());
    }

    @Test
    void historySurvivesARestart() {
        fillThread(new FileConversationMemory(dir));

        List<ConversationEntry> reread = new FileConversationMemory(dir).recent("wp3", 10);
        assertEquals(3, reread.size());
        assertEquals(email.body(), reread.get(0).text()); // retours à la ligne et guillemets conservés
        assertEquals(Instant.parse("2026-09-28T09:15:00Z"), reread.get(0).at());
        assertEquals("Texte envoyé", reread.get(2).text());
    }

    @Test
    void recentReturnsTheLastEntriesChronologically() {
        var memory = new FileConversationMemory(dir);
        for (int i = 1; i <= 5; i++) {
            memory.recordDraft("t", draft("proposition " + i, i));
        }
        assertEquals(List.of("proposition 4", "proposition 5"),
                memory.recent("t", 2).stream().map(ConversationEntry::text).toList());
        assertTrue(memory.recent("inconnu", 5).isEmpty());
    }

    @Test
    void oneLinePerEntry() throws Exception {
        fillThread(new FileConversationMemory(dir));
        assertEquals(3, Files.readAllLines(dir.resolve("wp3.jsonl"), UTF_8).size());
    }

    @Test
    void damagedLinesAreSkipped() throws Exception {
        fillThread(new FileConversationMemory(dir));
        Files.writeString(dir.resolve("wp3.jsonl"), "{ ligne coupée par un arrêt brutal", UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        assertEquals(3, new FileConversationMemory(dir).recent("wp3", 10).size());
    }

    @Test
    void threadIdsBecomeSafeAndDistinctFileNames() {
        String a = FileConversationMemory.fileName("../../secret");
        String b = FileConversationMemory.fileName("a/b");
        String c = FileConversationMemory.fileName("a_b");

        assertFalse(a.contains("/") || a.contains(".."));
        assertNotEquals(b, c);
        assertEquals("supermas-wp3.jsonl", FileConversationMemory.fileName("supermas-wp3"));

        var memory = new FileConversationMemory(dir);
        memory.recordDraft("a/b", draft("x", 1));
        memory.recordDraft("a_b", draft("y", 1));
        var reread = new FileConversationMemory(dir);
        assertEquals("x", reread.recent("a/b", 1).get(0).text());
        assertEquals("y", reread.recent("a_b", 1).get(0).text());
    }

    @Test
    void clearRemovesEverything() {
        var memory = new FileConversationMemory(dir);
        fillThread(memory);
        memory.clear();

        assertTrue(memory.recent("wp3", 10).isEmpty());
        assertTrue(new FileConversationMemory(dir).recent("wp3", 10).isEmpty());
    }

    @Test
    void behavesLikeTheInMemoryVersion() {
        var file = new FileConversationMemory(dir);
        var ram = new InMemoryConversationMemory();
        fillThread(file);
        fillThread(ram);
        assertEquals(ram.recent("wp3", 10), file.recent("wp3", 10));
    }
}