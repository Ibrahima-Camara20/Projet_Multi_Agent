# Partie A (Rania) : cœur multi-agent et routing

Ce document décrit ce qui a été implémenté dans `agent/`, `routing/` et `app/`, et les
résultats des tests de routing (matière pour le rapport).

Les contrats communs (`model/`, interfaces de chaque package, `ContractsTest`) **n'ont pas été
modifiés**.

## 1. Fichiers ajoutés

| Fichier | Rôle | Pattern |
|---|---|---|
| `agent/SimpleMessageBus` | Livraison synchrone des `AgentMessage`, diffusion aux `MessageListener`, erreurs converties en `FAILURE` | Mediator, Observer |
| `agent/SpecializedAgent` | Agent générique piloté par un `AgentProfile` ; délègue la rédaction au `DraftGenerator` ; peut **refuser** | — |
| `agent/AgentFactory`, `DefaultAgentFactory` | Profil → agent ; variantes avec auto-évaluation (mots-clés ou LLM) | Factory |
| `agent/RoutingAgent` | Coordinateur : routing, seuil, repli, ambiguïté, départage, refus → candidat suivant | — |
| `agent/RoutingOutcome` | Résultat d'une coordination (statut, décision, agent, refus rencontrés, note) | — |
| `agent/Participants` | Identifiants réservés `router` et `user` | — |
| `routing/TextMatching` | Normalisation (accents, casse), mots entiers, motifs d'adresse `*@domaine` | — |
| `routing/KeywordRoutingStrategy` | Mots-clés du profil dans l'objet et le corps | Strategy |
| `routing/SenderRoutingStrategy` | Motif d'adresse + rôle connu (`ContactDirectory`) | Strategy |
| `routing/ThreadAffinityStrategy` | Favorise l'agent qui a déjà répondu dans le fil (`ConversationMemory`) | Strategy |
| `routing/MetadataRoutingStrategy` | Métadonnées de l'e-mail : étiquette égale à l'id d'un agent, ou mot-clé du profil dans une valeur (ex. `list-id`) | Strategy |
| `routing/WeightedRoutingStrategy` | Combinaison pondérée de stratégies | Strategy + Composite |
| `routing/LlmRoutingStrategy` | Choix par le LLM local (départage des cas ambigus, auto-évaluation) | Strategy |
| `app/DefaultAssistantService` | Point d'entrée de l'UI : submit / revise / reroute / accept / reloadAgents | Facade |

Configuration ajoutée : profils `student`, `teacher`, `administration`, `generic` (repli) dans
`config/agents/` (JSON + system prompt `.md`), mot-clé `SuperMASProject` ajouté à
`research-supermas`, dossiers `config/context/<agent>/`, e-mails `002` à `006` dans
`config/inbox/` (un par scénario du sujet).

Tests : `routing/RoutingStrategiesTest`, `agent/AgentsTest`, `agent/SelfAssessmentTest`,
`agent/RoutingAgentTest`, `app/DefaultAssistantServiceTest`, `RoutingScenariosTest` (67 tests au
total avec `ContractsTest`). Comme prévu à l'étape 2 du plan, l'agent spécialisé est aussi testé
avec `StubLLMClient`.

## 2. Flux

```
UI ──submit(email)──► DefaultAssistantService ──memory.recordReceived
                              │
                              ▼
                        RoutingAgent.process
                              │  strategy.route(email, agents du bus) → RoutingDecision
                              │  seuil / ambiguïté / départage → ordre d'essai
                              ▼
          MessageBus.send(REQUEST DraftRequest)   (listeners : journal, UI)
                              │
                              ▼
                       SpecializedAgent.handle ──► DraftGenerator (B) ──► LLMClient (B)
                              │
               PROPOSE / REFUSE (→ candidat suivant) / FAILURE (→ arrêt)
                              ▼
     DefaultAssistantService ──memory.recordDraft──► ProcessingResult ──► UI

rejet  : revise(emailId, feedback)  → même agent, tous les commentaires, attempt + 1
autre  : reroute(emailId, agentId)  → agent choisi par l'utilisateur
valide : accept(emailId, feedback)  → memory.recordSent (texte modifié si EDIT)
```

## 3. Décisions de conception

