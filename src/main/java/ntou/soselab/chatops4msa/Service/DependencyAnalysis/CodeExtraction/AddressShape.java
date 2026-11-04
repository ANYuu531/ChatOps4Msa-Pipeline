package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.ADDRESS_WORDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.CONNECTION_STRING_HOST_KEYS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.CONNECTION_WORDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.DB_SCHEMES;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.HOST_WORDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.INBOUND_WORDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.LIST_WORDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.LOOPBACK;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.NON_NETWORK_SCHEMES;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.QUEUE_SCHEMES;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.RESERVED_DOMAINS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.RESERVED_TLDS;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary.URL_SCHEMES;

/**
 * Convention ② — what a value that is an address looks like — in one place (2026-11-03).
 * The word lists it consults (schemes, key words, loopback and reserved names) live in
 * {@link ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary}.
 *
 * <p>The extractor and the merger each had their own copy: two loopback sets, two reserved-
 * domain tests, two scheme → kind tables, three host parsers, and five vocabularies of "this
 * key holds a host" (env names, Spring keys, command-line flags, .NET keys, placeholder
 * names), which disagreed — {@code FOO_URL=bar} was a host in an environment and
 * {@code foo.url: bar} was not one in a config file. Every reader now asks this class.
 *
 * <p>The shapes are the documented ones: a URI (RFC 3986) with its scheme saying what kind of
 * service it is, a JDBC / R2DBC URL, an ADO.NET-style {@code key=value;} connection string, a
 * scheme-less {@code host:port}; and a value under a key whose name says it is a host. Which
 * of the hosts are names in this deployment is convention ①, not decided here.
 */
public final class AddressShape {

    private AddressShape() {
    }

    /** A parsed address: the host with its dots, the port, the scheme, and the kind the shape states. */
    public record Address(String host, String port, String scheme, String kind) {
    }

    /** What a key's name says about its value. */
    public enum KeyKind {
        /** A host, bare or with a port ({@code DB_HOST}, {@code spring.data.mongodb.host}, {@code RabbitMq:HostName}). */
        HOST,
        /** One or more {@code host:port} ({@code bootstrap-servers}, {@code server-addr}, {@code addresses}). */
        LIST,
        /** A URL; a bare single word there is a host only when the deployment declares it (rule 45). */
        ADDRESS,
        /** A connection string ({@code ConnectionString}, {@code DSN}). */
        CONNECTION,
        NONE
    }

    /** The host key of a {@code key=value;key=value} connection string (Npgsql, SqlClient, ODBC). */
    public static final java.util.regex.Pattern CONNECTION_STRING_HOST = java.util.regex.Pattern.compile(
            "(?i)(?:^|;)\\s*(?:" + String.join("|", CONNECTION_STRING_HOST_KEYS) + ")\\s*=\\s*([^;,]+)");

    private static final java.util.regex.Pattern HOST_PORT = java.util.regex.Pattern.compile("[^\\s/=;]+:\\d{2,5}(/.*)?");

    /**
     * What a key's name says its value is. The key is split into words the way every
     * convention spells them — {@code .} (Spring), {@code :} and {@code __} (.NET),
     * {@code _} (environment variables), {@code -} (flags, kebab keys) and camelCase
     * ({@code HostName}, {@code baseUrl}) — and its last word, or last two joined
     * ({@code server-addr}, {@code bootstrap-servers}, {@code service-id}), is looked up.
     */
    public static KeyKind keyKind(String key) {
        if (key == null || key.isBlank() || isInboundKey(key) || isCommentKey(key)) return KeyKind.NONE;
        List<String> words = words(key.replaceAll("\\[\\d+]$", ""));
        if (words.isEmpty()) return KeyKind.NONE;
        String last = words.get(words.size() - 1);
        String two = words.size() > 1 ? words.get(words.size() - 2) + last : last;
        if (words.get(0).equals("connectionstrings")) return KeyKind.CONNECTION;
        if (CONNECTION_WORDS.contains(two) || CONNECTION_WORDS.contains(last)) return KeyKind.CONNECTION;
        if (LIST_WORDS.contains(two)) return KeyKind.LIST;
        if (HOST_WORDS.contains(two)) return KeyKind.HOST;
        if (LIST_WORDS.contains(last)) return KeyKind.LIST;
        if (HOST_WORDS.contains(last)) return KeyKind.HOST;
        if (ADDRESS_WORDS.contains(last)) return KeyKind.ADDRESS;
        return KeyKind.NONE;
    }

