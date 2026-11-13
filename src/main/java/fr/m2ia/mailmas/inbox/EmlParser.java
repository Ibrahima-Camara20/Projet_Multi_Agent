package fr.m2ia.mailmas.inbox;

import fr.m2ia.mailmas.model.Email;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture d'un fichier .eml (format RFC 5322 / MIME) sans bibliothèque externe.
 *
 * <p>Couvre ce que produisent Gmail, Outlook et Thunderbird : en-têtes repliés et encodés
 * (=?UTF-8?Q?...?=), corps en quoted-printable ou base64, messages multipart (on garde le texte
 * brut, sinon le HTML converti en texte, et on ignore les pièces jointes).</p>
 *
 * <p>Le texte cité de l'e-mail précédent (« Le ... a écrit : », lignes « > ») est retiré :
 * l'historique du fil est déjà fourni au LLM par la mémoire des conversations.</p>
 */
final class EmlParser {

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
    private static final Pattern BETWEEN_ENCODED_WORDS = Pattern.compile("(\\?=)\\s+(=\\?)");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PARAM = Pattern.compile("(?i);\\s*([a-z-]+)\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)");
    private static final Pattern QUOTE_HEADER = Pattern.compile(
            "(?ims)^\\s*(le|on)\\s[^\\n]{0,200}?(\\n[^\\n]{0,120}?)?(a\\s+écrit|wrote)\\s*:\\s*$");
    private static final Pattern ORIGINAL_MESSAGE = Pattern.compile(
            "(?im)^\\s*-{2,}\\s*(original message|message d'origine)\\s*-{2,}\\s*$");

    private EmlParser() {
    }

    /** Une partie MIME : en-têtes (noms en minuscules, valeurs dépliées) et corps encore encodé. */
    private record Part(Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }

