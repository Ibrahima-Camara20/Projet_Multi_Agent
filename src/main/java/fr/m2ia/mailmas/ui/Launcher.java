package fr.m2ia.mailmas.ui;

import javafx.application.Application;

/**
 * Point d'entrée de l'interface. Une classe qui n'hérite pas d'Application permet de lancer
 * JavaFX depuis le classpath (Maven exec:java, IDE) sans configuration de modules.
 *
 * <pre>
 *   Launcher           avec Ollama
 *   Launcher --stub    sans modèle (StubLLMClient), pour travailler sur l'interface
 * </pre>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(MailMasApp.class, args);
    }
}