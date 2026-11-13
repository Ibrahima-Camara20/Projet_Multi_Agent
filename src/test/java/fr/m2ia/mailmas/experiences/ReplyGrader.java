package fr.m2ia.mailmas.experiences;

import fr.m2ia.mailmas.review.TextChecks;
import fr.m2ia.mailmas.routing.TextMatching;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Grille d'évaluation automatique d'une proposition de réponse.
 *
 * <p>Elle ne remplace pas la relecture : elle rend les essais comparables et repère ce qu'un
 * humain doit vérifier. Critères :</p>
 * <ul>
 *   <li><b>faits</b> : les informations attendues sont-elles présentes ? (chaque fait accepte
 *       plusieurs formulations) ;</li>
 *   <li><b>formules interdites</b> : « ci-joint », « comme convenu »... (normes du prompt) ;</li>
 *   <li><b>fuite</b> : des informations d'un autre domaine apparaissent-elles ? (isolation) ;</li>
 *   <li><b>langue</b> : la réponse est-elle dans la langue attendue ?</li>
 *   <li><b>registre</b> : tutoiement ou vouvoiement, comme l'interlocuteur ;</li>
 *   <li><b>dates et heures sans source</b> : présentes dans la réponse mais ni dans l'e-mail ni
 *       dans les documents de contexte (inventions probables, à vérifier à la main).</li>
 * </ul>
 */
public final class ReplyGrader {

    /** Formules interdites pour tous les agents (règles communes du prompt). */
    public static final List<String> GLOBAL_FORBIDDEN = List.of(
            "ci-joint", "ci-jointe", "en piece jointe", "comme convenu", "as agreed", "please find attached",
            "i have attached", "i attach");

    /** Ce qu'on attend de la réponse à un e-mail. */
    public record Expectation(String emailId, List<String> expectedAgents, String language, String address,
                              List<List<String>> facts, List<String> forbidden, List<String> leaks) {
        public Expectation {
            expectedAgents = expectedAgents != null ? List.copyOf(expectedAgents) : List.of();
            facts = facts != null ? facts.stream().map(List::copyOf).toList() : List.of();
            forbidden = forbidden != null ? List.copyOf(forbidden) : List.of();
            leaks = leaks != null ? List.copyOf(leaks) : List.of();
        }
    }

    /** Résultat de la grille pour une proposition. */
    public record Grade(int factsFound, int factsTotal, List<String> missingFacts, List<String> forbiddenFound,
                        List<String> leaksFound, boolean languageOk, boolean addressOk, List<String> unsupported,
                        int placeholders) {
        public double factRate() {
            return factsTotal == 0 ? 1.0 : (double) factsFound / factsTotal;
        }
    }

    private ReplyGrader() {
    }

    /**
     * @param reply   texte de la proposition
     * @param sources e-mail et documents de contexte : ce que la réponse a le droit de citer
     */
    public static Grade grade(String reply, Expectation exp, String sources) {
        String r = TextMatching.normalize(reply);

        List<String> missing = new ArrayList<>();
        int found = 0;
        for (List<String> alternatives : exp.facts()) {
            if (alternatives.stream().anyMatch(a -> r.contains(TextMatching.normalize(a)))) {
                found++;
            } else {
                missing.add(String.join(" / ", alternatives));
            }
        }

        List<String> forbidden = new ArrayList<>();
        for (String f : concat(GLOBAL_FORBIDDEN, exp.forbidden())) {
            if (TextMatching.containsTerm(r, f)) {
                forbidden.add(f);
            }
        }

        List<String> leaks = exp.leaks().stream().filter(l -> TextMatching.containsTerm(r, l)).toList();

        Set<String> allowed = datesAndTimes(TextMatching.normalize(sources));
        List<String> unsupported = datesAndTimes(r).stream().filter(d -> !allowed.contains(d)).toList();

        int placeholders = countOccurrences(reply, "[À COMPLÉTER]") + countOccurrences(reply, "[A COMPLETER]");
        String address = address(r);
        boolean addressOk = exp.address() == null || address == null || address.equals(exp.address());
        return new Grade(found, exp.facts().size(), missing, forbidden, leaks,
                language(r).equals(exp.language()), addressOk, unsupported, placeholders);
    }

    /** « tu », « vous », ou null si la réponse n'emploie ni l'un ni l'autre. */
    public static String address(String text) {
        return TextChecks.address(text);
    }

    /** « fr » ou « en », d'après les mots outils les plus fréquents. */
    public static String language(String text) {
        return TextChecks.language(text);
    }

    /** Dates et heures normalisées (voir {@link TextChecks#datesAndTimes(String)}). */
    public static Set<String> datesAndTimes(String text) {
        return TextChecks.datesAndTimes(text);
    }

    private static int countOccurrences(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}