# Mail MAS : assistant multi-agent de réponse aux e-mails (LLM local)

Système multi-agent en Java qui propose des réponses aux e-mails reçus. Un **agent de routage**
analyse chaque e-mail et le confie à l'**agent spécialisé** le plus adapté (étudiants, collègues,
administration, projet SuperMASProject, ou agent de repli). L'agent rédige une proposition avec un
**LLM exécuté localement** (Ollama), à partir de son propre contexte et de l'historique du fil.
**Rien n'est envoyé sans validation de l'utilisateur**, qui peut valider, modifier, rejeter avec un
commentaire ou confier l'e-mail à un autre agent, depuis une interface graphique JavaFX.

Projet du Master 2 Intelligence Artificielle, réalisé en binôme :
- **Personne A (Rania)** : cœur multi-agent et routing ;
- **Personne B (Ibrahima)** : LLM local, contexte, mémoire, persistance, interface.

---

## 1. Installation

| Outil | Version | Remarque |
|---|---|---|
| JDK | **21** | Par exemple Eclipse Temurin 21 (adoptium.net). Vérifier avec `java -version`. |
| Maven | inutile | Le projet contient le Maven Wrapper (`mvnw`, `mvnw.cmd`). JavaFX est téléchargé par Maven. |
| Ollama | 0.20 ou plus | ollama.com/download |
| Modèle | `qwen3.5:4b` | `ollama pull qwen3.5:4b` (environ 3,4 Go) |
| Polices (facultatif) | — | `SourceSans3-Regular.ttf`, `SourceSans3-Bold.ttf`, `JetBrainsMono-Regular.ttf`, `JetBrainsMono-Bold.ttf` dans `src/main/resources/fonts/`. Sans elles, l'interface utilise Segoe UI et Consolas. |

Vérifier qu'Ollama répond : ouvrir http://localhost:11434/api/tags dans un navigateur.

> **Windows** : lancer les commandes depuis **PowerShell** (pas WSL : depuis WSL, `localhost`
> ne désigne pas la machine Windows où tourne Ollama). Pour afficher correctement les accents
> dans la console :
> ```powershell
> chcp 65001
> [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
> ```

## 2. Lancer

### Interface graphique

```powershell
# avec le modèle local (Ollama doit être lancé)
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=fr.m2ia.mailmas.ui.Launcher"

# mode test, sans modèle (réponses factices, instantanées)
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=fr.m2ia.mailmas.ui.Launcher" "-Dexec.args=--stub"
```

La pastille de l'en-tête indique l'état du modèle : verte (Ollama joignable), orange (mode test),
rouge (Ollama injoignable : les propositions échouent avec un message clair et un bouton « Réessayer »).

### Démonstrateur en console

```powershell
.\mvnw.cmd -q compile exec:java                                # les e-mails de la boîte, avec Ollama
.\mvnw.cmd -q compile exec:java "-Dexec.args=001 005"          # seulement ces e-mails
.\mvnw.cmd -q compile exec:java "-Dexec.args=--stub"           # sans modèle
.\mvnw.cmd -q compile exec:java "-Dexec.args=--reset 001"      # efface d'abord l'historique enregistré
.\mvnw.cmd -q compile exec:java "-Dexec.args=--fresh 001"      # mémoire temporaire, rien n'est écrit
```

Pour chaque e-mail : les échanges entre agents (`[bus] REQUEST router -> student`), le classement du
routing avec sa justification, l'agent retenu (et si le cas est ambigu), puis la proposition.

### Tests

```powershell
.\mvnw.cmd clean test
```

123 tests. Les deux tests qui appellent le vrai modèle (`OllamaLLMClientTest`, `SuperMasScenarioTest`)
sont **ignorés automatiquement** si Ollama n'est pas lancé.

---

## 3. L'interface

Trois écrans, accessibles depuis l'en-tête.

| Écran | Contenu |
|---|---|
| **Boîte de réception** | À gauche, les e-mails avec l'agent retenu et leur état (à traiter, en rédaction, proposition prête, ambigu, validé). Au centre, l'e-mail et la proposition, modifiable, avec les boutons **Valider**, **Rejeter avec un commentaire** (le même agent réécrit, proposition n°2) et **Changer d'agent** ; un bandeau signale les cas ambigus et propose de confier l'e-mail à l'autre agent. À droite, l'**explication** : scores du routing et leurs justifications, documents de contexte utilisés, journal des échanges entre agents. |
| **Agents** | Créer, modifier ou supprimer un agent : nom, description, mots-clés, adresses, rôles, agent de repli, **consignes (system prompt)**, fichiers de contexte. « Enregistrer et recharger » applique les changements **sans redémarrer**. |
| **Contacts** | L'annuaire utilisé par le routing. Les rôles proposés sont ceux que déclarent les agents (on ne peut pas créer un rôle qu'aucun agent ne reconnaît). |

