package fr.m2ia.mailmas.memory;

import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;

import java.util.List;

/** Historique des fils de discussion (reçus, brouillons, envoyés). */
public interface ConversationMemory {

    void recordReceived(Email email);

    void recordDraft(String threadId, DraftReply draft);

    void recordSent(String threadId, DraftReply sent);

    /** Les maxEntries entrées les plus récentes du fil, dans l'ordre chronologique. */
    List<ConversationEntry> recent(String threadId, int maxEntries);
}
