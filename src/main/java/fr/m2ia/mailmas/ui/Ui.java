package fr.m2ia.mailmas.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Petites aides partagées par les vues : polices, libellés, nombres. */
final class Ui {

    /** Police du texte et police des données techniques, choisies au démarrage. */
    static String sans = "System";
    static String mono = "Monospaced";

    private Ui() {
    }

    /**
     * Charge Source Sans 3 et JetBrains Mono depuis src/main/resources/fonts/ si
     * elles y sont ;
     * sinon, polices de Windows (Segoe UI, Consolas) ou polices par défaut de Java.
     */
    static void loadFonts() {
        boolean sansOk = load("SourceSans3-Regular.ttf") & load("SourceSans3-Bold.ttf");
        boolean monoOk = load("JetBrainsMono-Regular.ttf") & load("JetBrainsMono-Bold.ttf");
        List<String> families = Font.getFamilies();
        sans = sansOk ? "Source Sans 3" : families.contains("Segoe UI") ? "Segoe UI" : "System";
        mono = monoOk ? "JetBrains Mono" : families.contains("Consolas") ? "Consolas" : "Monospaced";
    }

    private static boolean load(String file) {
        try (InputStream in = Ui.class.getResourceAsStream("/fonts/" + file)) {
            return in != null && Font.loadFont(in, 14) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    /** Libellé qui revient à la ligne (corps d'e-mail, justifications). */
    static Label text(String text, String... styleClasses) {
        Label l = label(text, styleClasses);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** Donnée technique (agent, score, fil) : police à chasse fixe. */
    static <T extends Node> T mono(T node) {
        node.setStyle("-fx-font-family: '" + mono + "';");
        return node;
    }

    static Region spacer() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** 0.97 → « 0,97 », comme dans le reste de l'interface en français. */
    static String score(double value) {
        return String.format(Locale.FRANCE, "%.2f", value);
    }

    /**
     * Champ de formulaire : libellé au-dessus du contrôle, et une aide facultative
     * en dessous.
     */
    static javafx.scene.layout.VBox field(String label, javafx.scene.control.Control control, String hint) {
        Label l = label(label, "form-label");
        l.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6, l, control);
        if (hint != null) {
            box.getChildren().add(text(hint, "muted", "small"));
        }
        return box;
    }

    /** « a, b ,, c » → {a, b, c} (listes saisies séparées par des virgules). */
    static java.util.Set<String> splitList(String text) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (String v : text.split(",")) {
            if (!v.isBlank()) {
                values.add(v.strip());
            }
        }
        return values;
    }

    static String joinList(java.util.Collection<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining(", "));
    }
}