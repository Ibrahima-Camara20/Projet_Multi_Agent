package fr.m2ia.mailmas.context;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class FileContextProviderTest {

    @TempDir
    Path tmp;

    private static Email email(String subject, String body) {
        return new Email("e1", null, "anna@partner-lab.eu", List.of(), subject, body, null, null);
    }

    private static AgentProfile agent(String... sources) {
        return new AgentProfile("test", null, null, "", List.of(sources), null, false);
    }

    private static String source(Path p) {
        return FileContextProvider.PREFIX + p;
    }

    private Path write(String relative, String content) throws Exception {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, UTF_8);
        return file;
    }

    @Test
    void mostRelevantChunkMatchesTheEmail() throws Exception {
        write("ctx/projet/membres.md", "Anna est responsable de l'architecture.");
        write("ctx/projet/echeances.md", "Le livrable WP3 est attendu le 15 novembre.");
        var provider = new FileContextProvider(tmp.resolve("ctx"));

        List<ContextItem> items = provider.retrieve(new ContextQuery(
                email("WP3", "Quelle est la date du livrable WP3 ?"),
                agent(source(tmp.resolve("ctx/projet"))), 1000));

        ContextItem best = items.stream().max(Comparator.comparingDouble(ContextItem::relevance)).orElseThrow();
        assertEquals(2, items.size());
        assertEquals("projet/echeances.md", best.source());
    }

    @Test
    void ignoresCaseAndAccents() {
        var words = FileContextProvider.keywords("ÉCHÉANCE du Projet");
        assertTrue(words.contains("echeance"));
        assertTrue(words.contains("projet"));
        assertFalse(words.contains("du"));
    }

    @Test
    void readsOnlyTheSourcesDeclaredByTheAgent() throws Exception {
        write("ctx/student/cours.md", "TP rendu le 10 octobre.");
        write("ctx/secret/notes.md", "Information confidentielle.");
        var provider = new FileContextProvider(tmp.resolve("ctx"));

        List<ContextItem> items = provider.retrieve(new ContextQuery(
                email("TP", "date ?"), agent(source(tmp.resolve("ctx/student"))), 1000));

        assertEquals(1, items.size());
        assertFalse(items.get(0).content().contains("confidentielle"));
    }

    @Test
    void refusesSourcesOutsideTheRoot() throws Exception {
        write("ailleurs/mots-de-passe.txt", "secret");
        var provider = new FileContextProvider(tmp.resolve("ctx"));

        assertThrows(IllegalArgumentException.class, () -> provider.retrieve(new ContextQuery(
                email("x", "y"), agent(source(tmp.resolve("ctx/../ailleurs"))), 1000)));
    }

    @Test
    void ignoresMissingFoldersAndUnsupportedFiles() throws Exception {
        write("ctx/docs/image.png", "binaire");
        var provider = new FileContextProvider(tmp.resolve("ctx"));

        assertTrue(provider.retrieve(new ContextQuery(email("a", "b"),
                agent(source(tmp.resolve("ctx/docs")), source(tmp.resolve("ctx/absent")), "autre:source"),
                1000)).isEmpty());
    }

    @Test
    void longTextsAreSplitIntoBoundedChunks() {
        String paragraph = "mot ".repeat(200).strip(); // ~800 caractères
        String text = paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<String> chunks = FileContextProvider.chunks(text);
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= FileContextProvider.MAX_CHUNK_CHARS));
    }

    @Test
    void budgetKeepsTheMostRelevantItems() {
        var items = List.of(
                new ContextItem("a", "x".repeat(2000), 0.9),
                new ContextItem("b", "y".repeat(2000), 0.2));
        Context ctx = Context.withinBudget(items, 600);
        assertEquals(List.of("a"), ctx.items().stream().map(ContextItem::source).toList());
    }

    @Test
    void superMasDeadlinesComeFirstForTheWp3Email() {
        var provider = new FileContextProvider(Path.of("config/context"));
        var email = email("SuperMAS - WP3 deliverable",
                "Hi, could you confirm the date for the WP3 deliverable and whether you can also "
                        + "take care of the evaluation section? Best, Anna");

        List<ContextItem> items = provider.retrieve(new ContextQuery(email,
                agent("file:config/context/supermas/"), 1500));

        ContextItem best = items.stream().max(Comparator.comparingDouble(ContextItem::relevance)).orElseThrow();
        assertTrue(best.source().startsWith("supermas/echeances.md"));
        assertTrue(best.content().contains("15 novembre 2026"));
    }

    @Test
    void compositeCombinesProviders() throws Exception {
        write("ctx/a/x.md", "contenu a");
        var files = new FileContextProvider(tmp.resolve("ctx"));
        ContextProvider notes = new ContextProvider() {
            public boolean supports(String s) {
                return s.startsWith("note:");
            }

            public List<ContextItem> retrieve(ContextQuery q) {
                return List.of(new ContextItem("note", "n", 1));
            }
        };
        var composite = new CompositeContextProvider(List.of(files, notes));

        assertTrue(composite.supports("note:x"));
        assertEquals(2, composite.retrieve(new ContextQuery(email("a", "b"),
                agent(source(tmp.resolve("ctx/a")), "note:x"), 1000)).size());
    }
}