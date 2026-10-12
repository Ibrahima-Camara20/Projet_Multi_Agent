package fr.m2ia.mailmas.generation;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.context.Context;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.ContextQuery;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.llm.LLMException;
import fr.m2ia.mailmas.llm.LLMRequest;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.model.ConversationEntry;
import fr.m2ia.mailmas.model.DraftReply;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.model.UserFeedback;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Facade de génération : contexte -> historique -> prompt -> LLM -> DraftReply.
 * Ne fait que LIRE la mémoire : l'enregistrement est la responsabilité
 * d'AssistantService.
 */
public class DefaultDraftGenerator implements DraftGenerator {

    private final LLMClient llm;
    private final ContextProvider contextProvider;
    private final ConversationMemory memory;
    private final PromptBuilder prompts;
    private final int tokenBudget;
    private final int maxHistoryEntries;

    public DefaultDraftGenerator(LLMClient llm, ContextProvider contextProvider, ConversationMemory memory,
            PromptBuilder prompts, int tokenBudget, int maxHistoryEntries) {
        this.llm = llm;
        this.contextProvider = contextProvider;
        this.memory = memory;
        this.prompts = prompts;
        this.tokenBudget = tokenBudget;
        this.maxHistoryEntries = maxHistoryEntries;
    }

    public static DefaultDraftGenerator from(AppConfig config, LLMClient llm,
            ContextProvider contextProvider, ConversationMemory memory) {
        return new DefaultDraftGenerator(llm, contextProvider, memory, new PromptBuilder(),
                config.contextTokenBudget(), config.maxHistoryEntries());
    }

    @Override
    public DraftReply generate(Email email, AgentProfile agent, List<UserFeedback> previousFeedback, int attempt)
            throws LLMException {
        Context context = loadContext(email, agent);
        List<ConversationEntry> history = memory.recent(email.threadId(), maxHistoryEntries);

        LLMRequest request = prompts.build(email, agent, context, history,
                previousFeedback != null ? previousFeedback : List.of(), attempt);
        String body = clean(llm.generate(request).text());
        if (body.isBlank()) {
            throw new LLMException("Le modèle a renvoyé une réponse vide.");
        }
        return new DraftReply(UUID.randomUUID().toString(), email.id(), agent.id(),
                replySubject(email.subject()), body, attempt, null);
    }

    /**
     * Le contrat ne prévoit que LLMException : une erreur de contexte est remontée
     * sous cette forme.
     */
    private Context loadContext(Email email, AgentProfile agent) throws LLMException {
        try {
            return Context.withinBudget(
                    contextProvider.retrieve(new ContextQuery(email, agent, tokenBudget)), tokenBudget);
        } catch (RuntimeException e) {
            throw new LLMException("Contexte de l'agent " + agent.id() + " illisible : " + e.getMessage(), e);
        }
    }

    static String replySubject(String subject) {
        String s = subject == null ? "" : subject.strip();
        return s.toLowerCase(Locale.ROOT).matches("^re\\s*:.*") ? s : "Re: " + s;
    }

    /**
     * Retire ce que le modèle ajoute parfois malgré les consignes (bloc de code,
     * ligne d'objet).
     */
    static String clean(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t.replaceFirst("(?i)^(objet|subject)\\s*:[^\\n]*\\n+", "").strip();
    }
}