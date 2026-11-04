package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.RoutingHints;
import fr.m2ia.mailmas.persistence.JsonAgentRepository;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Écran « Agents » : créer, modifier ou supprimer un agent sans toucher au
 * code.
 * Enregistrer écrit config/agents/&lt;id&gt;.json et &lt;id&gt;.md
 * (JsonAgentRepository), puis
 * AssistantService.reloadAgents() remplace les agents sur le bus : les e-mails
 * suivants
 * utilisent aussitôt les nouveaux réglages, sans redémarrer.
 */
final class AgentsPane extends HBox {

    private final MainController controller;
    private final HostServices host;
    private final JsonAgentRepository repository;
    private final ListView<AgentProfile> list = new ListView<>();
    private final VBox form = new VBox(26);
    private final Label count = Ui.label("", "section-title");

    private AgentProfile editing;
    private boolean creating;
    private TextField idField;
    private TextField nameField;
    private TextField descriptionField;
    private TextArea keywordsField;
    private TextArea patternsField;
    private TextField rolesField;
    private CheckBox fallbackBox;
    private TextArea promptArea;
    private Label message;

    AgentsPane(MainController controller, HostServices host) {
        this.controller = controller;
        this.host = host;
        this.repository = controller.assistant().agents();

        Button create = new Button("Nouvel agent");
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
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, p) -> {
            if (p != null) {
                edit(p);
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

    /** Relit les profils (à chaque affichage de l'écran). */
    void refresh() {
        reload(editing != null ? editing.id() : null);
    }

    private void reload(String selectId) {
        List<AgentProfile> agents = repository.findAll();
        list.getItems().setAll(agents);
        count.setText("AGENTS · " + agents.size());
        AgentProfile target = agents.stream().filter(p -> p.id().equals(selectId)).findFirst()
                .orElse(agents.isEmpty() ? null : agents.get(0));
        if (target != null) {
            list.getSelectionModel().select(target);
            edit(target);
        } else {
            edit(null);
        }
    }

    // ------------------------------------------------------------------ formulaire

    private void edit(AgentProfile profile) {
        editing = profile;
        creating = profile == null;
        AgentProfile p = creating
                ? new AgentProfile("nouvel-agent", "", "", "", List.of(), RoutingHints.none(), false)
                : profile;

        idField = new TextField(creating ? "" : p.id());
        idField.setPromptText("ex. : doctorants");
        idField.setEditable(creating);
        Ui.mono(idField);
        nameField = new TextField(p.name().equals(p.id()) && creating ? "" : p.name());
        descriptionField = new TextField(p.description());
        keywordsField = listArea(Ui.joinList(p.routingHints().keywords()));
        patternsField = listArea(Ui.joinList(p.routingHints().senderPatterns()));
        rolesField = new TextField(Ui.joinList(p.routingHints().senderRoles()));
        fallbackBox = new CheckBox("Agent de repli (reçoit les e-mails qu'aucun autre agent ne prend)");
        fallbackBox.setSelected(p.fallback());
        promptArea = new TextArea(p.systemPrompt());
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(10);
        Ui.mono(promptArea);
        promptArea.setAccessibleText("Consignes de l'agent");
        message = Ui.text("", "small");

        form.getChildren().setAll(header(p), identity(), routing(), instructions(), context(p), message);
    }

    private HBox header(AgentProfile p) {
        Label path = Ui.mono(Ui.label(creating ? "nouvel agent" : "config/agents/" + p.id() + ".json + .md",
                "muted", "small"));
        Label title = Ui.text(creating ? "Nouvel agent" : p.name(), "title");
        VBox titles = new VBox(4, path, title);
        HBox.setHgrow(titles, Priority.ALWAYS);

        Button save = new Button(creating ? "Créer l'agent" : "Enregistrer et recharger");
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
        return header;
    }

    private GridPane identity() {
        GridPane grid = grid();
        grid.add(Ui.field("Identifiant", idField, creating
                ? "Minuscules, chiffres et tirets. Il sert de nom de fichier et ne pourra plus changer."
                : null), 0, 0);
        grid.add(Ui.field("Nom affiché", nameField, null), 1, 0);
        grid.add(Ui.field("Description (lue par le LLM lors du départage des cas ambigus)", descriptionField, null),
                0, 1, 2, 1);
        return grid;
    }

    private VBox routing() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        for (int i = 0; i < 3; i++) {
            var col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(col);
        }
        grid.add(Ui.field("Mots-clés", keywordsField, "Séparés par des virgules."), 0, 0);
        grid.add(Ui.field("Adresses", patternsField, "Ex. : *@etu.univ.fr"), 1, 0);
        grid.add(Ui.field("Rôles des contacts", rolesField, "Ex. : student (voir l'écran Contacts)."), 2, 0);
        VBox box = new VBox(14, Ui.label("Indices de routing", "h2"), grid, fallbackBox);
        box.setMaxWidth(900);
        return box;
    }

    private VBox instructions() {
        Label title = Ui.label("Consignes (system prompt)", "h2");
        Label hint = Ui.text("Comment l'agent répond. Elles s'ajoutent aux règles communes à tous les agents "
                + "(ne rien inventer, ne pas s'engager, répondre dans la langue de l'e-mail…).", "muted", "small");
        VBox box = new VBox(8, title, hint, promptArea);
        box.setMaxWidth(900);
        return box;
    }

    private VBox context(AgentProfile p) {
        Label title = Ui.label("Contexte (faits seulement)", "h2");
        Label hint = Ui.text("Ce que l'agent sait : dates, membres, procédures. Pas de consignes ici, "
                + "le modèle les recopierait dans ses réponses.", "muted", "small");
        VBox box = new VBox(8, title, hint);
        box.setMaxWidth(900);
        if (creating) {
            box.getChildren().add(Ui.text("Le dossier config/context/<identifiant>/ sera créé avec l'agent : "
                    + "déposez-y des fichiers .md ou .txt.", "small"));
            return box;
        }

        GridPane files = new GridPane();
        files.getStyleClass().add("file-table");
        files.setHgap(16);
        files.setVgap(4);
        int row = 0;
        for (Path file : contextFiles(p)) {
            Label name = Ui.mono(Ui.label(file.toString().replace('\\', '/'), "small"));
            GridPane.setHgrow(name, Priority.ALWAYS);
            Label size = Ui.label("≈ " + tokens(file) + " tokens", "muted", "small");
            Button open = new Button("Ouvrir");
            open.getStyleClass().add("small-button");
            open.setOnAction(e -> host.showDocument(file.toAbsolutePath().toUri().toString()));
            files.addRow(row++, name, size, open);
        }
        if (row == 0) {
            box.getChildren().add(Ui.text("Aucun document : l'agent ne connaît que ses consignes.", "muted", "small"));
        } else {
            box.getChildren().add(files);
        }
        for (Path dir : contextDirs(p)) {
            Button openDir = new Button("Ouvrir le dossier " + dir.toString().replace('\\', '/'));
            openDir.getStyleClass().add("small-button");
            openDir.setOnAction(e -> {
                try {
                    Files.createDirectories(dir);
                    host.showDocument(dir.toAbsolutePath().toUri().toString());
                } catch (IOException ex) {
                    error("Impossible d'ouvrir " + dir + " : " + ex.getMessage());
                }
            });
            box.getChildren().add(openDir);
        }
        return box;
    }

    // ------------------------------------------------------------------ actions

    private void save() {
        String id = creating ? idField.getText().strip().toLowerCase(Locale.ROOT) : editing.id();
        if (!id.matches("[a-z0-9][a-z0-9-]*")) {
            error("Identifiant invalide : minuscules, chiffres et tirets uniquement.");
            return;
        }
        if (creating && repository.findById(id).isPresent()) {
            error("Un agent « " + id + " » existe déjà.");
            return;
        }
        if (fallbackBox.isSelected() && repository.findAll().stream()
                .anyMatch(other -> other.fallback() && !other.id().equals(id))) {
            error("Il ne peut y avoir qu'un seul agent de repli.");
            return;
        }
        List<String> sources = creating ? List.of("file:config/context/" + id + "/") : editing.contextSources();
        AgentProfile profile = new AgentProfile(id,
                nameField.getText().isBlank() ? id : nameField.getText().strip(),
                descriptionField.getText().strip(),
                promptArea.getText().strip(),
                sources,
                new RoutingHints(Ui.splitList(patternsField.getText()), Ui.splitList(rolesField.getText()),
                        Ui.splitList(keywordsField.getText())),
                fallbackBox.isSelected());
        try {
            repository.save(profile);
            if (creating) {
                Files.createDirectories(Path.of("config", "context", id));
            }
            controller.assistant().service().reloadAgents();
            reload(id);
            success("Enregistré. Les prochains e-mails utiliseront ces réglages, sans redémarrage.");
        } catch (IOException | RuntimeException e) {
            error("Enregistrement impossible : " + e.getMessage());
        }
    }

    private void delete() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Supprimer l'agent « " + editing.id() + " » ? Ses fichiers .json et .md seront effacés "
                        + "(ses documents de contexte sont conservés).",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        confirm.setTitle("Supprimer un agent");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            repository.delete(editing.id());
            controller.assistant().service().reloadAgents();
            editing = null;
            reload(null);
        } catch (RuntimeException e) {
            error("Suppression impossible : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ aides

    private static List<Path> contextDirs(AgentProfile p) {
        List<Path> dirs = new ArrayList<>();
        for (String source : p.contextSources()) {
            if (source.startsWith("file:") && !source.endsWith(".md") && !source.endsWith(".txt")) {
                dirs.add(Path.of(source.substring("file:".length())));
            }
        }
        return dirs;
    }

    private static List<Path> contextFiles(AgentProfile p) {
        List<Path> files = new ArrayList<>();
        for (String source : p.contextSources()) {
            if (!source.startsWith("file:")) {
                continue;
            }
            Path path = Path.of(source.substring("file:".length()));
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".md") || f.toString().endsWith(".txt"))
                            .sorted()
                            .forEach(files::add);
                } catch (IOException ignored) {
                    // dossier illisible : affiché comme vide
                }
            }
        }
        return files;
    }

    private static long tokens(Path file) {
        try {
            return Math.max(1, Files.size(file) / 4);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Zone de saisie d'une liste séparée par des virgules, sur plusieurs lignes si
     * besoin.
     */
    private static TextArea listArea(String value) {
        TextArea area = new TextArea(value);
        area.setWrapText(true);
        area.setPrefRowCount(3);
        return area;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(16);
        grid.setMaxWidth(900);
        for (int i = 0; i < 2; i++) {
            var col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(50);
            grid.getColumnConstraints().add(col);
        }
        return grid;
    }

    private void error(String text) {
        message.setText(text);
        message.getStyleClass().setAll("label", "small", "error-text");
    }

    private void success(String text) {
        message.setText(text);
        message.getStyleClass().setAll("label", "small", "success-text");
    }

    /** Une ligne de la liste : identifiant, repli, description. */
    private static final class Cell extends ListCell<AgentProfile> {
        @Override
        protected void updateItem(AgentProfile p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            HBox top = new HBox(8, Ui.mono(Ui.label(p.id(), "strong")));
            top.setAlignment(Pos.CENTER_LEFT);
            if (p.fallback()) {
                top.getChildren().add(Ui.label("repli", "badge"));
            }
            int keywords = p.routingHints().keywords().size();
            Label detail = Ui.text(p.description().isBlank() ? p.name() : p.description(), "muted", "small");
            Label stats = Ui.label(keywords + " mot" + (keywords > 1 ? "s" : "") + "-clé"
                    + (keywords > 1 ? "s" : ""), "muted", "small");
            VBox box = new VBox(3, top, detail, stats);
            box.setPrefWidth(0);
            setGraphic(box);
            setText(null);
        }
    }
}