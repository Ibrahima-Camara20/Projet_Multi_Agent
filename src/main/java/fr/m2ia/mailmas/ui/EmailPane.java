package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.RoutingDecision;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Colonne centrale : l'e-mail et la proposition de réponse, avec les actions de l'utilisateur
 * (valider, modifier, rejeter avec un commentaire, changer d'agent). Rien n'est envoyé sans
 * validation explicite.
 */
final class EmailPane extends ScrollPane {

    private static final DateTimeFormatter RECEIVED = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final double MAX_WIDTH = 760;

    private final MainController controller;
    private final VBox content = new VBox(22);
    private InboxItem current;
    private Timeline timer;

    EmailPane(MainController controller) {
        this.controller = controller;
        getStyleClass().add("center");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.getStyleClass().add("email-pane");
        setContent(content);

        controller.selectedProperty().addListener((obs, old, item) -> show(item));
        controller.onItemChanged(item -> {
            if (item == current) {
                show(item);
            }
        });
        show(null);
    }

    private void show(InboxItem item) {
        current = item;
        stopTimer();
        content.getChildren().clear();
        if (item == null) {
            Label empty = Ui.text("Choisissez un e-mail dans la boîte de réception : le système choisira l'agent "
                    + "adapté et proposera une réponse.", "muted");
            empty.setMaxWidth(420);
            content.getChildren().add(empty);
            return;
        }
        content.getChildren().addAll(header(item.email), body(item.email));
        switch (item.status) {
            case TO_PROCESS -> content.getChildren().add(toProcess(item));
            case WORKING -> content.getChildren().add(working(item));
            case FAILED -> content.getChildren().add(failure(item));
            case PROPOSED, SENT -> {
                if (item.ambiguous()) {
                    ambiguityBanner(item).ifPresent(content.getChildren()::add);
                }
                content.getChildren().add(proposal(item));
            }
        }
        setVvalue(0);
    }

    // ------------------------------------------------------------------ e-mail reçu

    private VBox header(Email email) {
        Label meta = Ui.mono(Ui.label("fil " + email.threadId() + " · reçu le "
                + RECEIVED.format(email.receivedAt()), "muted", "small"));
        Label subject = Ui.text(email.subject(), "title");
        Label from = Ui.text("De " + controller.displayName(email.from()) + " <" + email.from() + ">"
                + (email.to().isEmpty() ? "" : " · à " + String.join(", ", email.to())), "muted");
        return new VBox(4, meta, subject, from);
    }

    private VBox body(Email email) {
        VBox box = new VBox(Ui.text(email.body()));
        box.getStyleClass().add("email-body");
        box.setMaxWidth(MAX_WIDTH);
        return box;
    }

    // ------------------------------------------------------------------ états

    private VBox toProcess(InboxItem item) {
        Button go = primary("Proposer une réponse");
        go.setOnAction(e -> controller.process(item));
        return new VBox(go);
    }

    private HBox working(InboxItem item) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label title = Ui.label("Rédaction en cours…", "strong");
        Label detail = Ui.label("", "muted", "small");
        Runnable tick = () -> detail.setText("Le modèle local peut prendre 20 à 60 secondes · "
                + (System.currentTimeMillis() - item.startedAt) / 1000 + " s");
        tick.run();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick.run()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        HBox box = new HBox(14, spinner, new VBox(2, title, detail));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private VBox failure(InboxItem item) {
        Label title = Ui.label("La proposition n'a pas pu être rédigée", "strong");
        Label message = Ui.text(item.error);
        Button retry = primary("Réessayer");
        retry.setOnAction(e -> controller.retry(item));
        FlowPane actions = actions(retry, changeAgentMenu(item));
        VBox box = new VBox(8, title, message, actions);
        box.getStyleClass().add("error-box");
        box.setMaxWidth(MAX_WIDTH);
        return box;
    }

    /** Bandeau du cas ambigu : deux agents presque à égalité, l'utilisateur peut trancher. */
    private Optional<HBox> ambiguityBanner(InboxItem item) {
        var ranking = item.result.routing().ranking();
        if (ranking.size() < 2) {
            return Optional.empty();
        }
        RoutingDecision.Candidate a = ranking.get(0);
        RoutingDecision.Candidate b = ranking.get(1);
        String other = a.agentId().equals(item.agentId()) ? b.agentId() : a.agentId();

        Label title = Ui.label("Deux agents conviennent presque autant", "strong");
        Label detail = Ui.text(a.agentId() + " " + Ui.score(a.score()) + " · " + b.agentId() + " "
                + Ui.score(b.score()) + ". Proposition rédigée par " + item.agentId() + ".");
        VBox text = new VBox(2, title, detail);
        HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);
        Button switchAgent = new Button("Confier à " + other);
        switchAgent.getStyleClass().add("banner-button");
        switchAgent.setOnAction(e -> controller.reroute(item, other));

        HBox banner = new HBox(16, text, switchAgent);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("banner-ambiguous");
        banner.setMaxWidth(MAX_WIDTH);
        return Optional.of(banner);
    }

    // ------------------------------------------------------------------ proposition

    private VBox proposal(InboxItem item) {
        DraftReply draft = item.status == InboxItem.Status.SENT ? item.sent : item.result.draft();
        boolean sent = item.status == InboxItem.Status.SENT;

        Label title = Ui.label(sent ? "Réponse validée" : "Proposition de réponse", "h2");
        Label by = Ui.label("rédigée par ", "muted", "small");
        Label agent = Ui.mono(Ui.label(draft.agentId(), "agent-id"));
        Label info = Ui.label(" · proposition n°" + draft.attempt() + (sent ? "" : " · " + item.seconds + " s"),
                "muted", "small");
        HBox heading = new HBox(12, title, new HBox(by, agent, info));
        heading.setAlignment(Pos.BASELINE_LEFT);

        Label subject = Ui.label("Objet : " + draft.subject(), "muted", "small");
        TextArea text = new TextArea(draft.body());
        text.getStyleClass().add("draft");
        text.setWrapText(true);
        text.setPrefRowCount(9);
        text.setEditable(!sent);
        text.setAccessibleText("Texte de la réponse");

        VBox box = new VBox(10, heading, subject, text);
        box.setMaxWidth(MAX_WIDTH);
        if (sent) {
            box.getChildren().add(Ui.text("Réponse enregistrée comme envoyée dans l'historique du fil.", "muted", "small"));
            return box;
        }

        VBox rejectPanel = rejectPanel(item);
        Button accept = primary("Valider la réponse");
        accept.setOnAction(e -> controller.accept(item, text.getText()));
        Button reject = new Button("Rejeter avec un commentaire");
        reject.setOnAction(e -> {
            rejectPanel.setVisible(true);
            rejectPanel.setManaged(true);
            javafx.scene.Node field = rejectPanel.lookup(".text-area");
            if (field != null) {
                field.requestFocus();
            }
        });
        box.getChildren().addAll(
                actions(accept, reject, changeAgentMenu(item)),
                Ui.text("Vos modifications dans le texte sont conservées à la validation. "
                        + "Rien n'est envoyé sans votre accord.", "muted", "small"),
                rejectPanel);
        return box;
    }

    private VBox rejectPanel(InboxItem item) {
        Label question = Ui.label("Pourquoi rejetez-vous cette proposition ?", "strong");
        TextArea comment = new TextArea();
        comment.setPromptText("Ex. : trop long, ne t'engage pas sur la date, réponds en anglais…");
        comment.setWrapText(true);
        comment.setPrefRowCount(3);
        question.setLabelFor(comment);

        VBox panel = new VBox(10);
        Button regenerate = primary("Régénérer (proposition n°" + (item.result.draft().attempt() + 1) + ")");
        regenerate.setOnAction(e -> controller.reject(item, comment.getText()));
        Button cancel = new Button("Annuler");
        cancel.setOnAction(e -> {
            panel.setVisible(false);
            panel.setManaged(false);
        });
        Label hint = Ui.label("Le même agent réécrit en tenant compte de tous vos commentaires.", "muted", "small");
        FlowPane buttons = actions(regenerate, cancel);
        buttons.getChildren().add(hint);

        panel.getChildren().addAll(question, comment, buttons);
        panel.getStyleClass().add("reject-panel");
        panel.setVisible(false);
        panel.setManaged(false);
        return panel;
    }

    private MenuButton changeAgentMenu(InboxItem item) {
        MenuButton menu = new MenuButton("Changer d'agent");
        for (String id : controller.agentIds()) {
            if (!id.equals(item.agentId())) {
                MenuItem choice = new MenuItem(id);
                choice.setOnAction(e -> controller.reroute(item, id));
                menu.getItems().add(choice);
            }
        }
        return menu;
    }

    // ------------------------------------------------------------------ aides

    private static Button primary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("primary");
        return b;
    }

    private static FlowPane actions(javafx.scene.Node... nodes) {
        FlowPane pane = new FlowPane(8, 8, nodes);
        pane.setAlignment(Pos.CENTER_LEFT);
        return pane;
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }
}