Cliquer sur un e-mail lance automatiquement son traitement. Un appel au modèle prend de 20 à 60 s :
il passe par un thread de travail dédié, l'interface reste utilisable et affiche le temps écoulé.

---

## 4. Architecture

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
qui crée les classes concrètes et les relie. La démo console et l'interface graphique l'utilisent
toutes deux, et pilotent donc exactement le même système.

### Design patterns

| Pattern | Où | Besoin |
|---|---|---|
| Mediator | `SimpleMessageBus` | Les agents ne se référencent jamais entre eux |
| Observer | `MessageListener` | Le journal de l'interface suit en direct les échanges entre agents |
| Strategy | `RoutingStrategy` (mots-clés, expéditeur, métadonnées, fil, LLM) | Changer et comparer les méthodes de routing |
| Composite | `WeightedRoutingStrategy`, `CompositeContextProvider` | Combiner des stratégies / des sources de contexte |
| Facade | `AssistantService`, `DraftGenerator` | Frontières simples entre interface, partie A et partie B |
| Adapter | `OllamaLLMClient` | Changer de moteur LLM sans toucher aux agents |
| Repository | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox`, `FileConversationMemory` | Persistance indépendante du format |
| Factory | `DefaultAgentFactory` | Profil → agent |

---

## 5. Organisation du code

| Package | Contenu | Responsable |
|---|---|---|
| `model` | `Email`, `Contact`, `DraftReply`, `UserFeedback`, `ConversationEntry` | commun |
| `config` | `AppConfig` (lecture de `app.properties`) | commun |
| *(racine)* | `MailAssistant` (racine de composition), `App` (démonstrateur console) | commun |
| `agent` | `SimpleMessageBus`, `SpecializedAgent`, `RoutingAgent`, `DefaultAgentFactory` | A |
| `routing` | `KeywordRoutingStrategy`, `SenderRoutingStrategy`, `MetadataRoutingStrategy`, `ThreadAffinityStrategy`, `LlmRoutingStrategy`, `WeightedRoutingStrategy` | A |
| `app` | `DefaultAssistantService` (submit, revise, reroute, accept, reloadAgents) | A |
| `llm` | `OllamaLLMClient`, `StubLLMClient` | B |
| `generation` | `DefaultDraftGenerator`, `PromptBuilder` | B |
| `context` | `FileContextProvider`, `CompositeContextProvider`, `Context` (budget de tokens) | B |
| `memory` | `FileConversationMemory` (historique sur disque), `InMemoryConversationMemory` (tests) | B |
| `persistence` | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox` | B |
| `ui` | `MailMasApp`, `MainController`, `InboxPane`, `EmailPane`, `ExplainPane`, `AgentsPane`, `ContactsPane` | B |

Les **interfaces** de chaque package sont des contrats communs : toute modification passe par
une Pull Request relue par l'autre membre du binôme.

Documentation détaillée de la partie A : [`docs/partie-A-routing.md`](docs/partie-A-routing.md).

---

## 6. Configuration et données

Tout se modifie **sans recompiler**, à la main ou depuis l'interface.

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
| `config/agents/<id>.md` | **Consignes** de l'agent (system prompt) |
| `config/context/<agent>/*.md` | **Contexte** de l'agent : uniquement des **faits** (dates, membres, procédures), jamais des consignes |
| `config/contacts.json` | Annuaire : adresse, nom, rôles (`student`, `teacher`, `research-partner`...) |
| `config/inbox/*.json` | Boîte de réception simulée (un fichier par e-mail, date au format `2026-09-28T09:15:00Z`) |
| `data/conversations/<fil>.jsonl` | **Historique des fils** (reçus, propositions, réponses validées), une entrée par ligne. **Exclu de Git** : il contient des e-mails. |

Un dossier de contexte vide contient un fichier `.gitkeep`, ignoré par le `FileContextProvider`
(qui ne lit que les `.md` et `.txt`).

### Ajouter un agent

