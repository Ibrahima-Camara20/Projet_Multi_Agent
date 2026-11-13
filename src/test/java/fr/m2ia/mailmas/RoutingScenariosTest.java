package fr.m2ia.mailmas;

import fr.m2ia.mailmas.agent.AgentFactory;
import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.DefaultAgentFactory;
import fr.m2ia.mailmas.agent.RoutingAgent;
import fr.m2ia.mailmas.agent.RoutingOutcome;
import fr.m2ia.mailmas.agent.SimpleMessageBus;
import fr.m2ia.mailmas.config.AppConfig;
import fr.m2ia.mailmas.memory.InMemoryConversationMemory;
import fr.m2ia.mailmas.model.Email;
import fr.m2ia.mailmas.routing.KeywordRoutingStrategy;
import fr.m2ia.mailmas.routing.MetadataRoutingStrategy;
import fr.m2ia.mailmas.routing.RoutingStrategy;
import fr.m2ia.mailmas.routing.SenderRoutingStrategy;
import fr.m2ia.mailmas.routing.WeightedRoutingStrategy;
import fr.m2ia.mailmas.support.ConfigProfiles;
import fr.m2ia.mailmas.support.LabeledEmails;
import fr.m2ia.mailmas.support.LabeledEmails.Labeled;
import fr.m2ia.mailmas.support.TestSupport.FakeDraftGenerator;
import fr.m2ia.mailmas.support.TestSupport.MapContactDirectory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static fr.m2ia.mailmas.support.TestSupport.email;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Scénarios du sujet sur les <b>vrais profils</b> de config/agents et la vraie boîte
 * config/inbox, et comparaison de plusieurs configurations de routing sur un petit jeu
 * d'e-mails étiquetés (le tableau affiché sert au rapport).
 */
class RoutingScenariosTest {

    private static final Path AGENTS = Path.of("config/agents");
    private static final Path INBOX = Path.of("config/inbox");

    private final MapContactDirectory contacts = LabeledEmails.contacts();

    private RoutingAgent build(RoutingStrategy strategy, boolean selfAssessment, List<AgentProfile> profiles) {
        var bus = new SimpleMessageBus();
        var generator = new FakeDraftGenerator();
        var memory = new InMemoryConversationMemory();
        AgentFactory factory = selfAssessment ? DefaultAgentFactory.withKeywordSelfAssessment(generator, memory)
                : new DefaultAgentFactory(generator);
        profiles.forEach(p -> bus.register(factory.create(p)));
        var router = new RoutingAgent(bus, strategy, RoutingAgent.Settings.from(AppConfig.defaults()));
        bus.register(router);
        return router;
    }

    private RoutingStrategy weighted() {
        return WeightedRoutingStrategy.builder()
                .add(new KeywordRoutingStrategy(), 1.0)
                .add(new SenderRoutingStrategy(contacts), 0.6)
                .add(new MetadataRoutingStrategy(), 0.8)
                .build();
    }

    @Test
    void configurationsComparedOnLabeledSet() {
        List<AgentProfile> profiles = ConfigProfiles.load(AGENTS);
        Map<String, RoutingAgent> configs = new LinkedHashMap<>();
        configs.put("mots-clés seuls", build(new KeywordRoutingStrategy(), false, profiles));
        configs.put("expéditeur seul", build(new SenderRoutingStrategy(contacts), false, profiles));
        configs.put("pondérée", build(weighted(), false, profiles));
        configs.put("pondérée + refus", build(weighted(), true, profiles));

        Map<String, Double> accuracy = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder(String.format(Locale.ROOT, "%n%-4s %-18s", "id", "attendu"));
        configs.keySet().forEach(k -> table.append(String.format(Locale.ROOT, " | %-18s", k)));
        table.append('\n');
        List<Labeled> set = LabeledEmails.labeledSet();
        Map<String, Integer> correct = new LinkedHashMap<>();
        for (Labeled l : set) {
            table.append(String.format(Locale.ROOT, "%-4s %-18s", l.email().id(), l.expected()));
            for (var e : configs.entrySet()) {
                RoutingOutcome out = e.getValue().process(l.email(), List.of(), 1);
                boolean ok = l.expected().equals(out.agentId());
                correct.merge(e.getKey(), ok ? 1 : 0, Integer::sum);
                table.append(String.format(Locale.ROOT, " | %-16s %s", out.agentId(), ok ? "✓" : "✗"));
            }
            table.append('\n');
        }
        correct.forEach((k, v) -> accuracy.put(k, (double) v / set.size()));
        table.append("Exactitude : ");
        accuracy.forEach((k, v) -> table.append(String.format(Locale.ROOT, "%s = %.0f %%   ", k, 100 * v)));
        System.out.println(table);

        // La combinaison fait au moins aussi bien que les mots-clés seuls et reste au-dessus de 85 %.
        assertTrue(accuracy.get("pondérée") >= accuracy.get("mots-clés seuls"));
        assertTrue(accuracy.get("pondérée") >= 0.85, "exactitude " + accuracy.get("pondérée"));
        // Le refus par mots-clés corrige « même expéditeur, autre contexte » (g3)...
        assertEquals("generic", configs.get("pondérée + refus").process(set.get(set.size() - 1).email(),
                List.of(), 1).agentId());
        // ... mais n'améliore pas l'exactitude globale : c'est un compromis, documenté dans le rapport.
    }

    @Test
    void inboxScenariosOfTheSubject() {
        List<AgentProfile> profiles = ConfigProfiles.load(AGENTS);
        RoutingAgent router = build(weighted(), true, profiles);

        Map<String, RoutingOutcome> byId = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (Email e : ConfigProfiles.inbox(INBOX)) {
            RoutingOutcome out = router.process(e, List.of(), 1);
            byId.put(e.id(), out);
            var best = out.decision().best().orElseThrow();
            lines.add(String.format(Locale.ROOT, "%s %-28s → %-17s ambigu=%-5s meilleur=%.2f (%s)",
                    e.id(), e.subject(), out.agentId(), out.ambiguous(), best.score(), best.reason()));
        }
        System.out.println(String.join("\n", lines));

        assertEquals("research-supermas", byId.get("001").agentId());
        assertEquals("student", byId.get("002").agentId());
        assertEquals("teacher", byId.get("003").agentId());
        assertEquals("administration", byId.get("004").agentId());
        assertTrue(byId.get("005").ambiguous(), "e-mail 005 volontairement ambigu");
        assertEquals("generic", byId.get("006").agentId());
    }

    @Test
    void profilesInConfigAreValid() {
        List<AgentProfile> profiles = ConfigProfiles.load(AGENTS);
        assertEquals(1, profiles.stream().filter(AgentProfile::fallback).count(), "un seul agent de repli");
        for (AgentProfile p : profiles) {
            assertFalse(p.systemPrompt().isBlank(), "system prompt manquant pour " + p.id());
            assertNotEquals("router", p.id());
        }
    }
}
