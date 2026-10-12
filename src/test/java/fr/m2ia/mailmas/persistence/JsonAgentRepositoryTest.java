package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.RoutingHints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class JsonAgentRepositoryTest {

    @TempDir
    Path dir;

    private static AgentProfile student() {
        return new AgentProfile("student", "Étudiants", "Questions d'étudiants",
                "Tu réponds aux étudiants avec bienveillance.",
                List.of("file:config/context/student/"),
                new RoutingHints(Set.of("*@etu.univ.fr"), Set.of("student"), Set.of("TP", "examen")),
                false);
    }

    @Test
    void saveThenReadGivesTheSameProfile() {
        var repo = new JsonAgentRepository(dir);
        repo.save(student());

        assertEquals(student(), repo.findById("student").orElseThrow());
        assertTrue(Files.exists(dir.resolve("student.json")));
        assertTrue(Files.exists(dir.resolve("student.md")));
    }

    @Test
    void readsSystemPromptFromMarkdownFile() throws Exception {
        Files.writeString(dir.resolve("admin.json"), """
                { "id": "admin", "name": "Administration" }""", UTF_8);
        Files.writeString(dir.resolve("admin.md"), "  Ton formel et précis.  \n", UTF_8);

        AgentProfile p = new JsonAgentRepository(dir).findById("admin").orElseThrow();
        assertEquals("Ton formel et précis.", p.systemPrompt());
        assertFalse(p.fallback());
        assertTrue(p.contextSources().isEmpty());
    }

    @Test
    void missingMarkdownGivesEmptyPrompt() throws Exception {
        Files.writeString(dir.resolve("generic.json"), """
                { "id": "generic", "fallback": true }""", UTF_8);

        AgentProfile p = new JsonAgentRepository(dir).findById("generic").orElseThrow();
        assertEquals("", p.systemPrompt());
        assertTrue(p.fallback());
    }

    @Test
    void findAllIsSortedAndIgnoresOtherFiles() throws Exception {
        var repo = new JsonAgentRepository(dir);
        repo.save(student());
        Files.writeString(dir.resolve("admin.json"), "{ \"id\": \"admin\" }", UTF_8);
        Files.writeString(dir.resolve("notes.txt"), "pas un agent", UTF_8);

        List<String> ids = repo.findAll().stream().map(AgentProfile::id).toList();
        assertEquals(List.of("admin", "student"), ids);
    }

    @Test
    void emptyListWhenFolderDoesNotExist() {
        assertTrue(new JsonAgentRepository(dir.resolve("absent")).findAll().isEmpty());
    }

    @Test
    void deleteRemovesBothFiles() {
        var repo = new JsonAgentRepository(dir);
        repo.save(student());
        repo.delete("student");

        assertTrue(repo.findById("student").isEmpty());
        assertFalse(Files.exists(dir.resolve("student.md")));
    }

    @Test
    void rejectsDangerousIds() {
        var repo = new JsonAgentRepository(dir);
        var evil = new AgentProfile("../secret", null, null, "x", null, null, false);
        assertThrows(IllegalArgumentException.class, () -> repo.save(evil));
        assertThrows(IllegalArgumentException.class, () -> repo.findById("Majuscules"));
    }

    @Test
    void reportsIdMismatchWithFileName() throws Exception {
        Files.writeString(dir.resolve("a.json"), "{ \"id\": \"b\" }", UTF_8);
        var e = assertThrows(RepositoryException.class, () -> new JsonAgentRepository(dir).findAll());
        assertTrue(e.getMessage().contains("a.json"));
    }

    @Test
    void reportsInvalidJsonWithFileName() throws Exception {
        Files.writeString(dir.resolve("broken.json"), "{ pas du json", UTF_8);
        var e = assertThrows(RepositoryException.class, () -> new JsonAgentRepository(dir).findAll());
        assertTrue(e.getMessage().contains("broken.json"));
    }

    @Test
    void readsTheProjectSuperMasProfile() {
        AgentProfile p = new JsonAgentRepository(Path.of("config/agents"))
                .findById("research-supermas").orElseThrow();
        assertTrue(p.systemPrompt().contains("SuperMASProject"));
        assertTrue(p.routingHints().keywords().contains("SuperMAS"));
        assertFalse(p.fallback());
    }
}