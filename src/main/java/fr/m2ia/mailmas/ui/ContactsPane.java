package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.persistence.JsonContactDirectory;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Écran « Contacts » : l'annuaire utilisé par le routing (stratégie « expéditeur »).
 * Les rôles proposés sont ceux que déclarent les agents : on ne peut pas créer par erreur un
 * rôle qu'aucun agent ne reconnaît. Les modifications servent dès l'e-mail suivant.
 */
final class ContactsPane extends HBox {

    private final MainController controller;
    private final JsonContactDirectory directory;
    private final ListView<Contact> list = new ListView<>();
    private final VBox form = new VBox(26);
    private final Label count = Ui.label("", "section-title");

    private Contact editing;
    private boolean creating;
    private TextField emailField;
    private TextField nameField;
    private final List<CheckBox> roleBoxes = new ArrayList<>();
    private Label message;

    ContactsPane(MainController controller) {
        this.controller = controller;
        this.directory = controller.assistant().contacts();

        Button create = new Button("Nouveau contact");
        create.getStyleClass().add("small-button");
        create.setOnAction(e -> {
            list.getSelectionModel().clearSelection();
            edit(null);
        });
        HBox header = new HBox(count, Ui.spacer(), create);
        header.getStyleClass().add("pane-header");
        header.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("inbox");
        list.setCellFactory(v -> new Cell());
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, c) -> {
            if (c != null) {
                edit(c);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox left = new VBox(header, list);
        left.getStyleClass().add("panel-left");
        left.setPrefWidth(320);
        left.setMinWidth(260);

        form.getStyleClass().add("email-pane");
        ScrollPane scroll = new ScrollPane(form);
        scroll.getStyleClass().add("center");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(left, scroll);
    }

    void refresh() {
        reload(editing != null ? editing.email() : null);
    }

    private void reload(String selectEmail) {
        List<Contact> contacts = directory.findAll();
        list.getItems().setAll(contacts);
        count.setText("CONTACTS · " + contacts.size());
        Contact target = contacts.stream().filter(c -> c.email().equalsIgnoreCase(String.valueOf(selectEmail)))
                .findFirst().orElse(contacts.isEmpty() ? null : contacts.get(0));
        if (target != null) {
            list.getSelectionModel().select(target);
            edit(target);
        } else {
            edit(null);
        }
    }

    // ------------------------------------------------------------------ formulaire

    private void edit(Contact contact) {
        editing = contact;
        creating = contact == null;
        emailField = new TextField(creating ? "" : contact.email());
        emailField.setPromptText("prenom.nom@univ.fr");
        Ui.mono(emailField);
        nameField = new TextField(creating ? "" : contact.displayName());
        message = Ui.text("", "small");

        Label title = Ui.text(creating ? "Nouveau contact" : contact.displayName(), "title");
        Label path = Ui.mono(Ui.label("config/contacts.json", "muted", "small"));
        VBox titles = new VBox(4, path, title);
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button save = new Button(creating ? "Ajouter le contact" : "Enregistrer");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> save());
        HBox buttons = new HBox(8);
        if (!creating) {
            Button delete = new Button("Supprimer");
            delete.setOnAction(e -> delete());
            buttons.getChildren().add(delete);
        }
        buttons.getChildren().add(save);
        HBox header = new HBox(16, titles, buttons);
        header.setAlignment(Pos.BOTTOM_LEFT);
        header.setMaxWidth(900);

        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(16);
        grid.setMaxWidth(900);
        for (int i = 0; i < 2; i++) {
            var col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(50);
            grid.getColumnConstraints().add(col);
        }
        grid.add(Ui.field("Adresse e-mail", emailField, null), 0, 0);
        grid.add(Ui.field("Nom affiché", nameField, null), 1, 0);

        form.getChildren().setAll(header, grid, roles(creating ? Set.of() : contact.roles()), message);
    }

    /** Une case par rôle connu des agents, avec les agents qu'il favorise. */
    private VBox roles(Set<String> current) {
        Map<String, List<String>> agentsByRole = new TreeMap<>();
        for (AgentProfile p : controller.assistant().agents().findAll()) {
            for (String role : p.routingHints().senderRoles()) {
                agentsByRole.computeIfAbsent(role.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(p.id());
            }
        }
        for (String role : current) {
            agentsByRole.putIfAbsent(role, List.of());   // rôle saisi à la main, inconnu des agents
        }

        roleBoxes.clear();
        VBox boxes = new VBox(10);
        for (var e : agentsByRole.entrySet()) {
            CheckBox box = new CheckBox(e.getKey());
            box.setUserData(e.getKey());
            box.setSelected(current.contains(e.getKey()));
            Label agents = Ui.mono(Ui.label(e.getValue().isEmpty() ? "aucun agent ne reconnaît ce rôle"
                    : "→ " + String.join(", ", e.getValue()), e.getValue().isEmpty() ? "muted" : "agent-id"));
            HBox row = new HBox(12, box, agents);
            row.setAlignment(Pos.CENTER_LEFT);
            roleBoxes.add(box);
            boxes.getChildren().add(row);
        }

        Label title = Ui.label("Rôles", "h2");
        Label hint = Ui.text("Un rôle relie le contact aux agents qui le déclarent : leurs e-mails sont orientés "
                + "vers ces agents (stratégie « expéditeur », à combiner avec le contenu).", "muted", "small");
        VBox section = new VBox(10, title, hint, boxes);
        section.setMaxWidth(900);
        return section;
    }

    // ------------------------------------------------------------------ actions

    private void save() {
        String email = emailField.getText().strip();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            error("Adresse e-mail invalide.");
            return;
        }
        boolean emailChanged = !creating && !email.equalsIgnoreCase(editing.email());
        if ((creating || emailChanged) && directory.findByEmail(email).isPresent()) {
            error("Ce contact existe déjà.");
            return;
        }
        Set<String> roles = roleBoxes.stream().filter(CheckBox::isSelected)
                .map(b -> (String) b.getUserData())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            directory.save(new Contact(email, nameField.getText().strip(), roles));
            if (emailChanged) {
                directory.delete(editing.email());
            }
            editing = new Contact(email, nameField.getText().strip(), roles);
            reload(email);
            success("Enregistré. Le routing en tient compte dès le prochain e-mail.");
        } catch (RuntimeException e) {
            error("Enregistrement impossible : " + e.getMessage());
        }
    }

    private void delete() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Supprimer « " + editing.displayName() + " » de l'annuaire ?", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        confirm.setTitle("Supprimer un contact");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            directory.delete(editing.email());
            editing = null;
            reload(null);
        } catch (RuntimeException e) {
            error("Suppression impossible : " + e.getMessage());
        }
    }

    private void error(String text) {
        message.setText(text);
        message.getStyleClass().setAll("label", "small", "error-text");
    }

    private void success(String text) {
        message.setText(text);
        message.getStyleClass().setAll("label", "small", "success-text");
    }

    /** Une ligne : nom, adresse, rôles. */
    private static final class Cell extends ListCell<Contact> {
        @Override
        protected void updateItem(Contact c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = Ui.label(c.displayName(), "strong");
            Label email = Ui.mono(Ui.label(c.email(), "muted", "small"));
            Label roles = Ui.mono(Ui.label(c.roles().isEmpty() ? "aucun rôle" : Ui.joinList(c.roles()),
                    c.roles().isEmpty() ? "muted" : "agent-id"));
            VBox box = new VBox(3, name, email, roles);
            box.setPrefWidth(0);
            setGraphic(box);
            setText(null);
        }
    }
}