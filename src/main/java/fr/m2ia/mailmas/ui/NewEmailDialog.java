package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.model.Email;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Formulaire « Nouvel e-mail » : saisir ou coller un e-mail reçu pour le faire traiter.
 * « En réponse à » place l'e-mail dans un fil existant (mémoire et affinité de fil).
 */
final class NewEmailDialog extends Dialog<NewEmailDialog.Result> {

    /** Ce que l'utilisateur a saisi ; replyTo est null pour une nouvelle conversation. */
    record Result(String from, String subject, String body, Email replyTo) {
    }

    /** Un choix de la liste « En réponse à » (email null = nouvelle conversation). */
    private record ThreadChoice(String label, Email email) {
        @Override
        public String toString() {
            return label;
        }
    }

    NewEmailDialog(List<Contact> contacts, List<Email> inbox) {
        setTitle("Nouvel e-mail");
        setHeaderText("Ajouter un e-mail reçu à la boîte de réception");
        getDialogPane().getStylesheets().add(NewEmailDialog.class.getResource("/ui/mailmas.css").toExternalForm());
        getDialogPane().setPrefWidth(600);

        ComboBox<String> from = new ComboBox<>(FXCollections.observableArrayList(
                contacts.stream().map(Contact::email).sorted().toList()));
        from.setEditable(true);
        from.setPromptText("lea.martin@etu.univ.fr");

        List<ThreadChoice> choices = new ArrayList<>();
        choices.add(new ThreadChoice("Nouvelle conversation", null));
        for (int i = inbox.size() - 1; i >= 0; i--) {          // du plus récent au plus ancien
            Email e = inbox.get(i);
            choices.add(new ThreadChoice(e.subject() + "  —  " + e.from(), e));
        }
        ComboBox<ThreadChoice> thread = new ComboBox<>(FXCollections.observableArrayList(choices));
        thread.getSelectionModel().selectFirst();

        TextField subject = new TextField();
        TextArea body = new TextArea();
        body.setWrapText(true);
        body.setPrefRowCount(10);

        // Répondre dans un fil : on préremplit l'expéditeur et l'objet s'ils sont vides.
        thread.valueProperty().addListener((obs, old, choice) -> {
            if (choice != null && choice.email() != null) {
                Email e = choice.email();
                if (from.getEditor().getText().isBlank()) {
                    from.getEditor().setText(e.from());
                }
                if (subject.getText().isBlank()) {
                    subject.setText(e.subject().startsWith("Re:") ? e.subject() : "Re: " + e.subject());
                }
            }
        });

        VBox form = new VBox(14,
                Ui.field("De", from, "Adresse de l'expéditeur ; les contacts connus sont proposés."),
                Ui.field("En réponse à", thread, "Facultatif : l'e-mail rejoint ce fil de discussion."),
                Ui.field("Objet", subject, null),
                Ui.field("Message", body, null));
        getDialogPane().setContent(form);

        ButtonType add = new ButtonType("Ajouter et traiter", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        Node addButton = getDialogPane().lookupButton(add);
        addButton.getStyleClass().add("primary");
        // Bouton actif seulement avec une adresse valide et un message.
        addButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !from.getEditor().getText().strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
                        || body.getText().isBlank(),
                from.getEditor().textProperty(), body.textProperty()));

        setResultConverter(button -> button == add
                ? new Result(from.getEditor().getText().strip(), subject.getText().strip(), body.getText().strip(),
                        thread.getValue() != null ? thread.getValue().email() : null)
                : null);
    }
}