**Ordre d'essai.** Candidats au-dessus du seuil (score décroissant), puis l'agent de repli,
dans la limite de `routing.maxAttempts`. S'il n'y a pas d'agent de repli, les agents sous le
seuil sont tout de même essayés : l'utilisateur obtient toujours une proposition.

**Aucun agent adapté.** Meilleur score sous `routing.threshold` (0,4) → agent de repli directement.

**Cas ambigu.** Écart entre les deux meilleurs inférieur à `routing.ambiguityMargin` (0,1) : le
meilleur est choisi, `ProcessingResult.ambiguous()` est vrai et l'UI propose `reroute`. Si une
stratégie de départage est fournie (`LlmRoutingStrategy`), elle n'est consultée que sur les
candidats à égalité, et son choix passe en tête. Le LLM n'est donc appelé pour le routing que
dans les cas difficiles.

**REFUSE ou FAILURE.** Un refus fait passer au candidat suivant : c'est une vraie délégation
entre agents. Un échec (LLM injoignable, délai dépassé) arrête tout : les autres agents
utiliseraient le même LLM et échoueraient aussi. Le résultat est `FAILED` avec un message
lisible, et `revise` permet de réessayer avec le même agent.

**Quand un agent refuse-t-il ?** Il refuse un message qu'il ne sait pas traiter, une demande
au-delà de 5 propositions pour un même e-mail, et (en option) une délégation du router qu'il
juge hors de son domaine. Les demandes faites au nom de l'utilisateur (`revise`, `reroute`) ne
sont jamais refusées pour compétence. L'agent de repli ne refuse jamais.

**Combinaison pondérée : OU probabiliste.** `score = 1 − Π(1 − wᵢ·sᵢ)`. Une moyenne pondérée
diluerait un indice fort et isolé (mots-clés clairs, expéditeur inconnu) sous le seuil. Le OU
probabiliste laisse un indice fort suffire, renforce les indices concordants et reste dans
[0, 1] quel que soit le nombre de stratégies. Limite : les scores se tassent près de 1. Deux
agents à 0,95 et 0,97 sont alors déclarés ambigus alors qu'ils sont tous deux très probables.

**Score des mots-clés : saturation.** `1 − exp(−0,6·w)`, avec w = 1 par mot dans le corps et
1,5 dans l'objet. Un profil riche en mots-clés n'est pas pénalisé, contrairement à un ratio
« trouvés / total ».

**État des e-mails en cours.** Le service garde, par e-mail : l'e-mail, la décision, l'agent
responsable, les commentaires de rejet cumulés, le numéro de proposition, la dernière
proposition et le statut validé. Il est le seul à écrire dans `ConversationMemory`.

## 4. Expériences de routing (`RoutingScenariosTest`)

Jeu étiqueté de 21 e-mails écrits pour les tests (5 catégories, plus des messages courts sans
mot-clé, un e-mail automatique de liste de diffusion, un cas piège et un « même expéditeur,
autre contexte »). La configuration « pondérée » combine mots-clés (1,0), expéditeur (0,6) et
métadonnées (0,8). Le système complet est
exécuté avec les **vrais profils** de `config/agents`, un faux générateur et le seuil 0,4.

| id | attendu | mots-clés seuls | expéditeur seul | pondérée | pondérée + refus |
|---|---|---|---|---|---|
| s1–s4, t1–t3, a1–a3, r1–r2, g1–g2 | (15 cas « faciles ») | ✓ | ✓ | ✓ | ✓ |
| r3 collègue écrit sur SuperMASProject | research-supermas | ✓ | ✗ teacher | ✓ | ✓ |
| s5 étudiant « la salle a changé ? » | student | ✗ generic | ✓ | ✓ | ✗ generic |
| t4 collègue « café pour parler du module ? » | teacher | ✗ generic | ✓ | ✓ | ✗ generic |
| r4 partenaire « travel arrangements » | research-supermas | ✗ generic | ✓ | ✓ | ✗ generic |
| a4 scolarité demande un sujet d'examen | administration | ✗ student | ✓ | ✗ student | ✗ student |
| m1 digest de la liste `supermas-wp3` | research-supermas | ✗ generic | ✗ generic | ✓ | ✗ generic |
| g3 étudiant connu vend son vélo | generic | ✓ | ✗ student | ✗ student | ✓ |
| **Exactitude (21 e-mails)** | | **76 %** | **86 %** | **90 %** | **76 %** |

