package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.NLPService.EmbeddingClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Shared plumbing for the offline-by-default calibration experiments: the hold-out
 * file, the three projects' node sets, and an embedding cache.
 *
 * Not a test itself (the name matches no surefire pattern).
 */
final class CalibrationSupport {

    static final Path OUT_DIR = Path.of("target/qa-calibration");
    static final Path CACHE_FILE = OUT_DIR.resolve("embeddings-cache.json");
    static final String HOLDOUT_RESOURCE = "/qa/router-holdout.tsv";
    static final String LABELS_RESOURCE = "/qa/retrieval-labels.tsv";

    /** Expected-intent token for "the graph cannot answer this": correct iff the router is unsure. */
    static final String NONE = "none";

    /**
     * The node ids production would hold for each project. Masking and mention
     * detection depend on the ids, so a hold-out question is only realistic against the
     * graph it would actually be asked about. Bank of Anthos and sock-shop are the
     * deployed sets; train-ticket is every node of docs/train-ticket-greenfield-graph.mmd
     * except the extraction artifact "null" — including the noisy ones (ts-common, bin),
     * because production would hold them too.
     */
    static final Map<String, List<String>> PROJECT_NODES = new LinkedHashMap<>();

    static {
        PROJECT_NODES.put("bank-of-anthos", List.of(
                "frontend", "userservice", "contacts", "ledgerwriter", "balancereader",
                "transactionhistory", "accounts-db", "ledger-db", "istio-ingressgateway"));
        PROJECT_NODES.put("train-ticket", List.of(
                "ts-contacts-service", "ts-admin-user-service", "ts-notification-service", "ts-cancel-service",
                "ts-config-service", "ts-assurance-service", "ts-order-other-service", "ts-route-service",
                "ts-price-service", "ts-preserve-service", "ts-news-service", "ts-avatar-service",
                "ts-ui-dashboard", "ts-security-service", "ts-consign-service", "ts-train-service",
                "ts-order-service", "ts-ticket-office-service", "ts-verification-code-service", "ts-food-service",
                "ts-station-food-service", "ts-rebook-service", "ts-train-food-service", "ts-admin-basic-info-service",
                "ts-food-delivery-service", "ts-consign-price-service", "ts-wait-order-service", "ts-basic-service",
                "ts-preserve-other-service", "ts-common", "ts-station-service", "ts-auth-service",
                "ts-user-service", "ts-route-plan-service", "ts-gateway-service", "ts-payment-service",
                "ts-inside-payment-service", "ts-ui-test", "ms-monitoring-core", "bin", "json2shiviz",
                "ts-voucher-service", "ts-delivery-service", "ts-seat-service", "ts-execute-service",
                "ts-travel-plan-service", "ts-travel2-service", "ts-travel-service", "ts-admin-order-service",
                "ts-admin-travel-service", "ts-admin-route-service", "github.com", "rest-service-external"));
        PROJECT_NODES.put("sock-shop", List.of(
                "front-end", "orders", "payment", "shipping", "carts", "catalogue", "user", "queue-master",
                "rabbitmq", "carts-db", "orders-db", "catalogue-db", "user-db"));
    }

    private CalibrationSupport() {
    }

    // ---------- hold-out ----------

    static final class HoldoutRow {
        final String project;
        final String question;
        /** Expected intents as written, "none" and not-yet-existing intents included. */
        final Set<String> expected;
        final String note;

        HoldoutRow(String project, String question, Set<String> expected, String note) {
            this.project = project;
            this.question = question;
            this.expected = expected;
            this.note = note;
        }

        String lang() {
            return language(question);
        }
    }

    static List<HoldoutRow> loadHoldout() throws Exception {
        List<HoldoutRow> rows = new ArrayList<>();
        for (String[] cols : readTsv(HOLDOUT_RESOURCE, "project")) {
            if (cols.length < 3) throw new IllegalStateException("hold-out row needs >= 3 columns: " + String.join(" | ", cols));
            Set<String> expected = new LinkedHashSet<>();
            for (String e : cols[2].split("\\|")) if (!e.isBlank()) expected.add(e.trim());
            rows.add(new HoldoutRow(cols[0].trim(), cols[1].trim(), expected, cols.length > 3 ? cols[3].trim() : ""));
        }
        return rows;
    }