    /** Whether a key declares an inbound party (CORS {@code AllowedOrigins}, {@code RedirectUris}) rather than a target. */
    public static boolean isInboundKey(String key) {
        if (key == null) return false;
        for (String w : words(key.replaceAll("\\[\\d+]", ""))) if (INBOUND_WORDS.contains(w)) return true;
        return false;
    }

    /** A key a JSON or YAML file uses as a comment ({@code "//use_this_locally"}, {@code "_comment"}). */
    public static boolean isCommentKey(String key) {
        if (key == null) return false;
        for (String seg : key.split("[:.]")) {
            String t = seg.trim();
            if (t.startsWith("//") || t.startsWith("#") || t.toLowerCase(Locale.ROOT).startsWith("_comment")) return true;
        }
        return false;
    }

    /** How many of the key's last words say it holds an address (0, 1, or 2 for {@code server-addr}). */
    public static int hostWords(String key) {
        if (keyKind(key) == KeyKind.NONE) return 0;
        List<String> words = words(key.replaceAll("\\[\\d+]$", ""));
        String last = words.get(words.size() - 1);
        String two = words.size() > 1 ? words.get(words.size() - 2) + last : null;
        return two != null && (HOST_WORDS.contains(two) || LIST_WORDS.contains(two) || CONNECTION_WORDS.contains(two)) ? 2 : 1;
    }

    /** The lower-cased words of a key, split on every separator a configuration convention uses. */
    public static List<String> words(String key) {
        String spaced = key.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("^-+", "")
                .replaceAll("[._:\\-\\s/]+", " ")
                .toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        if (spaced.isEmpty()) return out;
        for (String w : spaced.split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Whether the value itself has an address's shape: a URI, a JDBC URL, a connection string, a {@code host:port}. */
    public static boolean isAddressShaped(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty()) return false;
        String l = v.toLowerCase(Locale.ROOT);
        return l.contains("://") || l.startsWith("jdbc:") || l.startsWith("r2dbc:") || isConnectionString(v) || HOST_PORT.matcher(v).matches();
    }

    /**
     * Whether a value under a host-named key can be a host: a DNS name or an IP, with an
     * optional port, or a list of them — not a flag ({@code prefer-ip-address: true}), a
     * number, a path or a sentence.
     */
    public static boolean isHostValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.matches("(?i)true|false|yes|no|on|off|null|none|nil|auto|default|\\d+")) return false;
        // A placeholder (${NACOS_HOST:127.0.0.1}:${NACOS_PORT:8848}) is filled in later and judged then.
        if (v.contains("${") || v.contains("{{")) return true;
        for (String part : v.split("\\s*[,;]\\s*")) {
            if (!part.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?")) return false;
        }
        return true;
    }

    /** Whether the value has a URI scheme ({@code ://}). */
    public static boolean hasScheme(String value) {
        return value != null && value.contains("://");
    }

    /** A {@code key=value;} connection string that names its host. */
    public static boolean isConnectionString(String value) {
        return value != null && value.indexOf('=') > 0 && value.indexOf(';') > 0 && CONNECTION_STRING_HOST.matcher(value).find();
    }