Depuis l'écran **Agents** : « Nouvel agent », remplir le formulaire, « Créer l'agent ». Le dossier
`config/context/<id>/` est créé ; y déposer les documents de l'agent. À la main : créer
`config/agents/<id>.json` et `<id>.md`, puis redémarrer. Ajouter si besoin le rôle correspondant
aux contacts concernés (écran **Contacts**).

### Changer de modèle

`ollama pull <modèle>` puis modifier `llm.model`. Pour un autre moteur qu'Ollama (llama.cpp,
LM Studio...), écrire une nouvelle implémentation de `LLMClient` et la brancher dans `MailAssistant`.

---

## 7. Décisions de conception

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
| 10 | Agent créé ou modifié dans l'interface | `AssistantService.reloadAgents()` | Pas de redémarrage |
| 11 | Réponse modifiée puis validée | `DraftReply.withBody()` : on enregistre le texte réellement envoyé | Mémoire fidèle |
| 12 | Appels bloquants | L'interface appelle le service dans un thread de travail | Interface fluide |
| 13 | Configuration | `config/app.properties` lu par `AppConfig` | Changer de modèle sans recompiler |
| 14 | Combinaison des stratégies | « OU probabiliste » pondéré : `1 − Π(1 − wᵢ·sᵢ)` (mots-clés 1,0, expéditeur 0,6, métadonnées 0,8, fil 0,6) | Un indice fort suffit, des indices concordants se renforcent ; meilleure exactitude mesurée (90 %) |
| 15 | Contexte et consignes | Contexte = faits transmissibles ; consignes = system prompt | Une consigne placée dans le contexte est recopiée dans la réponse par le modèle |
| 16 | Construction du prompt | Règles communes à tous les agents + consignes de l'agent (prioritaires pour la langue et le ton) | Un nouvel agent hérite automatiquement des garde-fous |
| 17 | Injection de prompt | E-mail encadré (`<<<DEBUT_EMAIL ... FIN_EMAIL>>>`) et signalé comme donnée à ne pas exécuter | Un e-mail ne doit pas pouvoir modifier le comportement de l'agent |
| 18 | Isolation des contextes | Un agent ne lit que les sources de son profil, et jamais hors de `config/context/` | Confidentialité entre agents |
| 19 | Mode « thinking » du modèle | Désactivé (`think: false`), fenêtre de contexte fixée (`num_ctx` 8192) | Réponses bien plus rapides ; pas de troncature silencieuse du prompt |
| 20 | Racine de composition | `MailAssistant` crée et relie toutes les pièces | Console et interface obtiennent exactement le même système |
| 21 | Format de la mémoire | Un fichier JSON Lines par fil, **ajout seulement** ; une ligne abîmée est ignorée | Un arrêt brutal ne peut pas corrompre l'historique |
| 22 | Confidentialité de l'historique | `data/` exclu de Git ; tests sur une mémoire temporaire | Les e-mails ne quittent pas la machine ; tests reproductibles |
| 23 | Annuaire des contacts | `config/contacts.json`, adresses insensibles à la casse, rôles en minuscules, écriture atomique | Le routing reconnaît un expéditeur dont l'adresse ne correspond à aucun motif |
| 24 | Dates des e-mails | Converties par `JsonInbox` (ISO-8601) | Pas de module Jackson supplémentaire |
| 25 | Appels au modèle depuis l'interface | Un **seul** thread de travail ; résultats renvoyés par `Platform.runLater` | Interface jamais gelée, modèle local jamais sollicité deux fois en même temps |
| 26 | Explicabilité | Scores, justifications, contexte utilisé et journal du bus affichés à côté de chaque proposition | L'utilisateur comprend et peut corriger le choix de l'agent |
| 27 | Rôles des contacts dans l'interface | Cases à cocher générées à partir des rôles déclarés par les agents | Pas de rôle orphelin qu'aucun agent ne reconnaîtrait |
| 28 | Identifiants (agents, fils) | Vérifiés ou convertis avant de servir de nom de fichier | Pas d'écriture hors des dossiers prévus (`../../`) |

---