    /** Non-comment, non-header lines of a classpath TSV, split on tabs. */
    static List<String[]> readTsv(String resource, String headerFirstColumn) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = CalibrationSupport.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing test resource " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split("\t", -1);
                if (cols[0].trim().equals(headerFirstColumn)) continue;
                out.add(cols);
            }
        }
        return out;
    }

    static DependencyGraph projectGraph(String project) {
        List<String> ids = PROJECT_NODES.get(project);
        if (ids == null) throw new IllegalArgumentException("unknown project " + project);
        DependencyGraph graph = new DependencyGraph(project);
        for (String id : ids) graph.addNode(id, DependencyGraph.classifyKind(id));
        return graph;
    }

    /** The current intent names, read at run time so a newly added intent needs no change here. */
    static Set<String> intentNames() {
        Set<String> names = new LinkedHashSet<>();
        for (SemanticRouter.Intent i : SemanticRouter.INTENTS) names.add(i.name);
        return names;
    }

    static String language(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) ? "zh" : "en";
    }

    /** Lower-cased, whitespace / punctuation / symbols removed: what "the same sentence" means for duplicates. */
    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ---------- key / client ----------

    static Properties applicationProperties() {
        Properties p = new Properties();
        Path props = Path.of("src/main/resources/application.properties");
        if (!Files.exists(props)) return p;
        try (FileInputStream in = new FileInputStream(props.toFile())) {
            p.load(in);
        } catch (Exception ignored) {
            // an unreadable file is the same as no key: the experiment will say so and skip
        }
        return p;
    }

    static String embeddingModel(Properties p) {
        return p.getProperty("openai.api.embedding-model", "text-embedding-3-small");
    }

    /** A client when a key is configured, else {@code null} (the cache may still cover every text). */
    static EmbeddingClient clientOrNull(Properties p) {
        String key = p.getProperty("openai.api.key", "");
        if (key.isBlank()) return null;
        String url = p.getProperty("openai.api.embedding-url", "");
        if (url.isBlank()) url = EmbeddingClient.deriveUrl(p.getProperty("openai.api.url"));
        return new EmbeddingClient(url, key, embeddingModel(p));
    }

    // ---------- embedding cache ----------

    /**
     * Embeds each distinct text once per model, ever: vectors are kept in a JSON file
     * keyed by sha256(model + text), so re-running a sweep — or sweeping a different
     * grid — replays the stored vectors instead of paying for the API again. It also
     * pins the experiment: a re-run on the same cache computes the same numbers.
     *
     * Changing the embedding model changes the key, so a stale vector is never reused
     * for a different model.
     */
    static final class EmbeddingCache implements SemanticRouter.Embedder {
        private final Path file;
        private final String model;
        private final SemanticRouter.Embedder remote;
        private final Map<String, double[]> vectors = new LinkedHashMap<>();
        private final Map<String, String> texts = new LinkedHashMap<>();
        int hits;
        int misses;
        int remoteCalls;

        EmbeddingCache(Path file, String model, SemanticRouter.Embedder remote) throws Exception {
            this.file = file;
            this.model = model;
            this.remote = remote;
            if (Files.exists(file)) {
                JSONObject entries = new JSONObject(Files.readString(file)).optJSONObject("entries");
                if (entries != null) {
                    for (String key : entries.keySet()) {
                        JSONObject e = entries.getJSONObject(key);
                        JSONArray v = e.getJSONArray("vector");
                        double[] vec = new double[v.length()];
                        for (int i = 0; i < vec.length; i++) vec[i] = v.getDouble(i);
                        vectors.put(key, vec);
                        texts.put(key, e.optString("text"));
                    }
                }
            }
        }

        static String key(String model, String text) {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(sha.digest((model + "\n" + text).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        boolean covers(List<String> wanted) {
            for (String t : wanted) if (!vectors.containsKey(key(model, t))) return false;
            return true;
        }

        /** In order; {@code null} when a text is missing and the remote call is unavailable or fails. */
        @Override
        public List<double[]> embed(List<String> wanted) {
            List<String> missing = new ArrayList<>();
            for (String t : wanted) {
                String k = key(model, t);
                if (vectors.containsKey(k)) hits++;
                else if (!missing.contains(t)) missing.add(t);
            }
            if (!missing.isEmpty()) {
                misses += missing.size();
                if (remote == null) return null;
                remoteCalls++;
                List<double[]> fresh = remote.embed(missing);
                if (fresh == null || fresh.size() != missing.size()) return null;
                for (int i = 0; i < missing.size(); i++) {
                    String k = key(model, missing.get(i));
                    vectors.put(k, fresh.get(i));
                    texts.put(k, missing.get(i));
                }
                save();
            }
            List<double[]> out = new ArrayList<>();
            for (String t : wanted) out.add(vectors.get(key(model, t)));
            return out;
        }

        private void save() {
            try {
                JSONObject entries = new JSONObject();
                for (Map.Entry<String, double[]> e : vectors.entrySet()) {
                    JSONArray v = new JSONArray();
                    for (double d : e.getValue()) v.put(d);
                    entries.put(e.getKey(), new JSONObject().put("model", model).put("text", texts.get(e.getKey())).put("vector", v));
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, new JSONObject().put("entries", entries).toString());
            } catch (Exception e) {
                System.out.println("[WARNING] embedding cache not saved: " + e.getMessage());
            }
        }
    }

    // ---------- small output helpers ----------

    static String csvRow(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String s = cells[i] == null ? "" : cells[i] instanceof Double d ? fmt(d) : String.valueOf(cells[i]);
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
            sb.append(s);
        }
        return sb.append('\n').toString();
    }

    static String fmt(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? "" : String.format(Locale.ROOT, "%.3f", d);
    }

    /** Nearest-rank quantile of an ascending list; NaN when empty. */
    static double quantile(List<Double> ascending, double p) {
        if (ascending.isEmpty()) return Double.NaN;
        int rank = (int) Math.ceil(p * ascending.size());
        return ascending.get(Math.min(ascending.size() - 1, Math.max(0, rank - 1)));
    }
}
