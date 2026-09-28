package fr.m2ia.mailmas.memory;

import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.ConversationEntry.Kind;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Implémentation minimale non persistante (MVP et tests). */
public class InMemoryConversationMemory implements ConversationMemory {

    private final Map<String, List<ConversationEntry>> threads = new ConcurrentHashMap<>();

    private void add(String threadId, ConversationEntry e) {
        threads.computeIfAbsent(threadId, k -> new ArrayList<>()).add(e);
    }

    @Override public void recordReceived(Email email) {
        add(email.threadId(), new ConversationEntry(Kind.RECEIVED, email.from(), email.body(), email.receivedAt()));
    }

    @Override public void recordDraft(String threadId, DraftReply d) {
        add(threadId, new ConversationEntry(Kind.DRAFT, d.agentId(), d.body(), d.createdAt()));
    }

    @Override public void recordSent(String threadId, DraftReply d) {
        add(threadId, new ConversationEntry(Kind.SENT, "user", d.body(), d.createdAt()));
    }

    @Override public List<ConversationEntry> recent(String threadId, int maxEntries) {
        List<ConversationEntry> all = threads.getOrDefault(threadId, List.of());
        return List.copyOf(all.subList(Math.max(0, all.size() - maxEntries), all.size()));
    }
}
