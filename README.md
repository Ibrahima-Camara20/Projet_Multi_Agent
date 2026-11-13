# Mail MAS : assistant multi-agent de réponse aux e-mails (LLM local)

Système multi-agent en Java qui propose des réponses aux e-mails reçus. Un **agent de routage**
analyse chaque e-mail et le confie à l'**agent spécialisé** le plus adapté (étudiants, collègues,
administration, projet SuperMASProject, ou agent de repli). L'agent rédige une proposition avec un
**LLM exécuté localement** (Ollama), à partir de son propre contexte et de l'historique du fil.
Un **agent critique** relit chaque proposition avant l'utilisateur. **Rien n'est envoyé sans
validation de l'utilisateur**, qui peut valider, modifier, rejeter avec un commentaire ou confier
l'e-mail à un autre agent, depuis une interface graphique JavaFX.

Projet du Master 2 Intelligence Artificielle, réalisé en binôme :
- **Personne A (Rania)** : cœur multi-agent et routing ;
- **Personne B (Ibrahima)** : LLM local, contexte, mémoire, persistance, interface, agent critique,
  réputation, expériences.

Rapport : [`docs/rapport/`](docs/rapport/).

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

Pour chaque e-mail : les échanges entre agents (`[bus] REQUEST router -> student`,
`[bus] INFORM critic -> router`), le classement du routing avec sa justification, l'agent retenu
(et si le cas est ambigu), la proposition, puis les remarques de l'agent critique.

### Tests

```powershell
.\mvnw.cmd clean test
```

161 tests. Les tests qui appellent le vrai modèle (`OllamaLLMClientTest`, `SuperMasScenarioTest`)
sont **ignorés automatiquement** si Ollama n'est pas lancé ; les expériences ne se lancent qu'à la
demande (section 9).

---

## 3. L'interface

Trois écrans, accessibles depuis l'en-tête.

| Écran | Contenu |
|---|---|
| **Boîte de réception** | À gauche, les e-mails avec l'agent retenu et leur état ; boutons **Nouveau** (formulaire pour saisir ou coller un e-mail, éventuellement « en réponse à » un e-mail existant) et **Importer** (fichiers `.eml` ou `.json`). Au centre, l'e-mail et la proposition, modifiable, avec **Valider**, **Rejeter avec un commentaire** (le même agent réécrit) et **Changer d'agent** ; un bandeau signale les cas ambigus, un autre les remarques de l'**agent critique** (orange : réécriture ou point à vérifier ; rouge : possible manipulation). À droite, l'**explication** : scores du routing et leurs justifications, documents de contexte utilisés, journal des échanges entre agents. |
| **Agents** | Créer, modifier ou supprimer un agent : nom, description, mots-clés, adresses, rôles, agent de repli, **consignes (system prompt)**, fichiers de contexte. « Enregistrer et recharger » applique les changements **sans redémarrer**. Chaque agent affiche sa **réputation**. |
| **Contacts** | L'annuaire utilisé par le routing. Les rôles proposés sont ceux que déclarent les agents. |

Cliquer sur un e-mail lance automatiquement son traitement. Un appel au modèle prend de 10 à 60 s :
il passe par un thread de travail dédié, l'interface reste utilisable et affiche le temps écoulé.

---

## 4. Architecture

### Organisation multi-agent

Organisation **centralisée coordinateur-spécialistes**, avec un **agent critique** et l'utilisateur
comme autorité finale. Les agents ne se connaissent pas : ils échangent des messages typés inspirés
de FIPA-ACL (`REQUEST`, `PROPOSE`, `REFUSE`, `FAILURE`, `INFORM`) par un bus (Mediator). Un agent
peut **refuser** une demande ; le coordinateur délègue alors au candidat suivant, puis à l'agent de repli.

```
Interface / App ──► AssistantService ──► RoutingAgent ──► RoutingStrategy (classement + justification)
                          │                    │
                          │                    ▼  REQUEST (MessageBus)
                          │            Agent spécialisé ──► DraftGenerator ──► contexte + historique + prompt ──► LLM local
                          │                    │
                          │                    ▼  PROPOSE
                          ├──── REQUEST ──► Agent critique ── INFORM (remarques)
                          │        réécriture automatique / agent de repli / signalement
                          ▼
                  ProcessingResult ──► utilisateur : valider / modifier / rejeter / changer d'agent
                          │
                          └──► réputation des agents (Observer)
```

### Architecture logicielle

