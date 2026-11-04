package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.App;
import fr.m2ia.mailmas.MailAssistant;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.OllamaLLMClient;
import fr.m2ia.mailmas.llm.StubLLMClient;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * Interface graphique (JavaFX). Elle n'utilise que MailAssistant, la racine de composition :
 * la console et l'interface pilotent exactement le même système.
 */
public class MailMasApp extends Application {

    private MainController controller;
    private BorderPane root;
    private AgentsPane agentsView;
    private ContactsPane contactsView;
    private final java.util.List<Button> navButtons = new java.util.ArrayList<>();

    @Override
    public void start(Stage stage) throws Exception {
        boolean stub = getParameters().getRaw().contains("--stub");
        Ui.loadFonts();

        AppConfig config = AppConfig.load(Path.of("config/app.properties"));
        boolean ollamaUp = !stub && App.ollamaIsRunning(config.llmUrl());
        LLMClient llm = stub ? new StubLLMClient() : OllamaLLMClient.from(config);
        controller = new MainController(MailAssistant.create(config, llm, !stub));

        // Les trois écrans ; l'en-tête permet de passer de l'un à l'autre.
        BorderPane inboxView = new BorderPane();
        inboxView.setLeft(new InboxPane(controller));
        inboxView.setCenter(new EmailPane(controller));
        inboxView.setRight(new ExplainPane(controller));
        agentsView = new AgentsPane(controller, getHostServices());
        contactsView = new ContactsPane(controller);

        root = new BorderPane();
        root.getStyleClass().add("app");
        root.setStyle("-fx-font-family: '" + Ui.sans + "';");
        root.setTop(header(config, stub, ollamaUp, inboxView));
        root.setCenter(inboxView);

        Scene scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(MailMasApp.class.getResource("/ui/mailmas.css").toExternalForm());
        stage.setTitle("Mail MAS — assistant de réponse");
        stage.setMinWidth(1100);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();

        controller.loadInbox();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    private HBox header(AppConfig config, boolean stub, boolean ollamaUp, BorderPane inboxView) {
        Label wordmark = Ui.mono(Ui.label("mail·mas", "wordmark"));
        Label tagline = Ui.label("assistant de réponse", "muted", "small");
        HBox brand = new HBox(8, wordmark, tagline);
        brand.setAlignment(Pos.BASELINE_LEFT);

        Button inbox = navButton("Boîte de réception", () -> root.setCenter(inboxView));
        Button agents = navButton("Agents", () -> {
            agentsView.refresh();
            root.setCenter(agentsView);
        });
        Button contacts = navButton("Contacts", () -> {
            contactsView.refresh();
            root.setCenter(contactsView);
        });
        activate(inbox);
        HBox nav = new HBox(4, inbox, agents, contacts);

        Circle dot = new Circle(4);
        dot.getStyleClass().add(stub ? "dot-warn" : ollamaUp ? "dot-ok" : "dot-bad");
        Label model = Ui.mono(Ui.label(stub ? "stub" : config.llmModel(), "small"));
        Label status = Ui.label(stub ? "Mode test, sans modèle ·" : ollamaUp ? "Ollama ·" : "Ollama injoignable ·",
                "muted", "small");
        HBox llm = new HBox(6, dot, status, model);
        llm.setAlignment(Pos.CENTER_LEFT);
        Label memory = Ui.label("Mémoire : data/conversations", "muted", "small");

        HBox header = new HBox(24, brand, nav, Ui.spacer(), llm, memory);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Button navButton(String text, Runnable show) {
        Button b = new Button(text);
        b.getStyleClass().add("nav");
        b.setOnAction(e -> {
            show.run();
            activate(b);
        });
        navButtons.add(b);
        return b;
    }

    /** Onglet actif : souligné en bleu. */
    private void activate(Button active) {
        for (Button b : navButtons) {
            b.getStyleClass().removeAll("nav", "nav-active");
            b.getStyleClass().add(b == active ? "nav-active" : "nav");
        }
    }
}