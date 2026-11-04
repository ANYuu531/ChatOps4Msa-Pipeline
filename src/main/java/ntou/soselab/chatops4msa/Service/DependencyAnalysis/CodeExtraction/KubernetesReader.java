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
 * Kubernetes manifests and what is rendered from Helm charts: workloads, Services and their
 * selectors, namespaces (declared, or applied by the repo's own kubectl scripts), ConfigMaps and
 * container env, Istio VirtualServices and APISIX routes.
 */
final class KubernetesReader {

    private final DescriptorRows rows;

    KubernetesReader(DescriptorRows rows) {
        this.rows = rows;
    }

    /** Fresh per extraction: the namespaces seen, and the ones the repo's scripts apply manifests into. */
    void prepare(Path root, EdgeLedger ledger) {
        namespaces.clear();
        appliedNamespaces.clear();
        appliedNamespaces.putAll(appliedNamespaces(root, ledger));
    }

    static boolean underAny(Path file, Set<Path> dirs) {
        Path f = file.normalize();
        for (Path d : dirs) if (f.startsWith(d)) return true;
        return false;
    }

    /**
     * Reads {@code env-address} entries ({@code name -> host}) from a Kubernetes
     * manifest: every {@code ConfigMap} data value and every literal container
     * {@code env[].value} that names a {@code host:port} or a URL. This is the
     * deterministic source for resolving env-indirected call targets (e.g.
     * {@code TRANSACTIONS_API_ADDR -> ledgerwriter}) with no cluster running.
     * Multi-document YAML (k8s files routinely use {@code ---}) is fully scanned.
     */
    boolean extractK8sEnvAddresses(Path file, String relative, EdgeLedger ledger) throws Exception {
        return extractK8sYaml(Files.readString(file, StandardCharsets.UTF_8), relative, ledger, null);
    }

    /** @param chart the Helm chart the text was rendered from, or null for a plain manifest */
    boolean extractK8sYaml(String text, String relative, EdgeLedger ledger, String chart) throws Exception {
        boolean anyK8s = false;
        rows.renderingChart = chart;
        try {
            for (Object doc : new Yaml().loadAll(text)) {
                if (!(doc instanceof Map<?, ?> root)) continue;
                if (root.get("apiVersion") == null || root.get("kind") == null) continue; // not a k8s object
                anyK8s = true;
                String kind = String.valueOf(root.get("kind"));
                String name = metadataName(root);

                // The namespaces the manifests declare — as a Namespace object or as any
                // object's metadata.namespace. A host written <svc>.<ns> is in-cluster only
                // when <ns> is one of these; the merger strips exactly that label and no
                // other (2026-10-26).
                String namespace = "Namespace".equals(kind) ? name : metadataNamespace(root);
                // A manifest with no metadata.namespace goes where the repo's own apply command
                // puts it (`kubectl apply -f travel_agency.yaml -n travel-agency`, rule 54).
                if (namespace == null && !"Namespace".equals(kind)) namespace = appliedNamespaces.get(relative);
                rows.currentNamespace = "Namespace".equals(kind) ? null : namespace;
                if (namespace != null && namespaces.add(namespace)) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", namespace);
                    ledger.add("k8s-namespace", fields, relative, -1, "High (k8s manifest)");
                }

                // The workload inventory: what the cluster would actually run. With no
                // runtime graph this is the only vocabulary that carries the real names —
                // a Maven module called microservice-kubernetes-demo-catalog deploys as
                // "catalog", and tools.descartes.teastore.webui as "teastore-webui".
                if (name != null && name.contains("$")) name = null; // a rendered name nothing could fill
                if (WORKLOAD_KINDS.contains(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    fields.put("kind", kind);
                    if (chart != null) fields.put("helm", chart);
                    String labels = podLabels(root);
                    if (labels != null) fields.put("labels", labels);
                    String image = firstImage(root);
                    if (image != null && ComposeReader.imageStem(image) != null) {
                        fields.put("image", ComposeReader.imageStem(image));
                        String registry = ComposeReader.imageRegistry(image);
                        if (registry != null) fields.put("image_registry", registry);
                    }
                    if (namespace != null) fields.put("namespace", namespace);
                    ledger.add("k8s-workload", fields, relative, -1, "High (k8s manifest)");
                }
                // A Service is the name callers use. It is recorded beside the workloads so
                // the merger can tell a versioned deployment (reviews-v1, the Istio bookinfo
                // convention) from the service it is a version of (reviews) — the Deployment
                // carries the version, the Service does not (2026-10-23).
                if ("Service".equals(kind) && name != null) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("name", name);
                    if (chart != null) fields.put("helm", chart);
                    Object spec = root.get("spec");
                    if (spec instanceof Map<?, ?> sp && sp.get("selector") instanceof Map<?, ?> sel && !sel.isEmpty()) {
                        fields.put("selector", labelString(sel));
                    }
                    if (namespace != null) fields.put("namespace", namespace);
                    ledger.add("k8s-service", fields, relative, -1, "High (k8s manifest)");
                }

                // Rule 56: Istio ingress routing — a Gateway's selector names the ingress workload,
                // a VirtualService bound to a gateway routes it to its destinations.
                if ("Gateway".equals(kind) && String.valueOf(root.get("apiVersion")).startsWith("networking.istio.io")) {
                    String workload = gatewayWorkload(root);
                    if (name != null && workload != null) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("name", name);
                        fields.put("workload", workload);
                        if (namespace != null) fields.put("namespace", namespace);
                        ledger.add("k8s-gateway", fields, relative, -1, "High (istio gateway)");
                    }
                }
                if ("VirtualService".equals(kind) && String.valueOf(root.get("apiVersion")).startsWith("networking.istio.io")) {
                    extractVirtualService(root, namespace, relative, ledger);
                }
                if ("ConfigMap".equals(kind) && root.get("data") instanceof Map<?, ?> data) {
                    for (Map.Entry<?, ?> e : data.entrySet()) {
                        rows.recordEnvAddress(String.valueOf(e.getKey()), String.valueOf(e.getValue()), name, null, relative, ledger);
                    }
                } else {
                    // Deployment / StatefulSet / Pod: containers[*].env[*] with a literal value.
                    // The value is scoped to THIS workload: four manifests may each declare
                    // MONGO_CLIENT_HOST with a different database, and only the workload's own
                    // value is what its code reads.
                    for (Object env : containerEnvEntries(root)) {
                        if (!(env instanceof Map<?, ?> pair)) continue;
                        Object k = pair.get("name");
                        Object v = pair.get("value"); // valueFrom (configMapKeyRef) has no literal here
                        if (k != null && v != null) {
                            rows.recordEnvAddress(String.valueOf(k), String.valueOf(v), null, name, relative, ledger);
                            for (String[] hv : DescriptorRows.hostsOf(String.valueOf(k), String.valueOf(v), false)) {
                                if (name != null) rows.recordWorkloadEnv(name, null, hv[0], String.valueOf(k), hv[1], relative, ledger);
                            }
                        } else if (k != null && pair.get("valueFrom") instanceof Map<?, ?> from
                                && from.get("configMapKeyRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                            if (name != null) {
                                // configMapKeyRef picks one key: that value, and only it, is this workload's own (convention ③).
                                rows.recordWorkloadEnv(name, String.valueOf(ref.get("name")), null, String.valueOf(k), null, relative, ledger,
                                        null, ref.get("key") == null ? null : String.valueOf(ref.get("key")));
                            }
                        }
                    }
                    // Rule 53: the containers' command / args.
                    if (name != null) {
                        for (Object cmd : containerCommands(root)) {
                            for (String[] hit : ComposeReader.commandAddresses(cmd, null)) {
                                RuleTrace.hit(53, "k8s " + name + " " + hit[0] + "=" + hit[1]);
                                rows.recordCommandAddress(name, hit, "command", relative, ledger);
                            }
                        }
                    }
                    // envFrom: the whole ConfigMap is injected — this is the wiring that says
                    // WHICH workload receives SPRING_DATASOURCE_URL, which no code line reads.
                    for (String configMap : containerEnvFromConfigMaps(root)) {
                        if (name != null) rows.recordWorkloadEnv(name, configMap, null, null, relative, ledger);
                    }
                }
            }
        } finally {
            rows.renderingChart = null;
            rows.currentNamespace = null;
        }
        return anyK8s;
    }

    /** Repo-relative manifest path → the namespace the repo's own scripts apply it into. */
    final Map<String, String> appliedNamespaces = new LinkedHashMap<>();

    /** {@code kubectl apply|create|replace … -f <file> … -n|--namespace <ns>} on one line. */
    static final java.util.regex.Pattern KUBECTL_APPLY = java.util.regex.Pattern.compile(
            "\\bkubectl\\s+(?:[^\\n]*?\\s)?(?:apply|create|replace)\\b([^\\n]*)");
    static final java.util.regex.Pattern KUBECTL_FILE = java.util.regex.Pattern.compile(
            "(?:^|\\s)(?:-f|--filename)(?:=|\\s+)(\\S+)");
    static final java.util.regex.Pattern KUBECTL_NS = java.util.regex.Pattern.compile(
            "(?:^|\\s)(?:-n|--namespace)(?:=|\\s+)([a-z0-9]([a-z0-9-]*[a-z0-9])?)(?=\\s|$)");

    /**
     * Rule 54: where a manifest that declares no namespace is applied. The repo's deploy
     * scripts (shell, Makefile) say it: {@code kubectl apply -f travel_agency.yaml -n
     * travel-agency}. The file is resolved against the script's directory, then the repo root;
     * a namespace written as a variable is not taken; a file applied into two namespaces has
     * none. These namespaces are declared namespaces like a Namespace object's.
     */
    Map<String, String> appliedNamespaces(Path root, EdgeLedger ledger) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        List<Path> scripts = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".sh") || n.endsWith(".bash") || n.equals("makefile") || n.endsWith(".mk");
                    })
                    .filter(p -> !SourceScanner.isIgnored(root, p))
                    .forEach(scripts::add);
        } catch (Exception ignored) {
            // unwalkable tree: no scripts
        }
        for (Path script : scripts) {
            String text;
            try {
                text = Files.readString(script, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            java.util.regex.Matcher m = KUBECTL_APPLY.matcher(text);
            while (m.find()) {
                String args = m.group(1);
                java.util.regex.Matcher ns = KUBECTL_NS.matcher(args);
                if (!ns.find()) continue;
                java.util.regex.Matcher f = KUBECTL_FILE.matcher(args);
                while (f.find()) {
                    String file = f.group(1).replaceAll("^[\"']|[\"']$", "");
                    if (file.contains("$") || file.contains("://")) continue;
                    for (Path base : new Path[]{script.getParent(), root}) {
                        Path p = base.resolve(file).normalize();
                        if (Files.isRegularFile(p) && p.startsWith(root.normalize())) {
                            found.computeIfAbsent(SourceScanner.relative(root, p), k -> new java.util.LinkedHashSet<>()).add(ns.group(1));
                            break;
                        }
                    }
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : found.entrySet()) {
            if (e.getValue().size() != 1) continue;
            String ns = e.getValue().iterator().next();
            out.put(e.getKey(), ns);
            RuleTrace.hit(54, "applied " + e.getKey() + " -n " + ns);
            if (namespaces.add(ns)) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("name", ns);
                fields.put("via", "kubectl -n");
                ledger.add("k8s-namespace", fields, e.getKey(), -1, "High (deploy script)");
            }
        }
        return out;
    }

    /**
     * The workload an Istio Gateway runs on: its {@code spec.selector}. The Istio installation's
     * convention is the label {@code istio: <x>} on the Deployment {@code istio-<x>} — the
     * default ingress is {@code istio: ingressgateway} on {@code istio-ingressgateway}; any other
     * selector names its workload by its {@code app} label (or its single value).
     */
    static String gatewayWorkload(Map<?, ?> root) {
        Object spec = root.get("spec");
        if (!(spec instanceof Map<?, ?> sp) || !(sp.get("selector") instanceof Map<?, ?> sel) || sel.isEmpty()) return null;
        Object istio = sel.get("istio");
        if (istio != null) return "istio-" + String.valueOf(istio).trim().toLowerCase(Locale.ROOT);
        Object app = sel.get("app");
        if (app != null) return String.valueOf(app).trim().toLowerCase(Locale.ROOT);
        return sel.size() == 1 ? String.valueOf(sel.values().iterator().next()).trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Rule 56: a VirtualService bound to a Gateway routes the ingress to each route's
     * destination host — {@code istio-ingressgateway → frontend} for Online Boutique's and
     * Bank of Anthos's istio manifests. One bound only to {@code mesh} (or to nothing) is
     * in-mesh traffic shaping (canaries, fault injection): its destinations are the service
     * itself, not edges. Graded documented, as declared routing is everywhere else.
     */
    void extractVirtualService(Map<?, ?> root, String namespace, String relative, EdgeLedger ledger) {
        Object spec = root.get("spec");
        if (!(spec instanceof Map<?, ?> sp)) return;
        List<String> gateways = new java.util.ArrayList<>();
        if (sp.get("gateways") instanceof List<?> gl) for (Object g : gl) if (!"mesh".equals(String.valueOf(g).trim())) gateways.add(String.valueOf(g).trim());
        if (gateways.isEmpty()) return;
        Set<String> hosts = new java.util.LinkedHashSet<>();
        for (String section : List.of("http", "tcp", "tls")) {
            if (!(sp.get(section) instanceof List<?> routes)) continue;
            for (Object r : routes) {
                if (!(r instanceof Map<?, ?> route) || !(route.get("route") instanceof List<?> dests)) continue;
                for (Object d : dests) {
                    if (d instanceof Map<?, ?> dm && dm.get("destination") instanceof Map<?, ?> dest && dest.get("host") != null) {
                        hosts.add(String.valueOf(dest.get("host")).trim());
                    }
                }
            }
        }
        for (String gateway : gateways) {
            for (String host : hosts) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("gateway", gateway.contains("/") ? gateway.substring(gateway.indexOf('/') + 1) : gateway);
                fields.put("host", host);
                if (namespace != null) fields.put("namespace", namespace);
                RuleTrace.hit(56, "istio " + gateway + " -> " + host);
                ledger.add("ingress-route", fields, relative, -1, "High (istio virtualservice)");
            }
        }
    }

    /**
     * Rule 56: an APISIX standalone configuration ({@code routes:} / {@code upstreams:} whose
     * entries carry {@code nodes}) is a reverse proxy's routing table, read like nginx's
     * proxy_pass and Envoy's clusters: one {@code url} row per upstream node. The proxy is the
     * container the file is mounted into (the merger's mount attribution). Returns whether the
     * document was one.
     */
    boolean extractApisixRoutes(Object loaded, String relative, EdgeLedger ledger) {
        if (!(loaded instanceof Map<?, ?> root)) return false;
        List<Object> upstreams = new java.util.ArrayList<>();
        if (root.get("upstreams") instanceof List<?> ul) upstreams.addAll(ul);
        if (root.get("routes") instanceof List<?> rl) {
            for (Object r : rl) if (r instanceof Map<?, ?> route && route.get("upstream") != null) upstreams.add(route.get("upstream"));
        }
        boolean any = false;
        for (Object u : upstreams) {
            if (!(u instanceof Map<?, ?> up)) continue;
            Object nodes = up.get("nodes");
            List<String> addresses = new java.util.ArrayList<>();
            if (nodes instanceof Map<?, ?> nm) for (Object k : nm.keySet()) addresses.add(String.valueOf(k).trim());
            if (nodes instanceof List<?> nl) {
                for (Object n : nl) {
                    if (n instanceof Map<?, ?> node && node.get("host") != null) {
                        addresses.add(String.valueOf(node.get("host")).trim() + (node.get("port") != null ? ":" + node.get("port") : ""));
                    }
                }
            }
            for (String a : addresses) {
                if (a.isEmpty()) continue;
                any = true;
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("value", "http://" + a);
                fields.put("proxy", "apisix");
                RuleTrace.hit(56, "apisix " + a);
                ledger.add("url", fields, relative, -1, "High (apisix routes)");
            }
        }
        return any;
    }

    /** {@code spec.template.metadata.labels} of a workload (a bare Pod's own labels), as sorted {@code k=v,…}; null if none. */
    static String podLabels(Map<?, ?> root) {
        Object labels = null;
        if ("Pod".equals(String.valueOf(root.get("kind")))) {
            Object md = root.get("metadata");
            labels = md instanceof Map<?, ?> m ? m.get("labels") : null;
        } else {
            Object spec = root.get("spec");
            Object template = spec instanceof Map<?, ?> s ? s.get("template") : null;
            Object md = template instanceof Map<?, ?> t ? t.get("metadata") : null;
            labels = md instanceof Map<?, ?> m ? m.get("labels") : null;
        }
        return labels instanceof Map<?, ?> l && !l.isEmpty() ? labelString(l) : null;
    }

    static String labelString(Map<?, ?> labels) {
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<?, ?> e : labels.entrySet()) sorted.put(String.valueOf(e.getKey()).trim(), String.valueOf(e.getValue()).trim());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** k8s kinds that run a container — the inventory of what the cluster would actually run. */
    static final java.util.Set<String> WORKLOAD_KINDS = java.util.Set.of(
            "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob", "Pod");

    /** Namespaces already recorded in this extraction (one {@code k8s-namespace} row each). */
    final Set<String> namespaces = new java.util.LinkedHashSet<>();

    /** {@code metadata.namespace} of a k8s object, or null. */
    static String metadataNamespace(Map<?, ?> root) {
        Object metadata = root.get("metadata");
        Object ns = (metadata instanceof Map<?, ?> m) ? m.get("namespace") : null;
        if (ns == null) return null;
        String s = String.valueOf(ns).trim().toLowerCase(Locale.ROOT);
        return s.matches(DescriptorRows.DNS_LABEL) ? s : null;
    }

    /** {@code metadata.name} of a k8s object, or null. */
    static String metadataName(Map<?, ?> root) {
        Object metadata = root.get("metadata");
        Object name = (metadata instanceof Map<?, ?> m) ? m.get("name") : null;
        return name == null || String.valueOf(name).isBlank() ? null : String.valueOf(name).trim();
    }

    /** The pod spec of a workload object: {@code spec.template.spec}, or {@code spec} for a bare Pod. */
    static Map<?, ?> podSpec(Map<?, ?> root) {
        Object spec = root.get("spec");
        if ("Pod".equals(String.valueOf(root.get("kind")))) return spec instanceof Map<?, ?> s ? s : null;
        Object template = (spec instanceof Map<?, ?> s) ? s.get("template") : null;
        Object podSpec = (template instanceof Map<?, ?> t) ? t.get("spec") : null;
        return podSpec instanceof Map<?, ?> ps ? ps : null;
    }

    /** Every {@code containers[].env} entry of a workload object, flattened. */
    List<Object> containerEnvEntries(Map<?, ?> root) {
        List<Object> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> container && container.get("env") instanceof List<?> envs) {
                    out.addAll(envs);
                }
            }
        }
        return out;
    }

    /** The first container's image of a workload object, or null. */
    static String firstImage(Map<?, ?> root) {
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> c && c.get("image") != null) {
            return String.valueOf(c.get("image"));
        }
        return null;
    }

    /** Every {@code containers[].command} and {@code containers[].args} of a workload object. */
    List<Object> containerCommands(Map<?, ?> root) {
        List<Object> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (containers instanceof List<?> list) {
            for (Object c : list) {
                if (!(c instanceof Map<?, ?> container)) continue;
                if (container.get("command") != null) out.add(container.get("command"));
                if (container.get("args") != null) out.add(container.get("args"));
            }
        }
        return out;
    }

    /** Every {@code containers[].envFrom[].configMapRef.name} of a workload object. */
    List<String> containerEnvFromConfigMaps(Map<?, ?> root) {
        List<String> out = new java.util.ArrayList<>();
        Map<?, ?> podSpec = podSpec(root);
        Object containers = podSpec == null ? null : podSpec.get("containers");
        if (!(containers instanceof List<?> list)) return out;
        for (Object c : list) {
            if (!(c instanceof Map<?, ?> container) || !(container.get("envFrom") instanceof List<?> froms)) continue;
            for (Object f : froms) {
                if (f instanceof Map<?, ?> from && from.get("configMapRef") instanceof Map<?, ?> ref && ref.get("name") != null) {
                    out.add(String.valueOf(ref.get("name")).trim());
                }
            }
        }
        return out;
    }

}
