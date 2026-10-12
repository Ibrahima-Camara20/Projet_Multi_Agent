# Mail MAS : assistant multi-agent de réponse aux e-mails (LLM local)

## Compiler et tester
    mvn test

Prérequis : JDK 21, Maven. (Section "Configurer le LLM local" à compléter par la personne B.)

## Contrats communs (à ne modifier qu'à deux)

| Package       | Rôle                                                        | Implémenté par |
| ---------------| -------------------------------------------------------------| ----------------|
| `config`      | AppConfig (lecture de app.properties)                       | commun         |
| `model`       | Email, Contact, DraftReply, UserFeedback, ConversationEntry | commun         |
| `agent`       | Agent, AgentProfile, AgentMessage, Payload, MessageBus      | A              |
| `routing`     | RoutingStrategy, RoutingDecision                            | A              |
| `app`         | AssistantService (appelé par l'UI)                          | A              |
| `generation`  | DraftGenerator (appelé par les agents)                      | B              |
| `llm`         | LLMClient + StubLLMClient                                   | B              |
| `context`     | ContextProvider, Context                                    | B              |
| `memory`      | ConversationMemory                                          | B              |
| `persistence` | AgentRepository, ContactDirectory                           | B              |

Toute modification d'une interface de ces packages passe par une Pull Request relue par l'autre.

## Flux minimal
UI -> AssistantService.submit(email) -> RoutingAgent -> RoutingStrategy -> MessageBus
-> REQUEST(DraftRequest) -> agent spécialisé -> DraftGenerator -> LLMClient
-> PROPOSE(DraftProposal) -> RoutingAgent -> ProcessingResult -> UI
(rejet : AssistantService.revise -> nouvelle REQUEST avec le feedback)

## Configuration
- `config/app.properties` : LLM local (URL, modèle, timeout), seuils de routing, budget de contexte.
- `config/agents/<id>.json` : profil d'un agent (sources de contexte, indices de routing, repli).
- `config/agents/<id>.md` : son system prompt, modifiable sans toucher au code.
- `config/inbox/*.json` : boîte de réception simulée.

## Décisions de conception
| #   | Question                                | Décision                                                                                  | Raison                                                                     |
| -----| -----------------------------------------| -------------------------------------------------------------------------------------------| ----------------------------------------------------------------------------|
| 1   | Source des e-mails                      | Fichiers JSON dans `config/inbox/` ; « envoyer » = enregistrer comme envoyé               | Le sujet n'exige pas de vraie messagerie ; l'utilisateur garde le contrôle |
| 2   | Qui écrit dans la mémoire               | `AssistantService` uniquement ; `DraftGenerator` lit                                      | Éviter les doublons, un seul responsable                                   |
| 3   | Échec (LLM indisponible, agent inconnu) | Message `FAILURE`, puis `ProcessingResult` au statut `FAILED` avec un message             | L'interface peut expliquer l'erreur                                        |
| 4   | Refus d'un agent                        | Candidat suivant au-dessus du seuil, puis agent de repli (essais bornés)                  | Délégation entre agents, pas d'impasse                                     |
| 5   | Cas ambigu                              | Meilleur agent choisi, ambiguïté signalée, l'utilisateur peut changer d'agent (`reroute`) | Simple, et l'utilisateur garde la main ; départage par LLM en extension    |
| 6   | Aucun agent adapté                      | Agent de repli (`fallback = true`) si le meilleur score est sous le seuil                 | Toujours une proposition                                                   |
| 7   | System prompt                           | Fichier `.md` séparé par agent                                                            | Plus facile à modifier qu'une chaîne JSON                                  |
| 8   | Informations du routing                 | Contacts et historique reçus par le constructeur de la stratégie                          | `route()` reste stable                                                     |
| 9   | Échelle des scores                      | [0, 1], même sens pour toutes les stratégies                                              | Combinaison possible                                                       |
| 10  | Nouvel agent créé dans l'UI             | `AssistantService.reloadAgents()`                                                         | Pas de redémarrage                                                         |
| 11  | Réponse modifiée puis validée           | `DraftReply.withBody()` : on enregistre le texte réellement envoyé                        | Mémoire fidèle                                                             |
| 12  | Appels bloquants                        | L'UI appelle le service dans un thread d'arrière-plan                                     | Interface fluide                                                           |
| 13  | Configuration                           | `config/app.properties` lu par `AppConfig`                                                | Changer de modèle sans recompiler                                          |


.\mvnw.cmd clean test