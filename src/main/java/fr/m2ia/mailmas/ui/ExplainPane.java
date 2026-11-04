package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.routing.RoutingDecision;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * Colonne de droite : pourquoi cet agent (scores et justifications du routing), quels documents
 * de contexte il a reçus, et le journal des échanges entre agents sur le bus.
 */
final class ExplainPane extends ScrollPane {

    /** Noms des stratégies de Rania, traduits pour l'utilisateur. */
    private static final Map<String, String> STRATEGIES = Map.of(
            "keyword", "mots-clés", "sender", "expéditeur", "metadata", "métadonnées", "thread", "fil");

    private final MainController controller;
    private final VBox content = new VBox(28);
    private final VBox journal = new VBox(2);
    private ObservableList<String> journalLines;
    private final ListChangeListener<String> journalListener = c -> fillJournal();
    private InboxItem current;

    ExplainPane(MainController controller) {
        this.controller = controller;
        getStyleClass().add("panel-right");
        setFitToWidth(true);
        setPrefWidth(360);
        setMinWidth(280);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.getStyleClass().add("explain");
        setContent(content);

        journal.getStyleClass().add("journal");

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
        content.getChildren().clear();
        if (item == null || item.result == null || item.result.routing() == null) {
            content.getChildren().add(Ui.text("Les explications du routing apparaîtront ici après le traitement "
                    + "d'un e-mail.", "muted", "small"));
            return;
        }
        content.getChildren().addAll(routingSection(item), contextSection(item), journalSection(item));
    }

    private VBox routingSection(InboxItem item) {
        var config = controller.assistant().config();
        VBox box = new VBox(4,
                Ui.label("POURQUOI CET AGENT", "section-title"),
                Ui.label("Routing pondéré · seuil " + Ui.score(config.routingThreshold())
                        + " · marge " + Ui.score(config.ambiguityMargin()), "muted", "small"));

        VBox candidates = new VBox(14);
        candidates.setStyle("-fx-padding: 10 0 0 0;");
        List<RoutingDecision.Candidate> ranking = item.result.routing().ranking();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            RoutingDecision.Candidate c = ranking.get(i);
            boolean chosen = c.agentId().equals(item.agentId());
            Label name = Ui.mono(Ui.label(c.agentId(), chosen ? "strong" : "muted"));
            Label score = Ui.mono(Ui.label(Ui.score(c.score()), chosen ? "strong" : "muted"));
            HBox row = new HBox(name, Ui.spacer(), score);

            String fill = chosen ? "chosen" : i == 1 ? "second" : "other";
            VBox candidate = new VBox(6, row, new ScoreBar(c.score(), config.routingThreshold(), fill));
            if (c.score() > 0) {
                for (String reason : c.reason().split(" ; ")) {
                    candidate.getChildren().add(Ui.text("• " + translate(reason), "reason"));
                }
            }
            candidates.getChildren().add(candidate);
        }
        box.getChildren().add(candidates);

        if (!item.result.routing().ranking().isEmpty() && item.agentId() != null
                && ranking.stream().noneMatch(c -> c.agentId().equals(item.agentId()))) {
            box.getChildren().add(Ui.text("Aucun agent n'atteint le seuil : l'agent de repli "
                    + item.agentId() + " a été choisi.", "note"));
        }
        if (item.result.message() != null) {
            box.getChildren().add(Ui.text(item.result.message(), "note"));
        }
        return box;
    }

    private VBox contextSection(InboxItem item) {
        VBox box = new VBox(10, Ui.label("CONTEXTE UTILISÉ", "section-title"));
        List<ContextItem> items = controller.contextFor(item);
        if (items.isEmpty()) {
            box.getChildren().add(Ui.text("Aucun document : l'agent n'a que ses consignes.", "muted", "small"));
            return box;
        }
        GridPane grid = new GridPane();
        grid.getStyleClass().add("context-table");
        int tokens = 0;
        for (int i = 0; i < items.size(); i++) {
            ContextItem c = items.get(i);
            Label file = Ui.mono(Ui.label(c.source(), "small"));
            Label relevance = Ui.label(Ui.score(c.relevance()), "muted", "small");
            GridPane.setHgrow(file, Priority.ALWAYS);
            grid.addRow(i, file, relevance);
            tokens += c.estimatedTokens();
        }
        box.getChildren().addAll(grid, Ui.label("≈ " + tokens + " tokens sur un budget de "
                + controller.assistant().config().contextTokenBudget(), "muted", "small"));
        return box;
    }

    private VBox journalSection(InboxItem item) {
        if (journalLines != null) {
            journalLines.removeListener(journalListener);
        }
        journalLines = controller.journal(item.email.id());
        journalLines.addListener(journalListener);
        fillJournal();
        return new VBox(10, Ui.label("ÉCHANGES ENTRE AGENTS", "section-title"), journal);
    }

    private void fillJournal() {
        journal.getChildren().clear();
        if (journalLines.isEmpty()) {
            journal.getChildren().add(Ui.label("Aucun échange.", "muted", "small"));
        }
        for (String line : journalLines) {
            journal.getChildren().add(Ui.mono(Ui.text(line, "journal-line")));
        }
    }

    /** « keyword 0.93 (mots-clés : SuperMAS, WP) » → « mots-clés 0,93 : SuperMAS, WP ». */
    private static String translate(String reason) {
        var m = java.util.regex.Pattern.compile("^(\\w+) ([0-9.]+) \\((.*)\\)$").matcher(reason);
        if (!m.matches()) {
            return reason;
        }
        String name = STRATEGIES.getOrDefault(m.group(1), m.group(1));
        String detail = m.group(3).replaceFirst("^mots-clés : ", "");
        return name + " " + Ui.score(Double.parseDouble(m.group(2))) + " : " + detail;
    }
}