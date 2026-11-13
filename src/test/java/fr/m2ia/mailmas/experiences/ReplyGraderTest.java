package fr.m2ia.mailmas.experiences;

import fr.m2ia.mailmas.experiences.ReplyGrader.Expectation;
import fr.m2ia.mailmas.experiences.ReplyGrader.Grade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReplyGraderTest {

    private static final Expectation MISSION = new Expectation("004", List.of("administration"), "fr", "vous",
            List.of(List.of("OM-2026-118"), List.of("formulaire"), List.of("facture d'inscription", "facture")),
            List.of("en règle"), List.of("TP3", "D3.1"));

    private static final String SOURCES = "Ordre de mission n° OM-2026-118, colloque du 2 au 4 décembre 2026. "
            + "Libre de 13h30 à 18h.";

    @Test
    void aGoodReplyGetsAllFactsAndNoFlag() {
        Grade g = ReplyGrader.grade("Madame, Monsieur, je vous transmettrai prochainement le formulaire signé "
                + "et la facture d'inscription pour l'ordre de mission OM-2026-118 (colloque du 2 décembre).",
                MISSION, SOURCES);

        assertEquals(3, g.factsFound());
        assertEquals(1.0, g.factRate());
        assertTrue(g.forbiddenFound().isEmpty());
        assertTrue(g.leaksFound().isEmpty());
        assertTrue(g.languageOk());
        assertTrue(g.unsupported().isEmpty(), "le 2 décembre est dans les sources");
    }

    @Test
    void forbiddenPhrasesLeaksAndUnsupportedDatesAreFlagged() {
        Grade g = ReplyGrader.grade("Comme convenu, veuillez trouver ci-joint le formulaire. Les autres pièces sont "
                + "en règle. Pour le TP3, rendez-vous le 12 décembre à 9h.", MISSION, SOURCES);

        assertEquals(List.of("ci-joint", "comme convenu", "en règle"), g.forbiddenFound());
        assertEquals(List.of("TP3"), g.leaksFound());
        assertEquals(List.of("09:00", "12/12"), g.unsupported());
        assertEquals(1, g.factsFound());
        assertEquals(List.of("OM-2026-118", "facture d'inscription / facture"), g.missingFacts());
    }

    @Test
    void datesAndTimesAreNormalised() {
        assertEquals(Set.of("14:00", "14:30", "15/11"),
                ReplyGrader.datesAndTimes("jeudi 14h, ou 14h30, ou 2 pm ; avant le 15 novembre, soit november 15"));
        assertEquals(Set.of("09:00", "15/11"), ReplyGrader.datesAndTimes("9:00 on the 15/11"));
    }

    @Test
    void languageIsDetected() {
        assertEquals("en", ReplyGrader.language("hi anna, the deliverable is due on 15 november. best regards"));
        assertEquals("fr", ReplyGrader.language("bonjour lea, le rendu du tp3 comprend le code et le rapport"));
    }

    @Test
    void addressIsChecked() {
        Expectation colleague = new Expectation("003", List.of("teacher"), "fr", "tu", List.of(), List.of(), List.of());
        assertTrue(ReplyGrader.grade("Salut Paul, jeudi 14h te convient ? Je t'envoie les copies.", colleague, "").addressOk());
        assertFalse(ReplyGrader.grade("Madame, Monsieur, je vous confirme ma présence.", colleague, "").addressOk());
        assertTrue(ReplyGrader.grade("Jeudi 14h semble convenir.", colleague, "").addressOk(), "aucun pronom : rien à reprocher");
        assertEquals("vous", ReplyGrader.address("je vous remercie pour votre message"));
    }

    @Test
    void placeholdersAreCounted() {
        Grade g = ReplyGrader.grade("La réunion aura lieu le [À COMPLÉTER] à [À COMPLÉTER].", MISSION, SOURCES);
        assertEquals(2, g.placeholders());
    }
}