Ce qu'on peut en dire dans le rapport :

- **Aucun indice seul ne suffit.** Les mots-clés ratent les messages courts, l'expéditeur rate
  « même personne, autre contexte », et seules les métadonnées reconnaissent l'e-mail
  automatique m1. La combinaison garde les points forts de chacun et obtient le meilleur score.
- **Le refus par règles est un compromis, pas un gain.** Il corrige g3 mais refuse aussi
  s5, t4 et r4, car ces cas ont exactement les mêmes indices de surface (expéditeur connu,
  aucun mot-clé). Seule une lecture du sens les distingue. D'où l'option
  `withLlmSelfAssessment`, à mesurer avec le vrai LLM (coût : un appel de plus par délégation).
- **Le piège a4** (l'administration parle d'examen) montre la limite des mots-clés : le vocabulaire
  d'un e-mail ne dit pas toujours qui écrit ni pourquoi.
- **L'e-mail 005** (« Stage et SuperMAS », d'un collègue) est bien détecté comme ambigu
  (research 0,78 contre teacher 0,76). C'est le cas où le départage par LLM est utile.
- Ce jeu est petit et écrit par nous : les chiffres sont indicatifs. Il faut l'enrichir avec
  des e-mails réels anonymisés et refaire la mesure avec Ollama, en comparant l'auto-évaluation
  LLM et le départage LLM.

Réglage : avec un poids de 0,9 pour l'expéditeur, l'e-mail 005 n'était plus détecté comme
ambigu (le rôle « teacher » écrasait le contenu). Le poids 0,6 retenu laisse au contenu sa
place, conformément au sujet : « la classification ne doit pas reposer uniquement sur
l'adresse de l'expéditeur ».

## 5. Organisation Git (section 9 du plan)

Le travail est réparti sur les deux branches prévues pour la personne A :

| Branche | Base | Contenu |
|---|---|---|
| `feature/specialized-agents` | `main` | `SimpleMessageBus`, `SpecializedAgent`, `AgentFactory`, `DefaultAgentFactory`, `Participants`, profils d'agents, tests du bus et des agents |
| `feature/agent-routing` | `feature/specialized-agents` | stratégies de routing, `RoutingAgent`, auto-évaluation, `DefaultAssistantService`, e-mails de test, tests de routing et de scénarios, ce document |

`feature/agent-routing` part de `feature/specialized-agents`, car le router a besoin du bus et
des agents. Il faut donc fusionner `feature/specialized-agents` dans `main` en premier. Aucun
fichier commun (`model/`, interfaces, `README.md`, `ContractsTest`) n'est modifié.

## 6. Réponses aux questions du sujet (partie A)

1. **Qu'est-ce qu'un agent ?** Une entité identifiée par un profil (rôle, prompt, sources de
   contexte, indices de routing). Elle reçoit des messages par le bus et décide de les traiter
   ou non. Un agent = une configuration ; une sous-classe Java seulement si le comportement diffère.
2. **Ses responsabilités ?** Le router choisit et délègue. Un agent spécialisé accepte ou refuse,
   puis produit une proposition dans son domaine. Le service gère le cycle avec l'utilisateur
   et la mémoire.
3. **Ses informations ?** Son profil et le contenu des messages reçus. Le contexte n'est lu que
   par les sources déclarées dans le profil, ce qui isole les agents. Le router connaît les
   profils, l'annuaire et l'historique des fils.
4. **Communication ?** Messages typés inspirés de FIPA-ACL (REQUEST, PROPOSE, REFUSE, FAILURE)
   via un médiateur. Les agents ne se référencent jamais entre eux.
5. **Choix de l'agent ?** Stratégies interchangeables (mots-clés, expéditeur, fil, métadonnées, LLM)
   combinées par un OU probabiliste pondéré. Chaque score est justifié.
6. **Plusieurs agents adaptés ?** Cas marqué ambigu, départage optionnel par LLM limité aux
   ex-aequo, choix final laissé à l'utilisateur (`reroute`).
7. **Aucun agent adapté ?** Agent de repli sous le seuil. Si tous refusent, le résultat est
   `FAILED` avec l'explication des refus.
11. **Ajouter un agent ?** Écrire `config/agents/<id>.json` et `<id>.md` (ou le faire depuis
    l'UI), puis `reloadAgents()`. Aucune recompilation.
