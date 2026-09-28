# Mail MAS : assistant multi-agent de réponse aux e-mails (LLM local)

## Compiler et tester
    mvn test

Prérequis : JDK 21, Maven. (Section "Configurer le LLM local" à compléter par la personne B.)

## Contrats communs (à ne modifier qu'à deux)

| Package | Rôle | Implémenté par |
|---|---|---|
| `model` | Email, Contact, DraftReply, UserFeedback, ConversationEntry | commun |
| `agent` | Agent, AgentProfile, AgentMessage, Payload, MessageBus | A |
| `routing` | RoutingStrategy, RoutingDecision | A |
| `app` | AssistantService (appelé par l'UI) | A |
| `generation` | DraftGenerator (appelé par les agents) | B |
| `llm` | LLMClient + StubLLMClient | B |
| `context` | ContextProvider, Context | B |
| `memory` | ConversationMemory | B |
| `persistence` | AgentRepository, ContactDirectory | B |

Toute modification d'une interface de ces packages passe par une Pull Request relue par l'autre.

## Flux minimal
UI -> AssistantService.submit(email) -> RoutingAgent -> RoutingStrategy -> MessageBus
-> REQUEST(DraftRequest) -> agent spécialisé -> DraftGenerator -> LLMClient
-> PROPOSE(DraftProposal) -> RoutingAgent -> ProcessingResult -> UI
(rejet : AssistantService.revise -> nouvelle REQUEST avec le feedback)

## Configuration
Un agent = un fichier `config/agents/<id>.json` (system prompt, sources de contexte, indices de routing).
