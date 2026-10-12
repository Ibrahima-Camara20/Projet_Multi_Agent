package fr.m2ia.mailmas.persistence;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;
import fr.m2ia.mailmas.routing.SenderRoutingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class JsonContactDirectoryTest {

    @TempDir
    Path dir;

    private Path write(String content) throws Exception {
        Path file = dir.resolve("contacts.json");
        Files.writeString(file, content, UTF_8);
        return file;
    }

    @Test
    void findsContactsIgnoringCase() throws Exception {
        var directory = new JsonContactDirectory(write("""
                [ { "email": "Lea.Martin@etu.univ.fr", "name": "Léa", "roles": ["Student", " "] } ]"""));

        Contact lea = directory.findByEmail("lea.martin@ETU.univ.fr").orElseThrow();
        assertEquals("Léa", lea.displayName());
        assertEquals(Set.of("student"), lea.roles());
        assertTrue(directory.findByEmail("inconnu@univ.fr").isEmpty());
        assertTrue(directory.findByEmail(null).isEmpty());
    }

    @Test
    void missingFileGivesEmptyDirectory() {
        var directory = new JsonContactDirectory(dir.resolve("absent.json"));
        assertTrue(directory.findAll().isEmpty());
    }

    @Test
    void missingNameAndRolesHaveDefaults() throws Exception {
        Contact c = new JsonContactDirectory(write("[ { \"email\": \"x@univ.fr\" } ]"))
                .findByEmail("x@univ.fr").orElseThrow();
        assertEquals("x@univ.fr", c.displayName());
        assertTrue(c.roles().isEmpty());
    }

    @Test
    void reportsInvalidFilesWithTheirName() throws Exception {
        var invalidJson = assertThrows(RepositoryException.class,
                () -> new JsonContactDirectory(write("[ pas du json")));
        assertTrue(invalidJson.getMessage().contains("contacts.json"));

        var duplicate = assertThrows(RepositoryException.class, () -> new JsonContactDirectory(write("""
                [ { "email": "a@univ.fr" }, { "email": "A@univ.fr" } ]""")));
        assertTrue(duplicate.getMessage().contains("double"));

        assertThrows(RepositoryException.class, () -> new JsonContactDirectory(write("[ { \"name\": \"Sans adresse\" } ]")));
        assertThrows(RepositoryException.class, () -> new JsonContactDirectory(write("[ { \"email\": \"pas-une-adresse\" } ]")));
    }

    @Test
    void saveAndDeleteArePersisted() {
        Path file = dir.resolve("sous-dossier/contacts.json");
        var directory = new JsonContactDirectory(file);

        directory.save(new Contact("paul@univ.fr", "Paul", Set.of("teacher")));
        directory.save(new Contact("PAUL@univ.fr", "Paul Durand", Set.of("teacher", "colleague")));
        directory.save(new Contact("lea@etu.univ.fr", "Léa", Set.of("student")));

        var reread = new JsonContactDirectory(file);
        assertEquals(2, reread.findAll().size());
        assertEquals("Paul Durand", reread.findByEmail("paul@univ.fr").orElseThrow().displayName());

        reread.delete("paul@univ.fr");
        assertEquals(List.of("lea@etu.univ.fr"),
                new JsonContactDirectory(file).findAll().stream().map(Contact::email).toList());
        assertFalse(Files.exists(dir.resolve("sous-dossier/contacts.json.tmp")));
    }

    @Test
    void reloadSeesManualChanges() throws Exception {
        Path file = write("[]");
        var directory = new JsonContactDirectory(file);
        assertTrue(directory.findByEmail("a@univ.fr").isEmpty());

        Files.writeString(file, "[ { \"email\": \"a@univ.fr\", \"roles\": [\"teacher\"] } ]", UTF_8);
        directory.reload();
        assertTrue(directory.findByEmail("a@univ.fr").isPresent());
    }

    /** Chaque rôle de l'annuaire du projet doit exister dans au moins un profil d'agent. */
    @Test
    void projectContactsUseRolesKnownByAgents() {
        var directory = new JsonContactDirectory(Path.of("config/contacts.json"));
        Set<String> knownRoles = new JsonAgentRepository(Path.of("config/agents")).findAll().stream()
                .flatMap(p -> p.routingHints().senderRoles().stream())
                .collect(Collectors.toSet());

        assertFalse(directory.findAll().isEmpty());
        for (Contact c : directory.findAll()) {
            for (String role : c.roles()) {
                assertTrue(knownRoles.contains(role),
                        "rôle inconnu des agents : " + role + " (" + c.email() + ")");
            }
        }
    }

    /** Marco n'a pas une adresse partner-lab.eu : seul son rôle dans l'annuaire le relie à SuperMAS. */
    @Test
    void knownRoleRoutesASenderWhoseAddressMatchesNoPattern() {
        List<AgentProfile> agents = new JsonAgentRepository(Path.of("config/agents")).findAll();
        var email = new Email("x", null, "Marco Rossi <marco.rossi@ai-lab.it>", List.of(), "Hello", "Quick question", null, null);

        RoutingDecision without = new SenderRoutingStrategy(null).route(email, agents);
        RoutingDecision with = new SenderRoutingStrategy(new JsonContactDirectory(Path.of("config/contacts.json")))
                .route(email, agents);

        assertEquals(0.0, without.best().orElseThrow().score());
        assertEquals("research-supermas", with.best().orElseThrow().agentId());
        assertTrue(with.best().orElseThrow().reason().contains("research-partner"));
    }
}