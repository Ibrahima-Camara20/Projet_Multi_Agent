package fr.m2ia.mailmas.support;

import fr.m2ia.mailmas.agent.AgentProfile;
import fr.m2ia.mailmas.agent.RoutingHints;
import fr.m2ia.mailmas.model.Email;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lit les vrais fichiers de config/agents et config/inbox pour les tests de scénarios.
 *
 * <p>
 * Lecteur JSON minimal, réservé aux tests : il évite d'attendre JsonAgentRepository (personne
 * B). Une fois ce dernier disponible, les tests pourront l'utiliser à la place.
 * </p>
 */
public final class ConfigProfiles {

    private ConfigProfiles() {
    }

    public static List<AgentProfile> load(Path agentsDir) {
        try (Stream<Path> files = Files.list(agentsDir)) {
            List<AgentProfile> out = new ArrayList<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> m = asMap(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                String id = (String) m.get("id");
                Path md = agentsDir.resolve(id + ".md");
                String prompt = Files.exists(md) ? Files.readString(md, StandardCharsets.UTF_8) : "";
                Map<String, Object> h = m.containsKey("routingHints") ? asMap(m.get("routingHints")) : Map.of();
                out.add(new AgentProfile(id, (String) m.get("name"), (String) m.get("description"), prompt,
                        strings(m.get("contextSources")),
                        new RoutingHints(new HashSet<>(strings(h.get("senderPatterns"))),
                                new HashSet<>(strings(h.get("senderRoles"))),
                                new HashSet<>(strings(h.get("keywords")))),
                        Boolean.TRUE.equals(m.get("fallback"))));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Email> inbox(Path inboxDir) {
        try (Stream<Path> files = Files.list(inboxDir)) {
            List<Email> out = new ArrayList<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> m = asMap(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                Object at = m.get("receivedAt");
                out.add(new Email((String) m.get("id"), (String) m.get("threadId"), (String) m.get("from"),
                        strings(m.get("to")), (String) m.get("subject"), (String) m.get("body"),
                        at == null ? null : Instant.parse((String) at), Map.of()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<String> strings(Object o) {
        if (o == null)
            return List.of();
        return ((List<?>) o).stream().map(String::valueOf).toList();
    }

    /** Analyseur JSON récursif minimal (objets, tableaux, chaînes, nombres, booléens, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length())
                throw new IllegalArgumentException("JSON : contenu en trop à la position " + j.i);
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i)))
                i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return true;
                case 'f':
                    i += 5;
                    return false;
                case 'n':
                    i += 4;
                    return null;
                default:
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0)
                        i++;
                    return Double.parseDouble(s.substring(start, i));
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            i++;
            return b.toString();
        }

        private void expect(char c) {
            if (s.charAt(i) != c)
                throw new IllegalArgumentException("JSON : '" + c + "' attendu à la position " + i);
            i++;
        }
    }
}
