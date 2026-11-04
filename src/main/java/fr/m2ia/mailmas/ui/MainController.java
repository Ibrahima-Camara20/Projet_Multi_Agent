package fr.m2ia.mailmas.ui;

import fr.m2ia.mailmas.MailAssistant;
import fr.m2ia.mailmas.agent.AgentMessage;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.Payload;
import fr.m2ia.mailmas.app.ProcessingResult;
import fr.m2ia.mailmas.context.Context;
import fr.m2ia.mailmas.context.ContextItem;
import fr.m2ia.mailmas.context.ContextQuery;
import fr.m2ia.mailmas.model.Contact;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;
import fr.m2ia.mailmas.routing.TextMatching;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Relie l'interface au système multi-agent (via MailAssistant).
 *
 * <p><b>Threads</b> : les appels au système (qui appellent le LLM, 20 à 60 s) passent par un
 * unique thread de travail, pour ne jamais geler l'interface et ne jamais solliciter le modèle
 * local deux fois en même temps. Les résultats reviennent dans le thread JavaFX par
 * {@link Platform#runLater}, seul thread qui modifie l'état des {@link InboxItem}.</p>
 */
final class MainController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MailAssistant assistant;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mailmas-llm");
        t.setDaemon(true);
        return t;
    });

    private final ObservableList<InboxItem> items = FXCollections.observableArrayList();
    private final ObjectProperty<InboxItem> selected = new SimpleObjectProperty<>();
    private final Map<String, ObservableList<String>> journals = new HashMap<>();
    private final List<Consumer<InboxItem>> changeListeners = new ArrayList<>();

    MainController(MailAssistant assistant) {
        this.assistant = assistant;
        // Observer sur le bus : chaque échange entre agents est ajouté au journal de son e-mail.
        assistant.bus().addListener(m -> Platform.runLater(() -> journal(m.conversationId()).add(format(m))));
    }

    MailAssistant assistant() {
        return assistant;
    }

    ObservableList<InboxItem> items() {
        return items;
    }

    ObjectProperty<InboxItem> selectedProperty() {
        return selected;
    }

    /** Appelé quand l'état d'un e-mail change (fin de rédaction, validation...). */
    void onItemChanged(Consumer<InboxItem> listener) {
        changeListeners.add(listener);
    }

    ObservableList<String> journal(String emailId) {
        return journals.computeIfAbsent(emailId, k -> FXCollections.observableArrayList());
    }

    // ------------------------------------------------------------------ boîte de réception

    /** (Re)lit la boîte de réception en gardant l'état des e-mails déjà affichés. */
    void loadInbox() {
        Map<String, InboxItem> known = new HashMap<>();
        items.forEach(i -> known.put(i.email.id(), i));
        List<InboxItem> fresh = new ArrayList<>();
        for (Email e : assistant.inbox().findAll()) {
            fresh.add(known.getOrDefault(e.id(), new InboxItem(e)));
        }
        items.setAll(fresh);
    }

    /** Sélectionne un e-mail ; un e-mail jamais traité part tout de suite au système. */
    void select(InboxItem item) {
        selected.set(item);
        if (item != null && item.status == InboxItem.Status.TO_PROCESS) {
            process(item);
        }
    }

    // ------------------------------------------------------------------ actions de l'utilisateur

    void process(InboxItem item) {
        run(item, () -> assistant.service().submit(item.email));
    }

    /** Rejet avec commentaire : le même agent propose une nouvelle version. */
    void reject(InboxItem item, String comment) {
        run(item, () -> assistant.service().revise(item.email.id(), UserFeedback.reject(comment)));
    }

    /** L'utilisateur confie l'e-mail à un autre agent. */
    void reroute(InboxItem item, String agentId) {
        run(item, () -> assistant.service().reroute(item.email.id(), agentId));
    }

    /** Après un échec : même agent s'il y en a un, sinon nouveau routing. */
    void retry(InboxItem item) {
        if (item.agentId() != null) {
            run(item, () -> assistant.service().revise(item.email.id(), null));
        } else {
            process(item);
        }
    }

    /** Validation ; si l'utilisateur a modifié le texte, c'est sa version qui est enregistrée. */
    void accept(InboxItem item, String text) {
        DraftReply draft = item.result.draft();
        UserFeedback feedback = text.equals(draft.body()) ? UserFeedback.accept() : UserFeedback.edit(text);
        worker.submit(() -> {
            try {
                assistant.service().accept(item.email.id(), feedback);
                DraftReply sent = assistant.service().sentReply(item.email.id()).orElse(draft);
                Platform.runLater(() -> {
                    item.sent = sent;
                    item.status = InboxItem.Status.SENT;
                    changed(item);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> fail(item, e.getMessage()));
            }
        });
    }

    private void run(InboxItem item, Callable<ProcessingResult> call) {
        item.status = InboxItem.Status.WORKING;
        item.error = null;
        item.startedAt = System.currentTimeMillis();
        changed(item);
        worker.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                ProcessingResult r = call.call();
                long seconds = (System.currentTimeMillis() - start) / 1000;
                Platform.runLater(() -> {
                    item.result = r.routing() != null || item.result == null ? r : merge(item.result, r);
                    item.seconds = seconds;
                    if (r.status() == ProcessingResult.Status.PROPOSED) {
                        item.status = InboxItem.Status.PROPOSED;
                        changed(item);
                    } else {
                        fail(item, r.message());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> fail(item, e.getMessage()));
            }
        });
    }

    /** Un échec sans décision de routing garde l'ancienne décision pour l'affichage. */
    private static ProcessingResult merge(ProcessingResult old, ProcessingResult r) {
        return new ProcessingResult(r.status(), r.email(), old.routing(), r.selectedAgentId(), r.ambiguous(),
                r.draft(), r.message());
    }

    private void fail(InboxItem item, String message) {
        item.status = InboxItem.Status.FAILED;
        item.error = message == null || message.isBlank() ? "Erreur inconnue." : message;
        changed(item);
    }

    private void changed(InboxItem item) {
        changeListeners.forEach(l -> l.accept(item));
    }

    // ------------------------------------------------------------------ informations pour les vues

    /** Nom affiché de l'expéditeur, tiré de l'annuaire s'il est connu. */
    String displayName(String from) {
        String address = TextMatching.emailAddress(from);
        return assistant.contacts().findByEmail(address).map(Contact::displayName).orElse(address);
    }

    List<String> agentIds() {
        return assistant.agents().findAll().stream().map(AgentProfile::id).toList();
    }

    /** Documents de contexte que l'agent choisi a reçus pour cet e-mail, avec leur pertinence. */
    List<ContextItem> contextFor(InboxItem item) {
        if (item.agentId() == null) {
            return List.of();
        }
        try {
            int budget = assistant.config().contextTokenBudget();
            return assistant.agents().findById(item.agentId())
                    .map(agent -> Context.withinBudget(
                            assistant.context().retrieve(new ContextQuery(item.email, agent, budget)), budget).items())
                    .orElse(List.of());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private static String format(AgentMessage m) {
        String detail = switch (m.payload()) {
            case Payload.Refusal r -> " : " + r.reason();
            case Payload.Failure f -> " : " + f.reason();
            case Payload.DraftRequest r -> "";
            case Payload.DraftProposal p -> "";
        };
        return LocalTime.now().format(TIME) + "  " + m.performative() + " " + m.sender() + " → " + m.receiver() + detail;
    }
}