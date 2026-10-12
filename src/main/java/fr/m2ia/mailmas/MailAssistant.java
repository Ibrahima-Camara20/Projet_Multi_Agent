package fr.m2ia.mailmas;

import fr.m2ia.mailmas.agent.DefaultAgentFactory;
import fr.m2ia.mailmas.agent.MessageBus;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.SimpleMessageBus;
import fr.m2ia.mailmas.app.DefaultAssistantService;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.context.CompositeContextProvider;
import fr.m2ia.mailmas.context.ContextProvider;
import fr.m2ia.mailmas.context.FileContextProvider;
import fr.m2ia.mailmas.generation.DefaultDraftGenerator;
import fr.m2ia.mailmas.generation.DraftGenerator;
import fr.m2ia.mailmas.llm.LLMClient;
import fr.m2ia.mailmas.memory.ConversationMemory;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.persistence.JsonAgentRepository;
import fr.m2ia.mailmas.persistence.JsonContactDirectory;
import fr.m2ia.mailmas.persistence.JsonInbox;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.LlmRoutingStrategy;
import fr.m2ia.mailmas.routing.MetadataRoutingStrategy;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.routing.SenderRoutingStrategy;
import fr.m2ia.mailmas.routing.ThreadAffinityStrategy;
import fr.m2ia.mailmas.routing.WeightedRoutingStrategy;

import java.util.List;

/**
 * Racine de composition : le SEUL endroit qui crée les implémentations concrètes et les relie.
 * Partout ailleurs, les classes ne dépendent que des interfaces (inversion des dépendances).
 *
 * <p>Utilisée par le démonstrateur en console ({@link App}) et, plus tard, par l'interface
 * graphique : les deux obtiennent exactement le même système.</p>
 *
 * @param service point d'entrée pour l'interface (submit, revise, reroute, accept...)
 * @param bus     pour s'abonner aux échanges entre agents (journal, affichage)
 */
public record MailAssistant(
        AppConfig config,
        DefaultAssistantService service,
        MessageBus bus,
        RoutingAgent router,
        JsonInbox inbox,
        JsonAgentRepository agents,
        JsonContactDirectory contacts,
        ConversationMemory memory) {

    /**
     * @param llm             modèle utilisé pour rédiger (Ollama, ou StubLLMClient pour tester sans modèle)
     * @param llmTieBreaking  vrai pour faire départager les cas ambigus par le LLM
     */
    public static MailAssistant create(AppConfig config, LLMClient llm, boolean llmTieBreaking) {
        // Données (personne B)
        JsonAgentRepository agents = JsonAgentRepository.from(config);
        JsonContactDirectory contacts = JsonContactDirectory.from(config);
        JsonInbox inbox = JsonInbox.from(config);
        ConversationMemory memory = new InMemoryConversationMemory();   // version persistante à venir

        // Génération (personne B)
        ContextProvider context = new CompositeContextProvider(List.of(
                new FileContextProvider(config.agentsDir().resolveSibling("context"))));
        DraftGenerator generator = DefaultDraftGenerator.from(config, llm, context, memory);

        // Routing (personne A) : configuration « pondérée », la meilleure des mesures de Rania (90 %),
        // complétée par l'affinité de fil pour les réponses dans une conversation déjà commencée.
        RoutingStrategy strategy = WeightedRoutingStrategy.builder()
                .add(new KeywordRoutingStrategy(), 1.0)
                .add(new SenderRoutingStrategy(contacts), 0.6)
                .add(new MetadataRoutingStrategy(), 0.8)
                .add(new ThreadAffinityStrategy(memory, 10), 0.6)
                .build();
        RoutingStrategy tieBreaker = llmTieBreaking ? new LlmRoutingStrategy(llm) : null;

        // Système multi-agent (personne A)
        MessageBus bus = new SimpleMessageBus();
        RoutingAgent router = new RoutingAgent(bus, strategy, tieBreaker, RoutingAgent.Settings.from(config));
        DefaultAssistantService service = new DefaultAssistantService(bus, router, agents,
                new DefaultAgentFactory(generator), memory);

        return new MailAssistant(config, service, bus, router, inbox, agents, contacts, memory);
    }
}