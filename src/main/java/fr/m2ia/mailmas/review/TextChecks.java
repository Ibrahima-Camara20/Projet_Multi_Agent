package fr.m2ia.mailmas.review;

import fr.m2ia.mailmas.routing.TextMatching;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vérifications simples et sans LLM sur un texte : langue, tutoiement ou vouvoiement, dates et
 * heures citées. Utilisées par l'agent critique, par le prompt (langue de l'e-mail) et par la
 * grille d'évaluation des expériences.
 *
 * <p>Les méthodes qui prennent un texte « normalisé » attendent le résultat de
 * {@link TextMatching#normalize(String)} (minuscules, sans accents).</p>
 */
public final class TextChecks {

    private static final String MONTHS_FR = "janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre";
    private static final String MONTHS_EN = "january|february|march|april|may|june|july|august|september|october|november|december";
    private static final String MONTHS = MONTHS_FR + "|" + MONTHS_EN;

    private static final Pattern TIME_H = Pattern.compile("(?<![\\d.])([01]?\\d|2[0-3]) ?h ?([0-5]\\d)?(?![\\p{L}\\d])");
    private static final Pattern TIME_COLON = Pattern.compile("(?<![\\d.])([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern TIME_AMPM = Pattern.compile("(?<![\\d.])(1[0-2]|[1-9])(?::([0-5]\\d))? ?(am|pm)(?!\\p{L})");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?<!\\d)([0-3]?\\d)(?:er|st|nd|rd|th)? (?:of )?(" + MONTHS + ")(?!\\p{L})");
    private static final Pattern RANGE = Pattern.compile(
            "(?<!\\d)([0-3]?\\d)(?:er)? (?:au|-|to) ([0-3]?\\d)(?:er|st|nd|rd|th)? (" + MONTHS + ")(?!\\p{L})");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?<!\\p{L})(" + MONTHS_EN + ") ([0-3]?\\d)(?:st|nd|rd|th)?(?!\\d)");
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\d/])([0-3]?\\d)/(0?[1-9]|1[0-2])(?![\\d])");

    private static final Set<String> FR_WORDS = Set.of("le", "la", "les", "et", "vous", "pour", "est", "je", "de",
            "des", "une", "un", "nous", "votre", "bien", "avec");
    private static final Set<String> EN_WORDS = Set.of("the", "and", "you", "to", "for", "is", "i", "of", "we",
            "your", "will", "with", "best", "regards", "be");
    private static final Set<String> TU_WORDS = Set.of("tu", "te", "toi", "ton", "ta", "tes", "t");
    private static final Set<String> VOUS_WORDS = Set.of("vous", "votre", "vos");

    private TextChecks() {
    }

    /** « fr » ou « en », d'après les mots outils les plus fréquents (texte brut ou normalisé). */
    public static String language(String text) {
        int fr = 0;
        int en = 0;
        for (String w : TextMatching.normalize(text).split("[^\\p{L}]+")) {
            if (FR_WORDS.contains(w)) {
                fr++;
            }
            if (EN_WORDS.contains(w)) {
                en++;
            }
        }
        return en > fr ? "en" : "fr";
    }

    /** « tu », « vous », ou null si le texte n'emploie ni l'un ni l'autre. */
    public static String address(String text) {
        int tu = 0;
        int vous = 0;
        for (String w : TextMatching.normalize(text).split("[^\\p{L}]+")) {
            if (TU_WORDS.contains(w)) {
                tu++;
            }
            if (VOUS_WORDS.contains(w)) {
                vous++;
            }
        }
        return tu == 0 && vous == 0 ? null : (tu > vous ? "tu" : "vous");
    }

    /**
     * Dates et heures citées, sous une forme comparable : « 14h00 », « 2 pm », « 14:00 » → « 14:00 » ;
     * « 15 novembre », « November 15 », « 15/11 » → « 15/11 » ; « du 2 au 4 décembre » → « 02/12 », « 04/12 ».
     */
    public static Set<String> datesAndTimes(String text) {
        String t = TextMatching.normalize(text);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TIME_H.matcher(t);
        while (m.find()) {
            out.add(time(Integer.parseInt(m.group(1)), m.group(2)));
        }
        m = TIME_COLON.matcher(t);
        while (m.find()) {
            out.add(time(Integer.parseInt(m.group(1)), m.group(2)));
        }
        m = TIME_AMPM.matcher(t);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1)) % 12 + (m.group(3).equals("pm") ? 12 : 0);
            out.add(time(h, m.group(2)));
        }
        m = DAY_MONTH.matcher(t);
        while (m.find()) {
            out.add(date(Integer.parseInt(m.group(1)), month(m.group(2))));
        }
        m = RANGE.matcher(t);
        while (m.find()) {
            out.add(date(Integer.parseInt(m.group(1)), month(m.group(3))));
        }
        m = MONTH_DAY.matcher(t);
        while (m.find()) {
            out.add(date(Integer.parseInt(m.group(2)), month(m.group(1))));
        }
        m = NUMERIC_DATE.matcher(t);
        while (m.find()) {
            out.add(date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return out;
    }

    /** Première expression de la liste trouvée dans le texte (mots entiers, sans accents), ou null. */
    public static String firstTerm(String text, List<String> terms) {
        String t = TextMatching.normalize(text);
        for (String term : terms) {
            if (TextMatching.containsTerm(t, term)) {
                return term;
            }
        }
        return null;
    }

    private static String time(int h, String minutes) {
        return String.format("%02d:%s", h, minutes == null ? "00" : minutes);
    }

    private static String date(int day, int month) {
        return String.format("%02d/%02d", day, month);
    }

    private static int month(String name) {
        Map<String, Integer> fr = Map.ofEntries(Map.entry("janvier", 1), Map.entry("fevrier", 2),
                Map.entry("mars", 3), Map.entry("avril", 4), Map.entry("mai", 5), Map.entry("juin", 6),
                Map.entry("juillet", 7), Map.entry("aout", 8), Map.entry("septembre", 9),
                Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));
        if (fr.containsKey(name)) {
            return fr.get(name);
        }
        return List.of(MONTHS_EN.split("\\|")).indexOf(name) + 1;
    }
}