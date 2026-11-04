package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.yaml.snakeyaml.Yaml;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rows every descriptor reader emits, and the shape helpers they share (convention ②,
 * {@link AddressShape}): an {@code env-address} row per address a declared value names, a
 * {@code workload-env} row per injection a workload receives, a command-line address. The
 * readers only know their file format; what an address is, and which scope a value holds
 * in, is said here once.
 */
final class DescriptorRows {

    static final int MAX_VALUE_LENGTH = 200;

    /** The namespace of the k8s object being read (declared, or applied by a script), or null. */
    String currentNamespace;
    /** The Helm chart the manifest being read was rendered from (rule 52), or null for a plain manifest. */
    String renderingChart;

    /**
     * Records an {@code env-address} entry when the value is an address (a
     * {@code host:port} or a URL) and its host is a plausible name. Non-address
     * values (a bank name, a log level, a boolean) are ignored. When the value comes
     * from a ConfigMap, the ConfigMap's name rides along so a workload's
     * {@code envFrom} can be joined back to the hosts it receives.
     */
    void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger) {
        recordEnvAddress(key, value, configMap, workload, relative, ledger, null);
    }

    /** @param via the file the value was forwarded from ({@code .env}), or null when stated in place */
    void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger, String via) {
        recordEnvAddress(key, value, configMap, workload, relative, ledger, via, false);
    }

    void recordEnvAddress(String key, String value, String configMap, String workload,
                                  String relative, EdgeLedger ledger, String via, boolean keepDomain) {
        if (key == null || value == null) return;
        key = key.trim();
        value = value.trim();
        if (key.isEmpty() || value.isEmpty()) return;
        List<String[]> list = addressList(key, value, keepDomain);
        if (!list.isEmpty()) {
            // Rule 55: a value listing several services yields one row per address.
            for (String[] a : list) {
                RuleTrace.hit(55, "list " + key + " " + a[0]);
                recordEnvAddressHost(key, a[0], a[1], configMap, workload, relative, ledger, via, keepDomain, true);
            }
            return;
        }
        String host = addressHost(key, value, keepDomain);
        if (host == null) return;
        recordEnvAddressHost(key, host, value, configMap, workload, relative, ledger, via, keepDomain, false);
    }

    void recordEnvAddressHost(String key, String host, String value, String configMap, String workload,
                                      String relative, EdgeLedger ledger, String via, boolean keepDomain, boolean listed) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", key);
        fields.put("host", host);
        if (listed) fields.put("list", "true");
        if (configMap != null) fields.put("configmap", configMap);
        // The scope the value holds in: a workload's own env block, or a Dockerfile's
        // module (marked "dockerfile"; the merger resolves the module from the file).
        if (workload != null) fields.put("workload", workload);
        if (via != null) fields.put("via", via);
        String kind = kindHint(key, value);
        if (kind != null) fields.put("kind", kind);
        if (bareUnderAddressKey(key, value)) fields.put("bare", "true");
        if (renderingChart != null) fields.put("helm", renderingChart);
        if (currentNamespace != null) fields.put("namespace", currentNamespace);
        String hostNs = keepDomain ? null : NameTable.namespaceLabel(value);
        if (hostNs != null) fields.put("host_ns", hostNs);
        ledger.add("env-address", fields, relative, -1, "High (k8s manifest)");
    }

    /**
     * A single word under a URL / URI / endpoint key ({@code SEARCH_URL=elasticsearch}): a host
     * only if the deployment declares that name (rule 45), which the merger checks. A host-named
     * key ({@code DB_HOST}) takes a bare name as is (rule 5); a dotted name is a domain.
     */
    static boolean bareUnderAddressKey(String key, String value) {
        return value != null && AddressShape.keyKind(key) == AddressShape.KeyKind.ADDRESS
                && value.trim().matches("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(:\\d{1,5})?");
    }

    /** {host, the token it came from} per address of a value: each of a list (rule 55), else the one address; empty if none. */
    static List<String[]> hostsOf(String name, String value, boolean keepDomain) {
        List<String[]> list = addressList(name, value, keepDomain);
        if (!list.isEmpty()) return list;
        String host = addressHost(name, value, keepDomain);
        return host == null ? List.of() : List.<String[]>of(new String[]{host, value});
    }

    /**
     * Rule 55: a value that lists several addresses — separated by {@code ,} {@code ;} or
     * whitespace, as travels' {@code PORTAL_SERVICES="voyages.fr;http://voyages.travel-portal:8000,
     * viaggi.it;http://viaggi…"} does — names each of them; only the address-shaped tokens (a
     * URL, a {@code host:port}) count, so the labels between them are not hosts. A list of
     * replicas of one cluster ({@code nacos-0…, nacos-1…, nacos-2…}: the names differ only in
     * digits) is one target, as rule 27 has always read it: an empty result leaves the value to
     * the single-address rules. Returns {host, token} per distinct service.
     */
    static List<String[]> addressList(String name, String value, boolean keepDomain) {
        if (value == null || !value.matches("(?s).*[,;\\s].*")) return List.of();
        List<String[]> out = new java.util.ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String token : splitAddresses(value)) {
            if (!(token.contains("://") || token.matches("[^\\s/]+:\\d{2,5}(/.*)?"))) continue;
            String host = addressHost(name, token, keepDomain);
            if (host != null && seen.add(host)) out.add(new String[]{host, token});
        }
        if (out.size() < 2) return List.of();
        Set<String> stems = new java.util.HashSet<>();
        for (String[] a : out) stems.add(a[0].replaceAll("\\d+", ""));
        return stems.size() == 1 ? List.of() : out;
    }

    /**
     * What the VALUE (or the variable's own name) says the target is, when it says
     * anything: a {@code key=value;} connection string and a {@code ConnectionStrings__X}
     * variable are a database whatever the host is called ({@code catalogdb} carries no
     * database hint; .NET names its stores freely), an {@code amqp://} URL is a broker.
     * Null when the shape is neutral — the merger then classifies by the host's name as
     * before (2026-10-26, run-aspnetcore-microservices).
     */
    static String kindHint(String name, String value) {
        return AddressShape.kind(name, value);
    }

    static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static final java.util.regex.Pattern CONNECTION_STRING_HOST = AddressShape.CONNECTION_STRING_HOST;

    /**
     * The host of a value that is an address (a URL or {@code host:port}), or null for
     * anything else — {@code "true"}, {@code "v0"}, a log level. A bare word is a valid
     * DNS label, so the shape check has to come before the host extraction — unless the
     * variable's own name says it holds a host ({@code DB_HOST: teastore-db}, the port in
     * a sibling {@code DB_PORT}), which is how TeaStore and Robot Shop wire everything.
     */
    static String addressHost(String name, String value) {
        return addressHost(name, value, false);
    }

    /** @param keepDomain see {@link #hostOf(String, boolean)} */
    static String addressHost(String name, String value, boolean keepDomain) {
        String v = value == null ? "" : value.trim();
        // An ADO.NET / Npgsql connection string names its host as a key=value pair:
        // Host=astronomy-db;Username=…;Database=… (2026-10-26, OpenTelemetry demo's accounting).
        if (v.indexOf('=') > 0 && v.indexOf(';') > 0) {
            java.util.regex.Matcher m = CONNECTION_STRING_HOST.matcher(v);
            if (RuleTrace.hitIf(m.find(), 32, "connstr " + name)) return hostOf(m.group(1).replaceFirst("(?i)^tcp:", ""), keepDomain);
        }
        if (AddressShape.isAddressShaped(v)) return hostOf(v, keepDomain);
        // The variable's name says it holds a host, so the value must look like one: a DNS
        // name, optionally with a port. SW_AGENT_NAME_SERVER: agent::app is a SkyWalking
        // service name, not an address, and its first segment is no host (2026-10-27).
        if (AddressShape.keyKind(name) != AddressShape.KeyKind.NONE) {
            if (RuleTrace.hitIf(!AddressShape.isHostValue(v) || v.matches(".*[,;].*"), 5, "not-a-host " + name + "=" + v)) return null;
            RuleTrace.hit(5, "env " + name + "=" + v);
            return hostOf(v, keepDomain);
        }
        return null;
    }


    /** The address tokens of a value that may list several (B6): split on {@code ,} {@code ;} and whitespace. */
    static List<String> splitAddresses(String value) {
        List<String> out = new java.util.ArrayList<>();
        for (String p : value.split("[,;\\s]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    void recordCommandAddress(String workload, String[] hit, String key, String relative, EdgeLedger ledger) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        fields.put("host", hit[1]);
        fields.put("name", key);
        fields.put("via", "command");
        if ("bare".equals(hit[3])) fields.put("bare", "true");
        String kind = kindHint(key, hit[2]);
        if (kind != null) fields.put("kind", kind);
        if (renderingChart != null) fields.put("helm", renderingChart);
        ledger.add("workload-env", fields, relative, -1, "High (container command line)");
    }

    /**
     * Records a {@code workload-env} entry: which workload receives which ConfigMap
     * ({@code envFrom} / {@code configMapKeyRef}) or which literal host. This is the
     * deterministic answer to "does transactionhistory get SPRING_DATASOURCE_URL?"
     * when no line of its code reads the variable (Spring Boot binds it implicitly).
     */
    void recordWorkloadEnv(String workload, String configMap, String host, String envName,
                                   String relative, EdgeLedger ledger) {
        recordWorkloadEnv(workload, configMap, host, envName, null, relative, ledger);
    }

    /** @param value the raw env value, when known, so its shape can say what kind of target the host is */
    void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger) {
        recordWorkloadEnv(workload, configMap, host, envName, value, relative, ledger, null);
    }

    /**
     * @param shared the env_file the value came from when it was injected wholesale rather than
     *               chosen for this workload, else null: then the value is this workload's only
     *               if it reads the key (convention ③, {@code key-read} rows)
     */
    void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger, String shared) {
        recordWorkloadEnv(workload, configMap, host, envName, value, relative, ledger, shared, null);
    }

    /** @param configMapKey the one ConfigMap key a {@code configMapKeyRef} selects, else null */
    void recordWorkloadEnv(String workload, String configMap, String host, String envName, String value,
                                   String relative, EdgeLedger ledger, String shared, String configMapKey) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("workload", workload);
        if (configMap != null && !configMap.isBlank()) fields.put("configmap", configMap.trim());
        if (host != null && !host.isBlank()) fields.put("host", host.trim());
        if (fields.size() < 2) return;
        if (envName != null && !envName.isBlank()) fields.put("name", envName.trim());
        if (shared != null) fields.put("shared", shared);
        if (configMapKey != null) fields.put("configmap_key", configMapKey);
        if (bareUnderAddressKey(envName, value)) fields.put("bare", "true");
        String kind = kindHint(envName, value);
        if (kind != null) fields.put("kind", kind);
        if (renderingChart != null) fields.put("helm", renderingChart);
        if (currentNamespace != null) fields.put("namespace", currentNamespace);
        String hostNs = value == null ? null : NameTable.namespaceLabel(value);
        if (hostNs != null) fields.put("host_ns", hostNs);
        ledger.add("workload-env", fields, relative, -1, "High (k8s manifest)");
    }

    /** One DNS label, and a dotted name of several. */
    static final String DNS_LABEL = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";
    static final String DNS_NAME = DNS_LABEL + "(\\." + DNS_LABEL + ")+";

    /** The in-cluster DNS suffixes a Kubernetes Service name may carry; the label before them is the namespace. */
    static final String[] CLUSTER_SUFFIXES = NameTable.CLUSTER_SUFFIXES;

    /**
     * @param keepDomain true for a Compose environment, where there are no namespaces: a dotted
     *                   name is a domain (or a dotted Compose service name, {@code catalog.api})
     *                   and is kept whole. False for a k8s manifest, where a two-label name is the
     *                   cluster DNS short form {@code <svc>.<ns>} and only then is cut to the
     *                   service. In both worlds only a real DNS suffix is ever stripped: a cluster
     *                   suffix takes its namespace label with it, a reserved TLD is kept whole so
     *                   the merger drops the host as a whole ({@code api.yas.local} used to come
     *                   out as a service called {@code api}; 2026-10-26), and a domain of three or
     *                   more labels is never cut to its first label.
     */
    static String hostOf(String raw, boolean keepDomain) {
        String s = AddressShape.host(raw);         // scheme, userinfo, path, port off (convention ②)
        if (s.isEmpty() || s.contains("$") || s.contains("[") || s.contains("{")) return null;
        if (s.matches("[0-9.]+")) return null;     // an IP or a bare number is not a name
        boolean cluster = false;
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                RuleTrace.hit(36, "cluster-suffix " + s);
                s = s.substring(0, s.length() - suffix.length());
                cluster = true;
                break;
            }
        }
        int lastDot = s.lastIndexOf('.');
        if (cluster) {
            if (lastDot > 0) s = s.substring(0, lastDot);            // <svc>.<ns>.svc.cluster.local -> <svc>
        } else if (!keepDomain && lastDot > 0 && s.indexOf('.') == lastDot && !AddressShape.isReservedHost(s)) {
            RuleTrace.hit(36, "ns-strip " + s);
            s = s.substring(0, lastDot);                              // k8s manifest: <svc>.<ns> -> <svc>
        } else if (lastDot > 0) {
            RuleTrace.hit(36, "keep-dotted " + s);                    // a domain, a reserved TLD or a dotted Compose name: kept whole
        }
        if (s.matches(DNS_LABEL)) return s;
        return s.matches(DNS_NAME) ? s : null;
    }

}
