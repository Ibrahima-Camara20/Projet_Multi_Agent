package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.model.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class JsonInboxTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, UTF_8);
    }

    @Test
    void readsTheProjectInbox() {
        List<Email> emails = new JsonInbox(Path.of("config/inbox")).findAll();

        assertEquals(6, emails.size());
        Email anna = emails.get(0);
        assertEquals("001", anna.id());
        assertEquals("supermas-wp3", anna.threadId());
        assertEquals("anna.muller@partner-lab.eu", anna.from());
        assertEquals(Instant.parse("2026-09-28T09:15:00Z"), anna.receivedAt());
        assertTrue(anna.body().contains("WP3"));
    }

    @Test
    void sortsByDateAndFillsDefaults() throws Exception {
        write("b.json", """
                { "id": "b", "from": "x@univ.fr", "receivedAt": "2026-10-02T08:00:00Z" }""");
        write("a.json", """
                { "id": "a", "from": "y@univ.fr", "subject": "Récent", "receivedAt": "2026-10-03T08:00:00Z" }""");
        write("notes.txt", "pas un e-mail");

        List<Email> emails = new JsonInbox(dir).findAll();
        assertEquals(List.of("b", "a"), emails.stream().map(Email::id).toList());
        Email b = emails.get(0);
        assertEquals("b", b.threadId());          // sans fil : l'e-mail forme son propre fil
        assertEquals("", b.subject());
        assertTrue(b.to().isEmpty());
        assertTrue(new JsonInbox(dir).findById("a").isPresent());
        assertTrue(new JsonInbox(dir).findById("z").isEmpty());
    }

    @Test
    void missingFolderGivesEmptyInbox() {
        assertTrue(new JsonInbox(dir.resolve("absent")).findAll().isEmpty());
    }

    @Test
    void reportsInvalidEmailsWithTheFileName() throws Exception {
        write("date.json", """
                { "id": "1", "from": "x@univ.fr", "receivedAt": "28/09/2026" }""");
        var e = assertThrows(RepositoryException.class, () -> new JsonInbox(dir).findAll());
        assertTrue(e.getMessage().contains("date.json"));
        Files.delete(dir.resolve("date.json"));

        write("sans-id.json", "{ \"from\": \"x@univ.fr\" }");
        assertThrows(RepositoryException.class, () -> new JsonInbox(dir).findAll());
        Files.delete(dir.resolve("sans-id.json"));

        write("x1.json", "{ \"id\": \"1\", \"from\": \"x@univ.fr\" }");
        write("x2.json", "{ \"id\": \"1\", \"from\": \"y@univ.fr\" }");
        var dup = assertThrows(RepositoryException.class, () -> new JsonInbox(dir).findAll());
        assertTrue(dup.getMessage().contains("double"));
    }
}