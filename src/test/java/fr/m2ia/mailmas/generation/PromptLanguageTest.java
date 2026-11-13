package fr.m2ia.mailmas.generation;

import org.junit.jupiter.api.Test;

import static fr.m2ia.mailmas.support.TestSupport.email;
import static org.junit.jupiter.api.Assertions.*;

/** La langue de la réponse est imposée explicitement, d'après la langue de l'e-mail. */
class PromptLanguageTest {

    @Test
    void anEnglishEmailAsksForAnEnglishReply() {
        String instruction = PromptBuilder.languageInstruction(email("e1", "anna@partner-lab.eu",
                "Plenary in Rome", "Hi, could you remind me when the plenary meeting takes place? Thanks, Anna"));
        assertTrue(instruction.contains("anglais"));
        assertTrue(instruction.contains("Write the whole reply in English"));
    }

    @Test
    void aFrenchEmailAsksForAFrenchReply() {
        String instruction = PromptBuilder.languageInstruction(email("e1", "lea.martin@etu.univ.fr",
                "Rendu du TP3", "Bonjour, est-ce que le rapport est obligatoire pour le rendu ? Merci, Léa"));
        assertTrue(instruction.contains("en français"));
    }
}