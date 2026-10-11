package fr.m2ia.mailmas.routing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Outils de comparaison de texte communs aux stratégies de routing. */
public final class TextMatching {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Map<String, Pattern> TERM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> GLOB_CACHE = new ConcurrentHashMap<>();

    private TextMatching() {
    }

    /** Minuscules, sans accents, espaces normalisés : « Réunion  Jury » → « reunion jury ». */
    public static String normalize(String s) {
        if (s == null)
            return "";
        String noAccents = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return SPACES.matcher(noAccents.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * Vrai si {@code term} apparaît comme mot entier dans un texte déjà normalisé.
     * Les frontières sont des lettres : « TP » trouve « TP2 » et « (TP) » mais pas « ATP » ;
     * les expressions de plusieurs mots (« work package ») sont acceptées.
     */
    public static boolean containsTerm(String normalizedText, String term) {
        String t = normalize(term);
        if (t.isEmpty() || normalizedText == null || normalizedText.isEmpty())
            return false;
        Pattern p = TERM_CACHE.computeIfAbsent(t,
                k -> Pattern.compile("(?<!\\p{L})" + Pattern.quote(k) + "(?!\\p{L})"));
        return p.matcher(normalizedText).find();
    }

    /**
     * Motif d'adresse avec jokers, insensible à la casse : {@code *} = n'importe quelle suite de
     * caractères, {@code ?} = un caractère. Ex. « *@partner-lab.eu ».
     */
    public static boolean matchesGlob(String value, String glob) {
        if (value == null || glob == null || glob.isBlank())
            return false;
        Pattern p = GLOB_CACHE.computeIfAbsent(glob.trim(), TextMatching::globToRegex);
        return p.matcher(value.trim()).matches();
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                re.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty())
            re.append(Pattern.quote(literal.toString()));
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Extrait l'adresse d'un champ « Nom &lt;adresse&gt; » ; renvoie le champ tel quel sinon. */
    public static String emailAddress(String from) {
        if (from == null)
            return "";
        int lt = from.indexOf('<');
        int gt = from.indexOf('>', lt + 1);
        String addr = (lt >= 0 && gt > lt) ? from.substring(lt + 1, gt) : from;
        return addr.trim().toLowerCase(Locale.ROOT);
    }

    /** Borne un score dans [0, 1] (protège des erreurs d'arrondi). */
    static double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }
}
