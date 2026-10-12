# Mail MAS : assistant multi-agent de réponse aux e-mails (LLM local)

Système multi-agent en Java qui propose des réponses aux e-mails reçus. Un **agent de routage**
analyse chaque e-mail et le confie à l'**agent spécialisé** le plus adapté (étudiants, collègues,
administration, projet SuperMASProject, ou agent de repli). L'agent rédige une proposition avec un
**LLM exécuté localement** (Ollama), à partir de son propre contexte et de l'historique du fil.
**Rien n'est envoyé sans validation de l'utilisateur**, qui peut valider, modifier, rejeter avec un
commentaire ou choisir un autre agent.

Projet du Master 2 Intelligence Artificielle, réalisé en binôme :
- **Personne A (Rania)** : cœur multi-agent et routing ;
- **Personne B (Ibrahima)** : LLM local, contexte, mémoire, persistance, interface.

---

## 1. Installation

| Outil | Version | Remarque |
|---|---|---|
| JDK | **21** | Par exemple Eclipse Temurin 21 (adoptium.net). Vérifier avec `java -version`. |
| Maven | inutile | Le projet contient le Maven Wrapper (`mvnw`, `mvnw.cmd`). |
| Ollama | 0.20 ou plus | ollama.com/download |
| Modèle | `qwen3.5:4b` | `ollama pull qwen3.5:4b` (environ 3,4 Go) |

Vérifier qu'Ollama répond : ouvrir http://localhost:11434/api/tags dans un navigateur.

> **Windows** : lancer les commandes depuis **PowerShell** (pas WSL : depuis WSL, `localhost`
> ne désigne pas la machine Windows où tourne Ollama). Pour afficher correctement les accents :
> ```powershell
> chcp 65001
> [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
> ```

## 2. Lancer

```powershell
.\mvnw.cmd clean test                                       # 115 tests
.\mvnw.cmd -q compile exec:java "-Dexec.args=--stub"        # démo sans modèle (quelques secondes)
.\mvnw.cmd -q compile exec:java "-Dexec.args=001 005"       # démo avec Ollama sur deux e-mails
.\mvnw.cmd -q compile exec:java                             # les 6 e-mails de la boîte (~4 min)
```

Les tests qui appellent le vrai modèle (`OllamaLLMClientTest`, `SuperMasScenarioTest`) sont
**ignorés automatiquement** si Ollama n'est pas lancé.

Pour chaque e-mail, la démo affiche les échanges entre agents (`[bus] REQUEST router -> student`),
le classement du routing avec sa justification, l'agent retenu (et si le cas est ambigu), puis la
proposition de réponse.

---

## 3. Architecture

### Organisation multi-agent

Organisation **centralisée coordinateur-spécialistes**. Les agents ne se connaissent pas : ils
échangent des messages typés inspirés de FIPA-ACL (`REQUEST`, `PROPOSE`, `REFUSE`, `FAILURE`) par
un bus (Mediator). Un agent peut **refuser** une demande ; le coordinateur délègue alors au
candidat suivant, puis à l'agent de repli.

```
Interface / App ──► AssistantService ──► RoutingAgent ──► RoutingStrategy (classement + justification)
                                              │
                                              ▼  REQUEST (MessageBus)
                                      Agent spécialisé ──► DraftGenerator ──► contexte + historique
                                              │                                + prompt ──► LLM local
                                              ▼  PROPOSE / REFUSE / FAILURE
                                     ProcessingResult ──► utilisateur : valider / modifier /
                                                                      rejeter (revise) / changer d'agent (reroute)
```

### Architecture logicielle

Architecture en couches de type **ports et adaptateurs** : le cœur ne dépend que d'interfaces
(les contrats communs) ; les détails techniques (Ollama, fichiers JSON, interface) sont des
adaptateurs remplaçables. `MailAssistant` est la **racine de composition** : c'est le seul endroit
qui crée les classes concrètes et les relie. La démo console et l'interface l'utilisent toutes deux.

### Design patterns

| Pattern | Où | Besoin |
|---|---|---|
| Mediator | `SimpleMessageBus` | Les agents ne se référencent jamais entre eux |
| Observer | `MessageListener` | Journal et interface suivent les échanges |
| Strategy | `RoutingStrategy` (mots-clés, expéditeur, métadonnées, fil, LLM) | Changer et comparer les méthodes de routing |
| Composite | `WeightedRoutingStrategy`, `CompositeContextProvider` | Combiner des stratégies / des sources de contexte |
| Facade | `AssistantService`, `DraftGenerator` | Frontières simples entre UI, partie A et partie B |
| Adapter | `OllamaLLMClient` | Changer de moteur LLM sans toucher aux agents |
| Repository | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox` | Persistance indépendante du format |
| Factory | `DefaultAgentFactory` | Profil → agent |

---

## 4. Organisation du code

| Package | Contenu | Responsable |
|---|---|---|
| `model` | `Email`, `Contact`, `DraftReply`, `UserFeedback`, `ConversationEntry` | commun |
| `config` | `AppConfig` (lecture de `app.properties`) | commun |
| *(racine)* | `MailAssistant` (racine de composition), `App` (démo console) | commun |
| `agent` | `SimpleMessageBus`, `SpecializedAgent`, `RoutingAgent`, `DefaultAgentFactory` | A |
| `routing` | `KeywordRoutingStrategy`, `SenderRoutingStrategy`, `MetadataRoutingStrategy`, `ThreadAffinityStrategy`, `LlmRoutingStrategy`, `WeightedRoutingStrategy` | A |
| `app` | `DefaultAssistantService` (submit, revise, reroute, accept, reloadAgents) | A |
| `llm` | `OllamaLLMClient`, `StubLLMClient` | B |
| `generation` | `DefaultDraftGenerator`, `PromptBuilder` | B |
| `context` | `FileContextProvider`, `CompositeContextProvider`, `Context` (budget de tokens) | B |
| `memory` | `InMemoryConversationMemory` | B |
| `persistence` | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox` | B |