Architecture en couches de type **ports et adaptateurs** : le cœur ne dépend que d'interfaces
(les contrats communs) ; les détails techniques (Ollama, fichiers JSON, interface) sont des
adaptateurs remplaçables. `MailAssistant` est la **racine de composition** : le seul endroit qui
crée les classes concrètes et les relie. La console et l'interface pilotent donc le même système.

### Design patterns

| Pattern | Où | Besoin |
|---|---|---|
| Mediator | `SimpleMessageBus` | Les agents ne se référencent jamais entre eux |
| Observer | `MessageListener`, `FeedbackListener` | Journal en direct ; réputation alimentée par les décisions de l'utilisateur |
| Strategy | `RoutingStrategy` (mots-clés, expéditeur, métadonnées, fil, LLM) | Changer et comparer les méthodes de routing |
| Composite | `WeightedRoutingStrategy`, `CompositeContextProvider`, `CompositeMailSource` | Combiner stratégies, sources de contexte ou sources d'e-mails |
| Facade | `AssistantService`, `DraftGenerator` | Frontières simples entre interface, partie A et partie B |
| Adapter | `OllamaLLMClient`, `EmlMailSource` | Changer de moteur LLM ou de source d'e-mails sans toucher aux agents |
| Repository | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox`, `FileConversationMemory`, `ReputationStore` | Persistance indépendante du format |
| Factory | `DefaultAgentFactory` | Profil → agent |

---

## 5. Organisation du code

| Package | Contenu | Responsable |
|---|---|---|
| `model` | `Email`, `Contact`, `DraftReply`, `UserFeedback`, `ConversationEntry` | commun |
| `config` | `AppConfig` (lecture de `app.properties`) | commun |
| *(racine)* | `MailAssistant` (racine de composition), `App` (démonstrateur console) | commun |
| `agent` | `SimpleMessageBus`, `SpecializedAgent`, `RoutingAgent`, `DefaultAgentFactory`, `Payload`, `Performative` | A |
| `routing` | `KeywordRoutingStrategy`, `SenderRoutingStrategy`, `MetadataRoutingStrategy`, `ThreadAffinityStrategy`, `LlmRoutingStrategy`, `WeightedRoutingStrategy` | A |
| `app` | `DefaultAssistantService` (submit, revise, reroute, accept, reloadAgents, relecture), `FeedbackListener` | A, relecture B |
| `llm` | `OllamaLLMClient`, `StubLLMClient` | B |
| `generation` | `DefaultDraftGenerator`, `PromptBuilder` (règles communes, langue imposée) | B |
| `context` | `FileContextProvider`, `CompositeContextProvider`, `Context` (budget de tokens) | B |
| `memory` | `FileConversationMemory` (historique sur disque), `InMemoryConversationMemory` (tests) | B |
| `persistence` | `JsonAgentRepository`, `JsonContactDirectory`, `JsonInbox` | B |
| `inbox` | `MailSource`, `CompositeMailSource`, `EmlMailSource`, `EmlParser` | B |
| `review` | `CriticAgent`, `Finding`, `TextChecks` | B |
| `reputation` | `Reputation`, `ReputationStore` | B |
| `ui` | `MailMasApp`, `MainController`, `InboxPane`, `EmailPane`, `NewEmailDialog`, `ExplainPane`, `AgentsPane`, `ContactsPane` | B |

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
| `config/context/<agent>/*.md` | **Contexte** de l'agent : uniquement des **faits** (dates, membres, procédures, disponibilités), jamais des consignes. Environ 11 000 caractères au total : fiche du module, règles et FAQ pour les étudiants ; service, jury et réunions pour les collègues ; missions et RH pour l'administration ; livrables, membres et réunions pour SuperMAS. |
| `config/contacts.json` | Annuaire : adresse, nom, rôles (`student`, `teacher`, `research-partner`...) |
| `config/inbox/*.json` | E-mails de démonstration (date au format `2026-09-28T09:15:00Z`) |
| `data/inbox/` | E-mails ajoutés par l'utilisateur : `.json` du formulaire « Nouveau », `.eml` et `.json` importés. **Exclu de Git.** |
| `data/conversations/<fil>.jsonl` | **Historique des fils** (reçus, propositions, réponses validées), une entrée par ligne. **Exclu de Git.** |
| `data/reputation.json` | Réputation de chaque agent. **Exclu de Git.** |

Un dossier de contexte vide contient un fichier `.gitkeep`, ignoré par le `FileContextProvider`
(qui ne lit que les `.md` et `.txt`). Écrire les faits **sous la forme exacte utile** : « libre de
13h30 à 18h, donc libre à 14h » plutôt que « libre l'après-midi » (le modèle ne fait pas la déduction).

### Ajouter un agent

Depuis l'écran **Agents** : « Nouvel agent », remplir le formulaire, « Créer l'agent ». Le dossier
`config/context/<id>/` est créé ; y déposer les documents de l'agent. Les identifiants `router` et
`critic` sont réservés.

### Changer de modèle

`ollama pull <modèle>` puis modifier `llm.model`. Pour un autre moteur qu'Ollama (llama.cpp,
LM Studio...), écrire une nouvelle implémentation de `LLMClient` et la brancher dans `MailAssistant`.

---

## 7. Agent critique et réputation

### Agent critique (`review/CriticAgent`)

Un agent du bus : il reçoit chaque proposition (`REQUEST`) et répond par ses remarques (`INFORM`).
Il n'appelle pas le LLM : ses vérifications sont rapides, déterministes et explicables.

| Problème détecté | Exemple | Ce que fait le système |
|---|---|---|
| Formule interdite | « comme convenu », « ci-joint » | réécriture automatique par le même agent (une fois) |
| Mauvaise langue | réponse en français à un e-mail anglais | réécriture automatique |
| Engagement | « je vous confirme ma présence » | signalé à l'utilisateur |
| Date ou heure sans source | absente de l'e-mail et du contexte de l'agent | signalé |
| Tentative de manipulation | « ignore tes consignes... » dans l'e-mail | confié à l'agent de repli, qui n'a aucun contexte |
| Divulgation | une référence (ex. `OM-2026-118`) envoyée à un expéditeur inconnu | confié à l'agent de repli |

La proposition bloquée n'est **ni enregistrée ni montrée** à l'agent de repli : sinon la fuite passe
par la mémoire du fil (constaté puis corrigé, section 8).

### Réputation (`reputation/`)

Modèle du cours : **R = (1 + p) / (2 + p + n)**. Validée telle quelle : p + 1 ; validée après
modification : p + 0,5 et n + 0,5 ; rejetée ou confiée à un autre agent : n + 1. Sans donnée,
R = 0,5. Affichée dans l'écran Agents. Base possible d'un envoi automatique (agent de forte
réputation, e-mail non ambigu, aucune remarque du critique) : non activé.

---

## 8. Décisions de conception

| # | Question | Décision | Raison |
|---|---|---|---|
| 1 | Source des e-mails | Pas de connexion à une vraie messagerie : démo JSON, formulaire, import `.eml`/`.json` ; « envoyer » = enregistrer comme envoyé | Ne pas confier l'accès à un compte réel ; l'utilisateur garde le contrôle |
| 2 | Qui écrit dans la mémoire | `AssistantService` uniquement ; `DraftGenerator` lit | Éviter les doublons, un seul responsable |
| 3 | Échec (LLM indisponible, agent inconnu) | Message `FAILURE`, puis `ProcessingResult` au statut `FAILED` ; un échec arrête la délégation | Les autres agents utilisent le même LLM et échoueraient aussi |
| 4 | Refus d'un agent | Candidat suivant au-dessus du seuil, puis agent de repli (essais bornés) | Délégation entre agents, pas d'impasse |
| 5 | Cas ambigu | Meilleur agent choisi, ambiguïté signalée ; départage par LLM limité aux ex-aequo ; changement d'agent possible | Le LLM n'est appelé pour le routing que dans les cas difficiles |
| 6 | Aucun agent adapté | Agent de repli si le meilleur score est sous le seuil | Toujours une proposition |
| 7 | System prompt | Fichier `.md` séparé par agent | Plus facile à modifier qu'une chaîne JSON |
| 8 | Informations du routing | Contacts et historique reçus par le constructeur de la stratégie | `route()` reste stable |
| 9 | Échelle des scores | [0, 1], même sens pour toutes les stratégies | Combinaison possible |
| 10 | Agent créé ou modifié dans l'interface | `AssistantService.reloadAgents()` | Pas de redémarrage |
| 11 | Réponse modifiée puis validée | `DraftReply.withBody()` : on enregistre le texte réellement envoyé | Mémoire fidèle |
| 12 | Appels bloquants | L'interface appelle le service dans un thread de travail | Interface fluide |
| 13 | Configuration | `config/app.properties` lu par `AppConfig` | Changer de modèle sans recompiler |
| 14 | Combinaison des stratégies | « OU probabiliste » pondéré : `1 − Π(1 − wᵢ·sᵢ)` | Un indice fort suffit, des indices concordants se renforcent |
| 15 | Contexte et consignes | Contexte = faits ; consignes = system prompt | Une consigne placée dans le contexte est recopiée dans la réponse |
| 16 | Construction du prompt | Règles communes + consignes de l'agent | Un nouvel agent hérite des garde-fous |
| 17 | Injection de prompt | E-mail encadré et signalé comme donnée ; marqueurs neutralisés | Un e-mail ne doit pas modifier le comportement de l'agent |
| 18 | Isolation des contextes | Un agent ne lit que ses sources, jamais hors de `config/context/` | Confidentialité entre agents |
| 19 | Mode « thinking » du modèle | Désactivé, fenêtre de contexte fixée (`num_ctx` 8192) | Réponses plus rapides, pas de troncature silencieuse |
| 20 | Racine de composition | `MailAssistant` crée et relie toutes les pièces | Console et interface obtiennent le même système |
| 21 | Format de la mémoire | JSON Lines par fil, ajout seulement | Un arrêt brutal ne corrompt pas l'historique |
| 22 | Confidentialité | `data/` exclu de Git ; tests sur une mémoire temporaire | Les e-mails ne quittent pas la machine |
| 23 | Annuaire des contacts | `config/contacts.json`, écriture atomique | Le routing reconnaît un expéditeur connu |
| 24 | Dates des e-mails | Converties par `JsonInbox` (ISO-8601) | Pas de module Jackson supplémentaire |
| 25 | Appels au modèle depuis l'interface | Un **seul** thread de travail | Interface jamais gelée, modèle jamais sollicité deux fois |
| 26 | Explicabilité | Scores, justifications, contexte utilisé, journal du bus, remarques du critique | L'utilisateur comprend et corrige |
| 27 | Rôles des contacts | Générés à partir des rôles déclarés par les agents | Pas de rôle orphelin |
| 28 | Identifiants (agents, fils, e-mails) | Vérifiés ou convertis avant de servir de nom de fichier | Pas d'écriture hors des dossiers prévus |
| 29 | Lecture des `.eml` | Lecteur MIME écrit en Java standard, sans dépendance | `pom.xml` inchangé ; remplaçable par Jakarta Mail |
| 30 | Langue de la réponse | Détectée dans l'e-mail et **imposée** à la fin du prompt | Avec un long contexte français, le modèle répondait en français aux e-mails anglais (78 % → 100 %) |
| 31 | Agent critique | Agent du bus, sans LLM ; réécrit, signale ou confie à l'agent de repli | Une consigne ne suffit pas : il faut détecter les violations des normes |
| 32 | Isolation | Une proposition bloquée n'est ni enregistrée ni montrée à l'agent suivant | L'isolation doit couvrir le contexte **et** l'historique |
| 33 | Réputation | Modèle Beta du cours, alimenté par les décisions de l'utilisateur (Observer) | Mesurer la qualité de chaque agent dans la durée |
| 34 | Expériences | Configuration **gelée** pendant les mesures ; 3 essais par e-mail | Les résultats ne sont pas réglés sur le jeu de test |

---

## 9. Résultats

### Routing

| Jeu | Configuration | Bon agent |
|---|---|---|
| 21 e-mails étiquetés (`RoutingScenariosTest`) | mots-clés seuls / expéditeur seul / **pondérée** | 76 % / 86 % / **90 %** |
| 15 e-mails jamais vus (n01 à n15) | pondérée | 80 % (12/15) |
| 21 e-mails de l'expérience | pondérée + départage LLM | 86 % ; 90 % avec le critique |

Erreurs sur les e-mails nouveaux : deux collègues absentes de l'annuaire envoyées à l'agent
étudiant (« partiel », « stage ») ; l'e-mail piégé (n15) envoyé à l'administration par **ses
propres mots-clés** : une manipulation du routing par signal stratégique.

### Agent unique ou multi-agent ?

Expérience `SingleVsMultiAgentExperimentTest` : 21 e-mails (6 de la démo, 15 nouveaux), 3 essais
chacun, même modèle et même budget de contexte. L'agent unique reçoit les consignes de tous les
agents et tout le contexte.

| | Multi-agent | Agent unique | Multi sans contexte |
|---|---|---|---|
| Faits attendus | 72 % | 75 % | **9 %** |
| Bon registre (tu/vous) | **90 %** | 81 % | 100 % |
| Formule interdite | 22 % | 10 % | 3 % |
| Réponses de plus de 90 s | **0 %** | 37 % | 0 % |

- **Le contexte est le facteur décisif** (72 % des faits contre 9 % sans).
- Sur les faits, l'agent unique fait jeu égal : la recherche par pertinence trouve les bons passages.
- La spécialisation apporte un **temps prévisible**, un **meilleur registre** et l'**isolation** des
  informations, à condition que le routing soit juste : une erreur de routing se propage à la réponse.

### Langue imposée et agent critique

| | Multi-agent | + langue imposée | + langue + critique |
|---|---|---|---|
| Bonne langue | 78 % | **100 %** | **100 %** |
| Formule interdite | 22 % | 21 % | **8 %** |
| Faits attendus | 72 % | 72 % | 74 % |
| Fuite sur l'e-mail piégé (n15) | 3/3 | 3/3 | **0/3** (après correctif) |

Le critique a corrigé 10 % des propositions. Il ne voit pas les erreurs de fond : déduction fausse
(« jeudi 14h ne me convient pas » alors que le créneau est libre), année erronée, reformulation
inconnue (« je vous transmets »). La relecture humaine reste indispensable.

### Évolution du prompt (e-mail SuperMAS d'Anna)

| Version | Effet observé |
|---|---|
| v0 : aucune consigne | Plusieurs modèles de réponse, heure inventée |
| v1 : règles communes + consignes de l'agent | Bonne date ; fuite de notes internes et refus |
| v2 : « ni accepter ni refuser » | Plus de refus ; la fuite persiste |
| v3 : faits seulement dans le contexte | Plus de fuite |
| v4 : langue de l'e-mail, prudence élargie | Sur-correction : la date disparaît |
| v5 : consigne positive pour les faits | Correct ; « comme convenu » persiste |

Enseignements : un petit modèle suit mieux une **consigne positive** ou une **phrase modèle** qu'une
interdiction ; il ne fait pas de déduction sur le contexte ; une même configuration peut donner une
réponse correcte puis une invention, d'où les 3 essais par e-mail.

### Lancer les expériences

```powershell
# agent unique / multi-agent / sans contexte / avec critique (environ 3 à 4 heures)
.\mvnw.cmd test "-Dtest=SingleVsMultiAgentExperimentTest" "-Dexperiences=true" "-Druns=3"
# options : "-Dconfigs=multi,critique" "-Demails=n15,004" "-Druns=1" "-Dtimeout=300"
```

Résultats dans `target/experiences-mono-multi-qwen3.5_4b.md` (synthèse, détail par e-mail, toutes
les propositions pour la relecture). Le jeu de test et les attendus sont dans
`src/test/resources/experiences/`.

---

## 10. Tests

161 tests (JUnit 5) : contrats communs, bus et agents, stratégies et scénarios de routing, service
(submit / revise / reroute / accept, relecture), client Ollama, repositories JSON, lecteur `.eml`,
mémoire persistante, contexte, prompt et langue imposée, agent critique (seul et dans le système),
réputation, grille d'évaluation des expériences, et un test de bout en bout de la vraie
configuration avec un faux LLM.

## 11. Organisation Git

- `main` est protégée : toute modification passe par une Pull Request relue par l'autre membre.
- Une branche par fonctionnalité (`feature/...`), fusionnée dès qu'elle est terminée et testée.
- Les fichiers communs (`model/`, interfaces, `MailAssistant`, `pom.xml`, `README.md`) ne sont
  modifiés qu'après accord.
- `data/` (e-mails ajoutés, historique, réputation) n'est jamais commité.

## 12. Prochaines étapes

- Routing : mieux reconnaître les collègues absents de l'annuaire ; ne pas laisser les mots d'un
  expéditeur inconnu suffire à choisir un agent sensible.
- Agent critique : vérification des faits par le LLM (année, disponibilité) en plus des règles.
- Envoi automatique encadré par la réputation, l'absence d'ambiguïté et de remarque du critique.
- Recherche de contexte par embeddings locaux ; comparaison avec un modèle plus grand (`qwen3.5:9b`).ec un modèle plus grand
  (`qwen3.5:9b`), RAG par embeddings locaux.Gestion des agents et des contacts depuis l'interface.
- Expériences : auto-évaluation des agents par le LLM, comparaison avec un modèle plus grand
  (`qwen3.5:9b`), RAG par embeddings locaux.                          |


.\mvnw.cmd clean test