## 8. Résultats

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
(« Stage et SuperMAS », d'un collègue) est détecté comme ambigu (0,78 contre 0,76) et départagé par
le LLM. Avec la mémoire persistante, un nouvel e-mail dans un fil déjà traité est renforcé par
l'affinité de fil (e-mail 001 : 0,97 → 0,98).

### Génération (e-mail SuperMAS d'Anna, `qwen3.5:4b`, processeur sans GPU dédié)

| Version du prompt | Effet observé |
|---|---|
| v0 : aucune consigne | Plusieurs modèles de réponse, heure inventée (« 17h00 ») |
| v1 : règles communes + consignes de l'agent | Bonne date avec contexte ; mais fuite de notes internes et refus |
| v2 : « ni accepter ni refuser », « notes internes » | Plus de refus ; la fuite persiste |
| v3 : faits seulement dans le contexte | Plus de fuite ; réponse neutre sur l'engagement |
| v4 : langue### Agents étudiant, collègues, administration (e-mails 002 à 004, avec contexte)

| E-mail | Premier essai | Problème | Correction | Résultat |
|---|---|---|---|---|
| 002 (TP3) | Correct : code + rapport de 4 pages, Moodle | — | — | Correct |
| 003 (jury) | « Je ne peux pas confirmer » malgré un jeudi après-midi libre | Règle « pas de nouvelle charge » généralisée à la réunion ; « 14h = après-midi » non déduit | Consigne positive sur les créneaux ; disponibilités écrites heure par heure | Créneau accepté, copies traitées |
| 004 (mission) | Références et pièces manquantes exactes, mais « pièces en règle » inventé | Puis « ci-joint le formulaire signé » : la consigne de l'agent parlait de « pièces jointes » | Consigne réécrite avec une phrase modèle ; règle commune « n'affirme jamais avoir joint ou signé » | Pièces annoncées au futur |

Enseignements : un petit modèle ne fait pas de déduction sur le contexte. Il faut écrire les faits sous
la forme exacte utile (« libre à 14h »), et une phrase modèle est mieux suivie qu'une interdiction.
Une même configuration peut donner une réponse correcte puis une invention : chaque cas est évalué sur
plusieurs essais. de l'e-mail, prudence élargie | 005 en français ; mais sur-correction : la date disparaît pour 001 |
| v5 : consigne positive pour les faits, prudence limitée aux engagements | 001 correct ; 005 reprend encore une proposition de l'expéditeur (« comme convenu ») |

Temps : de 8 s (prompt minimal) à 35-50 s (contexte complet, ou départage par LLM). Un contexte
sobre réduit sensiblement le temps de réponse.

Enseignements : un petit modèle local applique mal les consignes conditionnelles et généralise trop
les consignes de prudence. Le prompt réduit les erreurs sans les supprimer : la **validation
humaine** et la **révision avec commentaire** restent indispensables, et l'interface les rend simples.

---

## 9. Tests

123 tests (JUnit 5) : contrats communs, bus et agents, stratégies et scénarios de routing, service
(cycle submit / revise / reroute / accept), client Ollama, repositories JSON (agents, contacts,
boîte de réception), mémoire persistante, contexte, génération du prompt, et un test de bout en bout
de la vraie configuration avec un faux LLM (`MailAssistantTest`).

## 10. Organisation Git

- `main` est protégée : toute modification passe par une Pull Request relue par l'autre membre.
- Une branche par fonctionnalité (`feature/...`), fusionnée dès qu'elle est terminée et testée.
- Les fichiers communs (`model/`, interfaces, `MailAssistant`, `pom.xml`, `README.md`) ne sont
  modifiés qu'après accord.
- `data/` (historique des conversations) n'est jamais commité.

## 11. Prochaines étapes

- **Sources d'e-mails** : une interface `MailSource` avec plusieurs implémentations — formulaire
  « Nouvel e-mail » dans l'interface, import de fichiers `.eml` (fils reconstitués avec
  `In-Reply-To` / `References`, `List-Id` pour la stratégie « métadonnées »), et éventuellement IMAP
  en lecture seule.
- Documents de contexte (faits) pour les agents `student`, `teacher` et `administration`.
- Évaluation systématique des réponses sur les 6 e-mails (3 essais chacun) : langue, faits,
  inventions, engagements, fuites, temps.
- Expériences : auto-évaluation des agents par le LLM, comparaison avec un modèle plus grand
  (`qwen3.5:9b`), RAG par embeddings locaux.Gestion des agents et des contacts depuis l'interface.
- Expériences : auto-évaluation des agents par le LLM, comparaison avec un modèle plus grand
  (`qwen3.5:9b`), RAG par embeddings locaux.                          |


.\mvnw.cmd clean test