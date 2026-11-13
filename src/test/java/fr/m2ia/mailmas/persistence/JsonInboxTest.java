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

    @Test
    void savedEmailIsReadBack() {
        JsonInbox inbox = new JsonInbox(dir);
        String id = inbox.newId();
        inbox.save(new Email(id, null, "lea.martin@etu.univ.fr", null, "Rendu du TP3", "Bonjour Madame",
                Instant.parse("2026-11-11T08:30:00Z"), null));

        Email read = inbox.findById(id).orElseThrow();
        assertTrue(id.startsWith("form-"));
        assertEquals(id, read.threadId());
        assertEquals("Bonjour Madame", read.body());
        assertEquals(Instant.parse("2026-11-11T08:30:00Z"), read.receivedAt());
    }

    @Test
    void newIdsAreUnique() {
        JsonInbox inbox = new JsonInbox(dir);
        String first = inbox.newId();
        inbox.save(new Email(first, null, "a@x.fr", null, "", "", null, null));
        assertNotEquals(first, inbox.newId());
    }

    @Test
    void importChecksTheFileAndCopiesIt() throws Exception {
        write("ext.json", """
                { "id": "ext-1", "from": "paul.durand@univ.fr", "subject": "Jury" }""");
        write("bad.json", "{ pas du json");
        JsonInbox target = new JsonInbox(dir.resolve("inbox"));

        target.importFile(dir.resolve("ext.json"));
        assertEquals("Jury", target.findById("ext-1").orElseThrow().subject());
        assertThrows(RepositoryException.class, () -> target.importFile(dir.resolve("bad.json")));
    }

    @Test
    void unsafeIdentifierIsRefused() throws Exception {
        write("evil.json", """
                { "id": "../../evil", "from": "x@y.fr" }""");
        JsonInbox target = new JsonInbox(dir.resolve("inbox"));
        assertThrows(RepositoryException.class, () -> target.importFile(dir.resolve("evil.json")));
    }
}