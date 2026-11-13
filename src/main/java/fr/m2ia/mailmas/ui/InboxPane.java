package fr.m2ia.mailmas.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Colonne de gauche : la boîte de réception. */
final class InboxPane extends VBox {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
            .withZone(ZoneId.systemDefault());

    private final MainController controller;
    private final ListView<InboxItem> list = new ListView<>();
    private final Label count = Ui.label("", "section-title");

    InboxPane(MainController controller) {
        this.controller = controller;
        getStyleClass().add("panel-left");
        setPrefWidth(320);
        setMinWidth(260);

        Button refresh = new Button("Actualiser");
        refresh.getStyleClass().add("small-button");
        refresh.setOnAction(e -> controller.loadInbox());
        Button newEmail = new Button("Nouveau");
        newEmail.getStyleClass().add("small-button");
        newEmail.setOnAction(e -> newEmail());
        Button importFiles = new Button("Importer");
        importFiles.getStyleClass().add("small-button");
        importFiles.setOnAction(e -> importFiles());
        HBox header = new HBox(6, count, Ui.spacer(), newEmail, importFiles, refresh);
        header.getStyleClass().add("pane-header");
        header.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("inbox");
        list.setItems(controller.items());
        list.setCellFactory(v -> new Cell());
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> controller.select(item));
        VBox.setVgrow(list, Priority.ALWAYS);

        controller.items().addListener((javafx.collections.ListChangeListener<InboxItem>) c -> updateCount());
        controller.onItemChanged(item -> list.refresh());
        updateCount();

        getChildren().addAll(header, list);
    }

    /** Formulaire « Nouvel e-mail » : l'e-mail ajouté est sélectionné, donc traité tout de suite. */
    private void newEmail() {
        new NewEmailDialog(controller.assistant().contacts().findAll(), controller.assistant().inbox().findAll())
                .showAndWait()
                .ifPresent(r -> {
                    var email = controller.addEmail(r.from(), r.subject(), r.body(), r.replyTo());
                    controller.items().stream()
                            .filter(i -> i.email.id().equals(email.id()))
                            .findFirst()
                            .ifPresent(i -> {
                                list.getSelectionModel().select(i);
                                list.scrollTo(i);
                            });
                });
    }

    /** Import de fichiers .eml ou .json ; les fichiers refusés sont listés avec la raison. */
    private void importFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Importer des e-mails");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("E-mails (.eml, .json)", "*.eml", "*.json"),
                new FileChooser.ExtensionFilter("Fichiers .eml", "*.eml"),
                new FileChooser.ExtensionFilter("Fichiers .json", "*.json"));
        List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        List<String> problems = controller.importFiles(files);
        if (!problems.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, String.join("\n", problems));
            alert.setHeaderText("Certains fichiers n'ont pas été importés");
            alert.showAndWait();
        }
    }

    private void updateCount() {
        count.setText("REÇUS · " + controller.items().size());
    }

    /** Une ligne : expéditeur, date, objet, agent et état. */
    private final class Cell extends ListCell<InboxItem> {
        @Override
        protected void updateItem(InboxItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label sender = Ui.label(controller.displayName(item.email.from()), "strong");
            Label date = Ui.label(DAY.format(item.email.receivedAt()), "muted", "small");
            HBox top = new HBox(8, sender, Ui.spacer(), date);

            Label subject = Ui.label(item.email.subject());
            subject.setMaxWidth(Double.MAX_VALUE);

            HBox bottom = new HBox(8);
            bottom.setAlignment(Pos.CENTER_LEFT);
            if (item.agentId() != null) {
                bottom.getChildren().add(Ui.mono(Ui.label(item.agentId(), "agent-id")));
            }
            if (item.ambiguous()) {
                bottom.getChildren().add(Ui.label("ambigu", "badge-ambiguous"));
            } else {
                bottom.getChildren().add(Ui.label(item.status.label, "muted", "small"));
            }

            VBox box = new VBox(4, top, subject, bottom);
            setGraphic(box);
            setText(null);
            setAccessibleText(controller.displayName(item.email.from()) + ", " + item.email.subject()
                    + ", " + item.status.label);
        }
    }
}