Les **interfaces** de chaque package sont des contrats communs : toute modification passe par
une Pull Request relue par l'autre membre du binôme.

Documentation détaillée de la partie A : [`docs/partie-A-routing.md`](docs/partie-A-routing.md).

---

## 5. Configuration

Tout se modifie **sans recompiler**.

### `config/app.properties`

| Clé | Défaut | Rôle |
|---|---|---|
| `llm.url` | `http://localhost:11434` | Adresse d'Ollama |
| `llm.model` | `qwen3.5:4b` | Modèle utilisé |
| `llm.timeoutSeconds` | `90` | Délai maximal d'une génération |
| `routing.threshold` | `0.4` | Sous ce score : agent de repli |
| `routing.ambiguityMargin` | `0.1` | Écart sous lequel deux agents sont « ambigus » |
| `routing.maxAttempts` | `3` | Agents essayés au maximum en cas de refus |
| `context.tokenBudget` | `1500` | Taille maximale du contexte envoyé au LLM |
| `memory.maxHistoryEntries` | `6` | Messages du fil envoyés au LLM |
| `paths.agents` / `paths.inbox` | `config/agents` / `config/inbox` | Emplacements |

### Fichiers

| Fichier | Contenu |
|---|---|
| `config/agents/<id>.json` | Profil d'un agent : nom, description, sources de contexte, indices de routing (adresses, rôles, mots-clés), repli |
| `config/agents/<id>.md` | **System prompt** de l'agent (consignes de comportement) |
| `config/context/<agent>/*.md` | **Contexte** de l'agent : uniquement des **faits** (dates, membres, procédures), jamais des consignes |
| `config/contacts.json` | Annuaire : adresse, nom, rôles (`student`, `teacher`, `research-partner`...) |
| `config/inbox/*.json` | Boîte de réception simulée (un fichier par e-mail, date au format `2026-09-28T09:15:00Z`) |

Un dossier de contexte vide contient un fichier `.gitkeep` (ignoré par le `FileContextProvider`,
qui ne lit que les `.md` et `.txt`).

### Ajouter un agent

1. Créer `config/agents/<id>.json` (identifiant en minuscules, chiffres et tirets) et `<id>.md`.
2. Créer `config/context/<id>/` et y placer ses documents.
3. Ajouter si besoin les rôles correspondants dans `config/contacts.json`.
4. Redémarrer, ou appeler `AssistantService.reloadAgents()` depuis l'interface.

### Changer de modèle

`ollama pull <modèle>` puis modifier `llm.model`. Pour un autre moteur que Ollama (llama.cpp,
LM Studio...), écrire une nouvelle implémentation de `LLMClient` et la brancher dans `MailAssistant`.

---

## 6. Décisions de conception