    /**
     * The address a value states, or null when it states none. The host keeps its dots and
     * is lower-cased; a {@code ${…}} / {@code {{…}}} placeholder host is "" (the value names
     * an address the tables may fill). A scheme chain ({@code zk:zookeeper:2181}) ends in
     * its address.
     */
    public static Address parse(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (isConnectionString(v)) {
            java.util.regex.Matcher m = CONNECTION_STRING_HOST.matcher(v);
            m.find();
            String hostPart = m.group(1).trim().replaceFirst("(?i)^tcp:", "");
            Address inner = parse(hostPart);
            return new Address(inner == null ? "" : inner.host(), inner == null ? null : inner.port(), null, "db");
        }
        String s = v.toLowerCase(Locale.ROOT).replace("\"", "").replace("'", "").trim();
        String kind = null;
        String scheme = null;
        if (s.startsWith("jdbc:")) {
            s = s.substring(5);
            kind = "db";
        } else if (s.startsWith("r2dbc:")) {
            s = s.substring(6);
            kind = "db";
        }
        int sep = s.indexOf("://");
        if (sep >= 0) {
            // A labelled listener (external:pulsar://h:6650) or a scheme chain: the scheme is
            // the last name before "://", and the authority is what follows it.
            String before = s.substring(0, sep);
            scheme = before.substring(before.lastIndexOf(':') + 1);
            // A URI whose authority is no network host: vault://secret/app is a secret path,
            // file:// and classpath: name files (RFC 8089; Spring's resource and config-import schemes).
            if (NON_NETWORK_SCHEMES.contains(scheme)) return null;
            if (kind == null && !scheme.isEmpty()) kind = DB_SCHEMES.contains(scheme) ? "db" : QUEUE_SCHEMES.contains(scheme) ? "queue" : null;
            s = s.substring(sep + 3);
        }
        // "http:${PROMETHEUS_ADDR}" — a URL scheme with no "//": an opaque URI (RFC 3986 §3),
        // no authority, so no host. Only the web schemes: "mongodb:27017" is a host named
        // mongodb with a port, and must stay one.
        int firstColon = s.indexOf(':');
        if (sep < 0 && firstColon > 0 && URL_SCHEMES.contains(s.substring(0, firstColon))) return null;
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        s = trailingHostPort(s);
        String port = null;
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && !s.startsWith("[")) {
            String p = s.substring(colon + 1).trim();
            if (p.matches("\\d{1,5}")) port = p;
            s = s.substring(0, s.indexOf(':'));
        }
        s = s.trim();
        if (s.contains("${") || s.contains("{{")) s = "";
        return new Address(s, port, scheme, kind);
    }

    /** The host of an address, dots kept, or "" when there is none (a placeholder, nothing parsable). */
    public static String host(String raw) {
        Address a = parse(raw);
        return a == null ? "" : a.host();
    }

    /** The explicit port of an address of two to five digits, or null. */
    public static String port(String raw) {
        Address a = parse(raw);
        return a == null || a.port() == null || a.port().length() < 2 ? null : a.port();
    }

    /**
     * What the value (or the key's own name) says the target is: {@code "db"}, {@code "queue"},
     * or null when the shape is neutral and the host's name decides. A {@code ConnectionStrings__X}
     * key, a connection string and a database scheme are a database; a broker scheme a queue.
     */
    public static String kind(String key, String value) {
        if (key != null) {
            String k = key.trim().toLowerCase(Locale.ROOT);
            if (k.startsWith("connectionstrings__") || k.startsWith("connectionstrings:") || k.startsWith("connectionstrings.")) return "db";
        }
        Address a = parse(value);
        return a == null ? null : a.kind();
    }

    /** The caller itself: loopback, the Docker host alias, the cloud metadata server, the reserved {@code .internal} domain. */
    public static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return LOOPBACK.contains(h) || h.endsWith(".internal") || h.startsWith("169.254.");
    }

    /**
     * A host under a domain reserved for documentation, testing or mDNS — nobody's address —
     * or a sample's placeholder written in its place ({@code xxx.servicebus.windows.net},
     * {@code xxxxxxxx-xxxx-…}, {@code your-server}).
     */
    public static boolean isReservedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.matches("x{3,}([.-].*)?|.*\\bx{4,}\\b.*|your[-_.].*|.*[<>].*")) return true;
        if (h.endsWith(".svc.cluster.local") || h.endsWith(".cluster.local")) return false;
        for (String tld : RESERVED_TLDS) if (h.endsWith(tld)) return true;
        for (String d : RESERVED_DOMAINS) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    /**
     * A URI scheme chain ends in the address: Pulsar's {@code metadata-store:zk:zookeeper:2181}
     * and {@code zk:zookeeper:2181} name zookeeper, not the scheme in front. When a
     * scheme-less authority has several colons and ends in a port, the host is the segment
     * before the port (B7, 2026-10-27).
     */
    public static String trailingHostPort(String s) {
        if (s == null || s.indexOf(':') == s.lastIndexOf(':') || s.contains("[")) return s;
        String[] parts = s.split(":");
        if (parts.length < 3 || !parts[parts.length - 1].matches("\\d{1,5}")
                || !parts[parts.length - 2].matches("[A-Za-z0-9][A-Za-z0-9.-]*")) return s;
        // Every segment in front of the host must be a scheme name (zk, metadata-store).
        for (int i = 0; i < parts.length - 2; i++) if (!parts[i].matches("[A-Za-z][A-Za-z0-9+.-]*")) return s;
        RuleTrace.hit(36, "scheme-chain " + s);
        return parts[parts.length - 2] + ":" + parts[parts.length - 1];
    }
}