    /**
     * @param id       identifiant donné à l'e-mail dans le système
     * @param raw      contenu brut du fichier
     * @param fallback date utilisée si l'en-tête Date est absent ou illisible
     */
    static Email parse(String id, byte[] raw, Instant fallback) {
        // ISO-8859-1 garde chaque octet tel quel : le vrai charset est appliqué partie par partie.
        String text = new String(raw, StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        Part message = part(text);

        String from = firstAddress(header(message, "from"));
        if (from == null) {
            throw new IllegalArgumentException("pas d'expéditeur (en-tête From)");
        }
        List<String> to = addresses(header(message, "to"));
        String subject = header(message, "subject").strip();
        String body = removeQuotedReply(bodyText(message));

        String messageId = stripBrackets(message.header("message-id"));
        String threadId = threadRoot(message, messageId, id);

        Map<String, String> metadata = new HashMap<>();
        String listId = header(message, "list-id").strip();
        if (!listId.isEmpty()) {
            metadata.put("list-id", listId);
        }
        return new Email(id, threadId, from, to, subject, body, date(message.header("date"), fallback), metadata);
    }

    // ------------------------------------------------------------------ structure

    private static Part part(String text) {
        int split = text.startsWith("\n") ? 0 : text.indexOf("\n\n");
        String head = split < 0 ? text : text.substring(0, split);
        String body = split < 0 ? "" : text.substring(split + (text.startsWith("\n") ? 1 : 2));

        Map<String, String> headers = new LinkedHashMap<>();
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : head.split("\n")) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                value.append(' ').append(line.strip());            // en-tête replié sur plusieurs lignes
                continue;
            }
            if (name != null) {
                headers.putIfAbsent(name, value.toString());
            }
            int colon = line.indexOf(':');
            name = colon > 0 ? line.substring(0, colon).strip().toLowerCase(Locale.ROOT) : null;
            value = new StringBuilder(colon > 0 ? line.substring(colon + 1).strip() : "");
        }
        if (name != null) {
            headers.putIfAbsent(name, value.toString());
        }
        return new Part(headers, body);
    }

    /** Texte du message : text/plain de préférence, sinon text/html converti en texte. */
    private static String bodyText(Part message) {
        String plain = find(message, "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain.strip();
        }
        String html = find(message, "text/html");
        return html != null ? htmlToText(html).strip() : "";
    }

    private static String find(Part p, String wanted) {
        String type = mediaType(p);
        if (p.header("content-disposition").toLowerCase(Locale.ROOT).startsWith("attachment")) {
            return null;
        }
        if (type.startsWith("multipart/")) {
            String boundary = param(p.header("content-type"), "boundary");
            if (boundary == null) {
                return null;
            }
            for (Part child : children(p.body(), boundary)) {
                String found = find(child, wanted);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return type.equals(wanted) ? decodeBody(p) : null;
    }

    private static List<Part> children(String body, String boundary) {
        List<Part> parts = new ArrayList<>();
        String delimiter = "--" + boundary;
        StringBuilder current = null;
        for (String line : body.split("\n", -1)) {
            String l = line.stripTrailing();
            if (l.equals(delimiter + "--")) {
                break;
            }
            if (l.equals(delimiter)) {
                if (current != null) {
                    parts.add(part(current.toString()));
                }
                current = new StringBuilder();
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            parts.add(part(current.toString()));
        }
        return parts;
    }

    private static String mediaType(Part p) {
        String ct = p.header("content-type");
        if (ct.isBlank()) {
            return "text/plain";                                   // valeur par défaut de MIME
        }
        int semi = ct.indexOf(';');
        return (semi < 0 ? ct : ct.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
    }

    private static String param(String headerValue, String name) {
        Matcher m = PARAM.matcher(headerValue);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) {
                return m.group(3) != null ? m.group(3) : m.group(2);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ décodage

    private static String decodeBody(Part p) {
        String encoding = p.header("content-transfer-encoding").strip().toLowerCase(Locale.ROOT);
        byte[] bytes = switch (encoding) {
            case "base64" -> Base64.getMimeDecoder().decode(p.body().replaceAll("[^A-Za-z0-9+/=]", ""));
            case "quoted-printable" -> quotedPrintable(p.body(), false);
            default -> p.body().getBytes(StandardCharsets.ISO_8859_1);
        };
        return decode(bytes, param(p.header("content-type"), "charset"));
    }

    private static byte[] quotedPrintable(String s, boolean underscoreIsSpace) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String text = s.replace("=\n", "");                        // « = » en fin de ligne : ligne coupée
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' && isHex(text, i + 1) && isHex(text, i + 2)) {
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c == '_' && underscoreIsSpace) {
                out.write(' ');
            } else {
                out.write((byte) c);
            }
        }
        return out.toByteArray();
    }

    private static boolean isHex(String s, int i) {
        return i < s.length() && Character.digit(s.charAt(i), 16) >= 0;
    }

    /** Octets → texte avec le charset annoncé ; sans charset : UTF-8 si valide, sinon Latin-1. */
    private static String decode(byte[] bytes, String charset) {
        if (charset != null) {
            try {
                return new String(bytes, Charset.forName(charset.strip()));
            } catch (IllegalArgumentException unsupported) {
                // charset inconnu : on essaie comme sans charset
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Valeur d'en-tête décodée : mots encodés RFC 2047 et éventuel UTF-8 brut. */
    private static String header(Part p, String name) {
        String raw = decode(p.header(name).getBytes(StandardCharsets.ISO_8859_1), null);
        raw = BETWEEN_ENCODED_WORDS.matcher(raw).replaceAll("$1$2");   // espaces entre mots encodés ignorés
        Matcher m = ENCODED_WORD.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            byte[] bytes = m.group(2).equalsIgnoreCase("B")
                    ? Base64.getMimeDecoder().decode(m.group(3))
                    : quotedPrintable(m.group(3), true);
            m.appendReplacement(sb, Matcher.quoteReplacement(decode(bytes, m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ champs de l'e-mail

    private static String firstAddress(String value) {
        List<String> all = addresses(value);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> addresses(String value) {
        List<String> result = new ArrayList<>();
        Matcher m = ADDRESS.matcher(value);
        while (m.find()) {
            result.add(m.group().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * Le fil est identifié par le premier message de la conversation : premier identifiant de
     * References, sinon In-Reply-To, sinon le Message-ID du message lui-même.
     */
    private static String threadRoot(Part message, String messageId, String fallback) {
        String references = message.header("references").strip();
        if (!references.isEmpty()) {
            return stripBrackets(references.split("\\s+")[0]);
        }
        String inReplyTo = message.header("in-reply-to").strip();
        if (!inReplyTo.isEmpty()) {
            return stripBrackets(inReplyTo.split("\\s+")[0]);
        }
        return messageId.isEmpty() ? fallback : messageId;
    }

    private static String stripBrackets(String s) {
        return s.strip().replaceAll("^<|>$", "");
    }

    private static Instant date(String value, Instant fallback) {
        String v = value.replaceAll("\\([^)]*\\)", "").strip();   // « +0200 (CEST) » → « +0200 »
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ nettoyage du texte

    static String removeQuotedReply(String body) {
        String text = body;
        for (Pattern p : List.of(QUOTE_HEADER, ORIGINAL_MESSAGE)) {
            Matcher m = p.matcher(text);
            if (m.find() && m.start() > 0) {
                text = text.substring(0, m.start());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.equals("-- ")) {
                break;                                             // début de la signature
            }
            if (!line.startsWith(">")) {
                sb.append(line).append('\n');
            }
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? body.strip() : result;           // on ne vide jamais un e-mail
    }

    static String htmlToText(String html) {
        String t = html.replaceAll("(?is)<(style|script|head)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
    }
}