| # | Question | Décision | Raison |
|---|---|---|---|
| 1 | Source des e-mails | Fichiers JSON dans `config/inbox/` ; « envoyer » = enregistrer comme envoyé | Le sujet n'exige pas de vraie messagerie ; l'utilisateur garde le contrôle |
| 2 | Qui écrit dans la mémoire | `AssistantService` uniquement ; `DraftGenerator` lit | Éviter les doublons, un seul responsable |
| 3 | Échec (LLM indisponible, agent inconnu) | Message `FAILURE`, puis `ProcessingResult` au statut `FAILED` avec un message ; un échec arrête la délégation | Les autres agents utilisent le même LLM et échoueraient aussi |
| 4 | Refus d'un agent | Candidat suivant au-dessus du seuil, puis agent de repli (essais bornés) | Délégation entre agents, pas d'impasse |
| 5 | Cas ambigu | Meilleur agent choisi, ambiguïté signalée ; départage par LLM limité aux ex-aequo ; l'utilisateur peut changer d'agent (`reroute`) | Le LLM n'est appelé pour le routing que dans les cas difficiles |
| 6 | Aucun agent adapté | Agent de repli (`fallback = true`) si le meilleur score est sous le seuil | Toujours une proposition |
| 7 | System prompt | Fichier `.md` séparé par agent | Plus facile à modifier qu'une chaîne JSON |
| 8 | Informations du routing | Contacts et historique reçus par le constructeur de la stratégie | `route()` reste stable |
| 9 | Échelle des scores | [0, 1], même sens pour toutes les stratégies | Combinaison possible |
| 10 | Nouvel agent créé dans l'UI | `AssistantService.reloadAgents()` | Pas de redémarrage |
| 11 | Réponse modifiée puis validée | `DraftReply.withBody()` : on enregistre le texte réellement envoyé | Mémoire fidèle |
| 12 | Appels bloquants | L'UI appelle le service dans un thread d'arrière-plan | Interface fluide |
| 13 | Configuration | `config/app.properties` lu par `AppConfig` | Changer de modèle sans recompiler |
| 14 | Combinaison des stratégies | « OU probabiliste » pondéré : `1 − Π(1 − wᵢ·sᵢ)` (mots-clés 1,0, expéditeur 0,6, métadonnées 0,8, fil 0,6) | Un indice fort suffit, des indices concordants se renforcent ; meilleure exactitude mesurée (90 %) |
| 15 | Contexte et consignes | Contexte = faits transmissibles ; consignes = system prompt | Une consigne placée dans le contexte est recopiée dans la réponse par le modèle |
| 16 | Construction du prompt | Règles communes à tous les agents + consignes de l'agent (prioritaires pour la langue et le ton) | Un nouvel agent hérite automatiquement des garde-fous |
| 17 | Injection de prompt | E-mail encadré (`<<<DEBUT_EMAIL ... FIN_EMAIL>>>`) et signalé comme donnée à ne pas exécuter | Un e-mail ne doit pas pouvoir modifier le comportement de l'agent |
| 18 | Isolation des contextes | Un agent ne lit que les sources de son profil, et jamais hors de `config/context/` | Confidentialité entre agents |
| 19 | Mode « thinking » du modèle | Désactivé (`think: false`), fenêtre de contexte fixée (`num_ctx` 8192) | Réponses bien plus rapides ; pas de troncature silencieuse du prompt |
| 20 | Racine de composition | `MailAssistant` crée et relie toutes les pièces | Démo et interface obtiennent exactement le même système |

---

## 7. Résultats

### Routing (21 e-mails étiquetés, `RoutingScenariosTest`)

| Configuration | Exactitude |
|---|---|
| Mots-clés seuls | 76 % |
| Expéditeur seul | 86 % |
| **Combinaison pondérée** | **90 %** |
| Pondérée + refus par règles | 76 % |

Aucun indice seul ne suffit. Le refus par mots-clés corrige un cas (« un étudiant connu vend son
vélo ») mais en casse trois (messages courts sans mot-clé) : seule une lecture du sens les distingue.

Sur la boîte de réception du sujet, les 6 scénarios sont traités correctement ; l'e-mail 005
(« Stage et SuperMAS », d'un collègue) est détecté comme ambigu (0,78 contre 0,76) et départagé par le LLM.

### Génération (e-mail SuperMAS d'Anna, `qwen3.5:4b`, processeur sans GPU dédié)

| Version du prompt | Effet observé |
|---|---|
| v0 : aucune consigne | Plusieurs modèles de réponse, heure inventée (« 17h00 ») |
| v1 : règles communes + consignes de l'agent | Bonne date avec contexte ; mais fuite de notes internes et refus |
| v3 : faits seulement dans le contexte | Plus de fuite ; réponse neutre sur l'engagement |
| v4 : consigne de prudence élargie | Sur-correction : la date disparaît |
| v5 : consigne positive pour les faits, prudence limitée aux engagements | Réponse correcte pour 001 ; 005 reprend encore une proposition de l'expéditeur |

Temps : de 8 s (prompt minimal) à 35-50 s (contexte complet, ou départage par LLM). Un contexte
sobre réduit sensiblement le temps de réponse.

Enseignements : un petit modèle local applique mal les consignes conditionnelles et généralise trop
les consignes de prudence. Le prompt réduit les erreurs sans les supprimer : la **validation
humaine** (et la révision avec commentaire) reste indispensable.

---

## 8. Tests

115 tests (JUnit 5) : contrats communs, bus et agents, stratégies de routing, scénarios de routing,
service (cycle submit / revise / reroute / accept), client Ollama, repositories JSON, contexte,
génération du prompt, et un test de bout en bout de la vraie configuration avec un faux LLM
(`MailAssistantTest`).

## 9. Organisation Git

- `main` est protégée : toute modification passe par une Pull Request relue par l'autre membre.
- Une branche par fonctionnalité (`feature/...`), fusionnée dès qu'elle est terminée et testée.
- Les fichiers communs (`model/`, interfaces, `MailAssistant`, `pom.xml`, `README.md`) ne sont
  modifiés qu'après accord.

## 10. Prochaines étapes

- Interface graphique : boîte de réception, routing expliqué, proposition, boutons Valider /
  Rejeter avec commentaire / Changer d'agent, journal des échanges entre agents.
- Mémoire persistante des conversations (l'historique survit au redémarrage).
- Gestion des agents et des contacts depuis l'interface.
- Expériences : auto-évaluation des agents par le LLM, comparaison avec un modèle plus grand
  (`qwen3.5:9b`), RAG par embeddings locaux.                          |


.\mvnw.cmd clean test