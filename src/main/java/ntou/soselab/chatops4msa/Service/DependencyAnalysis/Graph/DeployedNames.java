package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.AddressShape;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.EnvScope;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.BARE_FQDN;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.DEPLOY_NAME_SUFFIXES;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.cleanHost;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.commonPrefix;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.fileStem;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.firstPathSegment;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.hyphenated;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isComposeFile;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isConfigFile;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isExternal;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.isPlausibleName;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.matchWorkloadForModule;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.parentDir;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.repoShortName;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.unversionedWorkload;
import static ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable.workloadForDeploymentName;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convention ① — the name table of one deployment: every name a descriptor declares, the
 * spellings that are one name (aliases, versions, module directories), the namespaces, and
 * the answer to "where does this address land?" ({@link #resolveTarget}) and "whose file is
 * this?" ({@link #resolveSource}). Built by the index passes before any edge is merged.
 * The pure name functions are {@link NameTable}; this object holds what the deployment said.
 */
final class DeployedNames {

    /** The runtime graph's namespace, for the external-domain test. */
    final String graphNamespace;

    DeployedNames(DependencyGraph graph, String repoName) {
        this.graphNamespace = graph.getNamespace();
        for (DependencyGraph.Node node : graph.getNodes()) knownNodes.add(node.id);
        this.greenfield = knownNodes.isEmpty();
        this.defaultSource = matchNode(repoShortName(repoName));
    }

    final Set<String> knownNodes = new LinkedHashSet<>();
    final String defaultSource; // resolved from the repo name, used when the file path does not identify a service
    /** Common module-directory prefix (e.g. "spring-petclinic") learned from dirs that resolved to workloads. */
    String modulePrefix;

    /**
     * Greenfield mode: no runtime graph supplied a vocabulary, so the service
     * inventory scanned from the repo ({@code service-root}) is the only source of
     * node names. In this mode the merger both seeds nodes from the inventory and
     * attributes sources by matching a file to its service directory.
     */
    final boolean greenfield;
    /** service directory (relative path) -> service name, longest dir wins on lookup. */
    final Map<String, String> serviceDirs = new LinkedHashMap<>();
    /**
     * Normalised service key -> node, for resolving a call whose host is a variable
     * but whose URL PATH names the callee by convention (Spring: {@code
     * /api/v1/orderservice/...} -> order-service). Keys are letters-only forms of the
     * known service names, with and without a {@code ts-} prefix / {@code service} suffix.
     */
    final Map<String, String> serviceKeys = new LinkedHashMap<>();
    /** Whether the vocabulary came from a rendered Helm chart (rule 52: only when nothing more concrete exists). */
    boolean helmVocabulary;
    /** Workload names the k8s manifests declare ({@code k8s-workload}); greenfield vocabulary. */
    final Set<String> workloads = new LinkedHashSet<>();
    /** Every workload name any manifest declares, before the own-module filter (rule 44's directory match). */
    final Set<String> declaredWorkloads = new LinkedHashSet<>();
    /** Module names that are (or align to) a deployable workload; pre-added as nodes in greenfield. */
    final Set<String> deployableModules = new LinkedHashSet<>();
    /**
     * The namespaces the manifests declare (plus the runtime graph's own). A host written
     * {@code <svc>.<ns>} is in-cluster only when {@code <ns>} is one of these; that label,
     * and the cluster DNS suffixes, are the only things ever stripped from a dotted name.
     * Everything else dotted is either a declared name kept whole ({@code catalog.api}, a
     * Compose service) or an external domain — never cut to its first label, which is how
     * {@code catalog.api} became {@code catalog} and {@code api.yas.local} became {@code api}
     * (2026-10-26, set C).
     */
    final Set<String> namespaces = new LinkedHashSet<>();
    /** Repo-relative module directory → the Compose service built from it ({@code build:` context / dockerfile dir). */
    final Map<String, String> composeBuildDirs = new LinkedHashMap<>();
    /** k8s image stem (and the stem with the project's registry prefix cut) → the vocabulary workloads running it. */
    final Map<String, Set<String>> k8sImages = new LinkedHashMap<>();
    final Map<String, Set<String>> k8sImageRegistries = new LinkedHashMap<>();
    /** A module whose image several workloads run → those workloads (its code edges are each one's). */
    final Map<String, List<String>> moduleFanout = new LinkedHashMap<>();
    /** Registry name ({@code srv-search}) -> the files whose service registers itself under it (rule 49). */
    final Map<String, Set<String>> registryNameFiles = new LinkedHashMap<>();

    // ---- declared names, collected by the index passes (convention ①) ----
    /** The k8s Services the manifests declare (unversioning and selectors). */
    final Set<String> k8sServices = new LinkedHashSet<>();
    /** Every k8s workload name as written, before unversioning. */
    final Set<String> rawWorkloadNames = new LinkedHashSet<>();
    /** The workloads of the vocabulary tier that won (manifests, Compose, or a rendered chart). */
    final Set<String> allWorkloads = new LinkedHashSet<>();
    /** Manifest directory → the workloads it declares (only a directory deploying this repo's modules counts). */
    final Map<String, Set<String>> workloadsByDir = new LinkedHashMap<>();
    /** Trace only: whether the vocabulary came from manifests (rule 1) or Compose (rule 11). */
    boolean k8sVocabulary;
    /** Module directory → its node, in inventory order. */
    final Map<String, String> moduleNodes = new LinkedHashMap<>();

    /**
     * The workloads that run the image a module is built into: an image stem (whole, or with
     * the project's registry prefix cut) equal to the module's directory, name or artifact.
     */
    Set<String> imageWorkloads(String dir, String rawName, String artifact, Set<String> vocabulary) {
        String last = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
        for (String key : new String[]{hyphenated(last), hyphenated(rawName), artifact == null ? "" : hyphenated(artifact)}) {
            if (key.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> e : k8sImages.entrySet()) {
                if (hyphenated(e.getKey()).equals(key)) return filterTo(e.getValue(), vocabulary);
            }
        }
        // Exact only: a suffix test on image names cut to their project stem would equate
        // train-ticket's undeployed ts-train-food-service with ts-food-service's image.
        return Set.of();
    }

    static Set<String> filterTo(Set<String> names, Set<String> vocabulary) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) if (vocabulary.contains(n)) out.add(n);
        return out;
    }

    /** Compose image stem ({@code gateway-service}, and the stem with the project's registry prefix cut) → the services running it. */
    final Map<String, Set<String>> composeImages = new LinkedHashMap<>();
    /** Image registry path → the image stems under it. */
    final Map<String, Set<String>> composeImageRegistries = new LinkedHashMap<>();

    /** Compose service names, as declared (dots included), for the artifactId equation. */
    final Set<String> composeServiceNames = new LinkedHashSet<>();

    /** Raw workload name (a versioned Deployment, a Compose service) -> the node it is. */
    final Map<String, String> workloadAliases = new LinkedHashMap<>();
    /** Listening port -> the modules whose config declares it (server.port); a target only when unique. */
    final Map<String, Set<String>> portOwners = new LinkedHashMap<>();
    /** Host port → the Compose services that publish it ({@code ports: "5432:5432"}). */
    final Map<String, Set<String>> publishedPorts = new LinkedHashMap<>();
    /** Module names (service-root spelling) that are libraries, not services. */
    final Set<String> libraryModules = new LinkedHashSet<>();
    /** The library modules whose verdict came from a JVM build file (trace only: rule 41 rather than 21). */
    final Set<String> jvmLibraryModules = new LinkedHashSet<>();
    /** Library module dir -> its importable top-level packages. */
    final Map<String, Set<String>> libraryPackages = new LinkedHashMap<>();
    /** service-root module name (before workload alignment) -> node. */
    final Map<String, String> moduleNameNodes = new LinkedHashMap<>();
    /**
     * Compose link aliases: node → (alias → target service). {@code links: - mysql:db} lets
     * that one container reach mysql as "db", and its config then says {@code jdbc:mysql://db};
     * the alias is local to the declaring service, so it is kept per service and consulted for
     * the reading module first, then taken repo-wide only when every declaration agrees
     * (2026-10-26, mall-swarm: db→mysql, es→elasticsearch, rabbit→rabbitmq).
     */
    final Map<String, Map<String, String>> composeAliases = new LinkedHashMap<>();
    /** Compose network aliases (B3): reader node → (alias → target), consulted only for that reader. */
    final Map<String, Map<String, String>> networkAliases = new LinkedHashMap<>();
    /**
     * Rule 49: registry name → the files whose service registers itself under it.
     */
    void indexRegistryNames(JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject e = edges.optJSONObject(i);
            if (e == null || !"registry-name".equals(e.optString("section", ""))) continue;
            JSONObject f = e.optJSONObject("fields");
            String name = f == null ? "" : f.optString("name", "").trim();
            if (!name.isEmpty()) registryNameFiles.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(e.optString("file", ""));
        }
    }

    /**
     * The namespaces the manifests declare, and the Service names declared in two of them (rule 54).
     */
    void indexNamespaces(JSONArray edges) {
        if (graphNamespace != null && !graphNamespace.isBlank()) {
            namespaces.add(graphNamespace.trim().toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("k8s-namespace".equals(section)) {
                String ns = fields.optString("name", "").trim().toLowerCase(Locale.ROOT);
                if (!ns.isEmpty()) namespaces.add(ns);
            }
        }
        // Rule 54: the same Service name in two namespaces is two services (travels in
        // travel-portal, the portal; travels in travel-agency, the agency). Such a name — and
        // only such a name — is qualified name.namespace everywhere: as a node, as a reader,
        // and as a host, where a short name means the reader's own namespace (k8s DNS).
        Map<String, Set<String>> nsByName = new LinkedHashMap<>();
        Set<String> unnamespaced = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"k8s-service".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String name = fields.optString("name", "").trim().toLowerCase(Locale.ROOT);
            String ns = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (ns.isEmpty()) unnamespaced.add(name);
            else nsByName.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(ns);
        }
        for (Map.Entry<String, Set<String>> e : nsByName.entrySet()) {
            if (e.getValue().size() >= 2 && !unnamespaced.contains(e.getKey())) {
                ambiguousNames.add(e.getKey());
                RuleTrace.hit(54, "qualify " + e.getKey() + " in " + e.getValue());
            }
        }
    }

    /**
     * Every name a descriptor declares: k8s Services and workloads, Compose services with what
     * they build and run, the parsed directories and the FROM chain.
     */
    void indexDeclaredNames(JSONArray edges) {
        // The k8s Services, read first: a Deployment named reviews-v1 beside a Service named
        // reviews is one version of the service "reviews" (the Istio bookinfo convention),
        // and the Service name is what every caller and the mesh use. So is a set of
        // Deployments that differ only by a trailing -v<digits>. Only that suffix is
        // stripped, and only on that evidence (2026-10-23).
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            String name = clean(fields.optString("name", ""));
            if ("k8s-service".equals(section)) k8sServices.add(name);
            if ("k8s-workload".equals(section)) rawWorkloadNames.add(name);
            if ("compose-service".equals(section) && !name.isEmpty()) {
                composeServiceNames.add(name);
                // The module a Compose service is built from: the Dockerfile's directory when
                // one is named (a .NET solution builds every project from the repo root), else
                // the build context itself. The repo root names no module.
                String dockerfileDir = fields.optString("dockerfile_dir", "").replace('\\', '/');
                String context = fields.optString("context", "").replace('\\', '/');
                String built = !dockerfileDir.isBlank() ? dockerfileDir : context;
                if (!built.isBlank() && !built.equals(".")) composeBuildDirs.putIfAbsent(built, name);
                String image = fields.optString("image", "").trim();
                if (!image.isEmpty()) {
                    composeImages.computeIfAbsent(image, k -> new LinkedHashSet<>()).add(name);
                    String registry = fields.optString("image_registry", "");
                    composeImageRegistries.computeIfAbsent(registry, k -> new LinkedHashSet<>()).add(image);
                }
            }
        }
        // Several images under one registry path name the project the same way (yas-cart,
        // yas-storefront, yas-media under ghcr.io/nashtech-garage): that shared prefix is the
        // project's, not the module's, and the module is the rest — an exact equation once the
        // prefix is the one every image of that path carries (rule 44, 2026-10-27).
        for (Map.Entry<String, Set<String>> reg : composeImageRegistries.entrySet()) {
            if (reg.getKey().isEmpty() || reg.getValue().size() < 2) continue;
            String prefix = commonPrefix(reg.getValue());
            int cut = Math.max(prefix.lastIndexOf('-'), prefix.lastIndexOf('_'));
            prefix = cut >= 0 ? prefix.substring(0, cut + 1) : "";
            if (prefix.isEmpty()) continue;
            for (String image : reg.getValue()) {
                String stem = image.substring(prefix.length());
                if (stem.isEmpty()) continue;
                for (String svc : composeImages.getOrDefault(image, Set.of())) {
                    composeImages.computeIfAbsent(stem, k -> new LinkedHashSet<>()).add(svc);
                }
            }
        }
    }

    /**
     * Greenfield: which declared names are the node vocabulary (rules 1, 11, 52), and the
     * image → workloads table with the project's registry prefix cut (rule 44).
     */
    void chooseVocabulary(JSONArray edges) {
        // Pass 0 (greenfield only): the workloads the manifests declare. These are the
        // names the cluster — and so the runtime graph — would use, so they outrank a
        // module directory's spelling: "catalog", not microservice-kubernetes-demo-catalog.
        // Grouped by manifest directory: only a directory that deploys this repo's own
        // modules is the application's deployment. A repo also ships manifests for its
        // monitoring stack (train-ticket: prometheus, grafana, jaeger, an EFK stack —
        // 60 workloads) and for optional extras (Bank of Anthos: a pgpool operator, a
        // Cloud SQL populate job); none of those is a node of the application's graph.
        // A Compose file's services are the same kind of vocabulary, for the repos whose
        // only deployment description it is (petclinic, ewolff's microservice-demo,
        // Tap-And-Eat). The manifests win when there are any: a k8s workload name is what
        // the cluster — and so the runtime graph — really uses, and mixing the two
        // spellings would split one service in two, which is the very fault this fixes.
        if (greenfield) {
            // Rule 52's precedence: a rendered Helm chart is a template whose names hang on the
            // release and the values; it is the vocabulary only when the repo states its
            // deployment nowhere more concretely (no plain manifest, no Compose file). Its env
            // and routing rows are read either way (2026-10-27).
            for (String tier : List.of("k8s-workload", "compose-service", "k8s-workload:helm")) {
                String section = tier.startsWith("k8s-workload") ? "k8s-workload" : tier;
                boolean helmTier = tier.endsWith(":helm");
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    if (edge == null || !section.equals(edge.optString("section", ""))) continue;
                    JSONObject fields = edge.optJSONObject("fields");
                    if (fields == null) continue;
                    if ("k8s-workload".equals(section) && fields.has("helm") != helmTier) continue;
                    String raw = clean(fields.optString("name", ""));
                    String name = "k8s-workload".equals(section) ? unversionedWorkload(raw, k8sServices, rawWorkloadNames) : raw;
                    String rowNs = fields.optString("namespace", "").trim().toLowerCase(Locale.ROOT);
                    if ("k8s-workload".equals(section) && ambiguousNames.contains(name) && !rowNs.isEmpty()) {
                        name = name + "." + rowNs;
                        workloadAliases.put(raw + "@" + rowNs, name);
                    }
                    // An ambiguous name with no namespace (travels-v2.yaml, applied nowhere) cannot be
                    // placed in either service: it is not a node of its own (rule 54).
                    if ("k8s-workload".equals(section) && ambiguousNames.contains(name) && rowNs.isEmpty()) {
                        RuleTrace.hit(54, "unplaceable " + raw);
                        continue;
                    }
                    if ("k8s-workload".equals(section) && !rowNs.isEmpty()) nodeNamespaces.put(name, rowNs);
                    if (!isPlausibleName(name)) continue;
                    String image = fields.optString("image", "").trim();
                    if ("k8s-workload".equals(section) && !image.isEmpty()) {
                        k8sImages.computeIfAbsent(image, k -> new LinkedHashSet<>()).add(name);
                        k8sImageRegistries.computeIfAbsent(fields.optString("image_registry", ""), k -> new LinkedHashSet<>()).add(image);
                    }
                    if (RuleTrace.hitIf(!name.equals(raw), 19, "version " + raw + "=" + name)) workloadAliases.put(raw, name);
                    allWorkloads.add(name);
                    workloadsByDir.computeIfAbsent(parentDir(edge.optString("file", "").replace('\\', '/')),
                            k -> new LinkedHashSet<>()).add(name);
                }
                if (!allWorkloads.isEmpty()) {
                    k8sVocabulary = "k8s-workload".equals(section);
                    helmVocabulary = helmTier;
                    RuleTrace.hitIf(helmTier, 52, "vocabulary " + allWorkloads.size() + " rendered workloads");
                    RuleTrace.hitIf(k8sVocabulary && !composeServiceNames.isEmpty(), 11, "k8s-over-compose " + composeServiceNames.size() + " compose names ignored");
                    break;
                }
            }
        }
        // The k8s images, with the project's registry prefix cut as for Compose (demo_travels_
        // under quay.io/kiali): an image name is the module's, the second alias source a
        // manifest gives besides the workload's own name (rule 44 extended, 2026-10-27).
        for (Map.Entry<String, Set<String>> reg : k8sImageRegistries.entrySet()) {
            if (reg.getKey().isEmpty() || reg.getValue().size() < 2) continue;
            String prefix = commonPrefix(reg.getValue());
            int cut = Math.max(prefix.lastIndexOf('-'), prefix.lastIndexOf('_'));
            prefix = cut >= 0 ? prefix.substring(0, cut + 1) : "";
            if (prefix.isEmpty()) continue;
            for (String image : new ArrayList<>(reg.getValue())) {
                String stem = image.substring(prefix.length());
                if (!stem.isEmpty()) k8sImages.computeIfAbsent(stem, k -> new LinkedHashSet<>()).addAll(k8sImages.getOrDefault(image, Set.of()));
            }
        }
    }

    /**
     * The service inventory ({@code service-root}) aligned to the deployment's names: a module
     * directory is the workload it deploys as (rule 44, then rules 1 / 11), a library is no node
     * (rules 21, 41); the application's manifest directories decide the vocabulary (rule 10).
     */
    void indexInventory(JSONArray edges) {
        // Pass 1: the service inventory, so knownNodes is complete before env-address
        // resolution below can prefer a host that is actually a known service. A module
        // directory aligns to the workload it deploys as (exact, or by -suffix) when the
        // manifests know one; otherwise its own name is the node.
        // A module whose build manifest says it is a library (a Python distribution with
        // no image) is never a node: its code runs inside the services that import it.
        Map<String, String> libraryDirs = new LinkedHashMap<>(); // dir -> packages
        Set<String> jvmLibraryDirs = new LinkedHashSet<>();        // the library dirs whose verdict came from a JVM build file (rule 41, not 21)
        Map<String, String> moduleArtifactIds = new LinkedHashMap<>(); // dir -> the module's own artifactId
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"module-deps".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "").replace('\\', '/');
            if ("true".equals(fields.optString("library", ""))) {
                libraryDirs.put(dir, fields.optString("packages", ""));
                if ("jvm".equals(fields.optString("library_kind", ""))) jvmLibraryDirs.add(dir);
            }
            String artifact = fields.optString("artifact", "").trim();
            if (!artifact.isEmpty()) moduleArtifactIds.put(dir, artifact);
        }
        Set<String> aligned = new LinkedHashSet<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String dir = fields.optString("dir", "").replace('\\', '/');
            String rawName = clean(fields.optString("name", ""));
            String name = rawName;
            if (dir.isBlank() || !isPlausibleName(name)) continue;
            // The deployment's own statement of which module it runs: a Compose service whose
            // build context (or Dockerfile) is this directory, or whose name is exactly this
            // module's Maven artifactId. That is a mapping the repo makes, not a similarity
            // guess, so it is applied without asking (yas: `backoffice: build: ./backoffice-bff`;
            // 2026-10-26). A deployment name overrules the module's own spelling and the
            // library heuristic alike.
            String deploymentName = composeBuildDirs.get(dir);
            if (deploymentName == null) {
                // A module nested inside a build context that holds no other module is what that
                // image is built from (robot-shop: `ratings: build: ratings`, the PHP module in
                // ratings/html). The deepest such context wins; two modules under one context
                // say nothing about which is the image (rule 44 extended, 2026-10-27).
                String best = null;
                for (Map.Entry<String, String> ctx : composeBuildDirs.entrySet()) {
                    String c = ctx.getKey();
                    if (c.isEmpty() || !dir.startsWith(c + "/")) continue;
                    if (best != null && best.length() >= c.length()) continue;
                    if (otherModuleUnder(c, dir, edges)) continue;
                    best = c;
                }
                if (RuleTrace.hitIf(best != null, 44, "nested " + rawName + "=" + (best == null ? "" : composeBuildDirs.get(best)))) {
                    deploymentName = composeBuildDirs.get(best);
                }
            }
            if (deploymentName == null) {
                // image: with no build: the image's name is this module's (its directory, or its
                // artifact) — exactly one Compose service runs that image (B2, 2026-10-27).
                String lastSegment = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
                for (String key : new String[]{lastSegment.toLowerCase(Locale.ROOT), rawName, moduleArtifactIds.getOrDefault(dir, "").toLowerCase(Locale.ROOT)}) {
                    Set<String> services = key.isEmpty() ? null : composeImages.get(key);
                    if (services != null && services.size() == 1) {
                        deploymentName = services.iterator().next();
                        RuleTrace.hit(44, "image " + rawName + "=" + deploymentName);
                        break;
                    }
                }
            }
            if (deploymentName == null) {
                String artifact = moduleArtifactIds.get(dir);
                if (artifact != null && !artifact.equalsIgnoreCase(rawName)) {
                    for (String composeName : composeServiceNames) {
                        if (RuleTrace.hitIf(composeName.equalsIgnoreCase(artifact), 44, "artifact " + rawName + "=" + composeName)) deploymentName = composeName;
                    }
                }
            }
            boolean deployed = false;
            // With k8s manifests present the Compose name only helps if it resolves to a
            // workload (exactly, or as the unique `-suffix` of one: TeaStore's compose
            // `webui` is the manifests' teastore-webui); otherwise the k8s spelling wins,
            // as rule 11 says, and the module aligns on its own name below.
            String deploymentWorkload = deploymentName == null ? null
                    : (allWorkloads.isEmpty() ? deploymentName : workloadForDeploymentName(deploymentName, allWorkloads));
            // …but when the manifests do not deploy this module at all (no workload for the
            // Compose name, none for the module's own name), there is no k8s spelling to lose
            // to: the Compose name is the only deployment name the repo gives it (robot-shop's
            // K8s/ holds a load generator only; ratings/html is the Compose service ratings).
            if (deploymentName != null && deploymentWorkload == null && greenfield
                    && matchWorkloadForModule(rawName, allWorkloads) == null) {
                RuleTrace.hit(44, "compose-only " + rawName + "=" + deploymentName);
                deploymentWorkload = deploymentName;
            }
            RuleTrace.hitIf(deploymentName != null && deploymentWorkload == null, 44, "no-workload " + rawName + "=" + deploymentName);
            if (deploymentWorkload != null && greenfield) {
                RuleTrace.hitIf(!deploymentWorkload.equals(rawName), 44, "map " + rawName + "=" + deploymentWorkload);
                name = deploymentWorkload;
                if (allWorkloads.contains(deploymentWorkload)) aligned.add(deploymentWorkload);
                deployed = true;
            } else if (greenfield && !allWorkloads.isEmpty()) {
                String workload = matchWorkloadForModule(name, allWorkloads);
                if (RuleTrace.hitIf(workload != null && !workload.equals(name), k8sVocabulary ? 1 : 11, "align " + name + "=" + workload) || workload != null) {
                    name = workload;
                    aligned.add(workload);
                    deployed = true;
                } else if (!k8sImages.isEmpty()) {
                    // No workload is named like the module: the image it is built into may be.
                    // One workload runs it → that workload; several (travels' portal image runs as
                    // voyages, viaggi and travels) → the module's code belongs to each of them.
                    Set<String> runs = imageWorkloads(dir, rawName, moduleArtifactIds.get(dir), allWorkloads);
                    if (runs.size() == 1) {
                        name = runs.iterator().next();
                        RuleTrace.hit(44, "k8s-image " + rawName + "=" + name);
                        aligned.add(name);
                        deployed = true;
                    } else if (runs.size() > 1) {
                        RuleTrace.hit(44, "k8s-image-fanout " + rawName + "=" + runs);
                        moduleFanout.put(name, new ArrayList<>(runs));
                        aligned.addAll(runs);
                    }
                }
            }
            moduleNodes.put(dir, name);
            moduleNameNodes.put(rawName, name);
            // A library the deployment names anyway is a service after all: the build
            // manifest's verdict yields to the manifest's.
            RuleTrace.hitIf(libraryDirs.containsKey(dir) && deployed, jvmLibraryDirs.contains(dir) ? 41 : 21, "deployed-overrides " + name);
            if (libraryDirs.containsKey(dir) && !deployed) {
                RuleTrace.hit(jvmLibraryDirs.contains(dir) ? 41 : 21, "lib " + name);
                if (jvmLibraryDirs.contains(dir)) jvmLibraryModules.add(name);
                libraryModules.add(name);
                Set<String> packages = new LinkedHashSet<>();
                for (String p : libraryDirs.get(dir).split(",")) if (!p.isBlank()) packages.add(p.trim());
                libraryPackages.put(dir, packages);
            }
        }
        // The application's manifests: the directories in which some module of this repo
        // is deployed. Their workloads (a database StatefulSet, the registry, the broker
        // of a variant) are the vocabulary; the other directories' are not.
        for (Map.Entry<String, Set<String>> e : workloadsByDir.entrySet()) {
            boolean deploysOwnModule = false;
            for (String w : e.getValue()) if (aligned.contains(w)) deploysOwnModule = true;
            if (deploysOwnModule) workloads.addAll(e.getValue());
            else for (String w : e.getValue()) RuleTrace.hit(10, "drop " + w + " (" + e.getKey() + ")");
        }
        if (greenfield) knownNodes.addAll(workloads);
        declaredWorkloads.addAll(allWorkloads);
        declaredWorkloads.addAll(composeServiceNames);
        for (Map.Entry<String, String> e : moduleNodes.entrySet()) {
            String name = e.getValue();
            serviceDirs.put(e.getKey(), name);
            if (greenfield && !libraryModules.contains(name)) {
                knownNodes.add(name);
                // A module the manifests never deploy is a library or a tool; with no
                // manifests at all, every module is presumed deployable.
                if (workloads.isEmpty() || workloads.contains(name)) deployableModules.add(name);
                else RuleTrace.hit(k8sVocabulary ? 1 : 11, "not-preadded " + name);
            }
        }
    }

    /**
     * Compose link and network aliases, per declaring service (rules 25, 37).
     */
    void indexComposeAliases(JSONArray edges) {
        // Compose link aliases, keyed by the node the declaring service is. A `hostname:`
        // alias is declared by the target itself and holds for every reader: keyed "*".
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"compose-alias".equals(edge.optString("section", ""))) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String rawService = fields.optString("service", "");
            String service = "*".equals(rawService) ? "*" : resolveComposeService(rawService);
            String alias = clean(fields.optString("alias", ""));
            String target = clean(fields.optString("target", ""));
            if (service == null || alias.isEmpty() || target.isEmpty()) continue;
            // A network alias holds on its network only: it is never taken repo-wide.
            if (fields.has("network")) {
                networkAliases.computeIfAbsent(service, k -> new LinkedHashMap<>()).putIfAbsent(alias, target);
                continue;
            }
            composeAliases.computeIfAbsent(service, k -> new LinkedHashMap<>()).putIfAbsent(alias, target);
        }
    }

    /**
     * Which Compose services mount a repo file, and which workload an Istio Gateway selects.
     */
    void indexMountsAndGateways(JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject fields = edge.optJSONObject("fields");
            if (fields == null) continue;
            String section = edge.optString("section", "");
            if ("compose-mount".equals(section)) {
                mounts.computeIfAbsent(fields.optString("file", ""), k -> new LinkedHashSet<>()).add(fields.optString("service", ""));
            } else if ("k8s-gateway".equals(section)) {
                gatewayWorkloads.computeIfAbsent(fields.optString("name", ""), k -> new LinkedHashSet<>()).add(fields.optString("workload", ""));
            }
        }
    }


    /**
     * Rule 59: a Kubernetes Service whose selector picks exactly one workload is that workload's
     * address, so the two names are one service. Callers dial the Service, the pods belong to
     * the workload; when the names differ (containerized-products / k8n-service-products,
     * songs / songs-deployment) the calls and the code landed on two nodes (17 of the 100
     * mining systems; 2026-11-03). The node keeps the name the code has: when exactly one of
     * the two names is a module's node, the other becomes its alias (a Service name an alias
     * every reader shares, as a Compose {@code hostname:} is, rule 37; a workload name an
     * alias of the workload rows). Neither or both a module, or a selector that matches no
     * workload or several: nothing changes. A versioned workload is first unversioned (rule 19).
     */
    void indexServiceSelectors(JSONArray edges, Set<String> rawWorkloadNames, Set<String> k8sServices, Set<String> moduleNodeNames) {
        Map<String, Set<String>> podsByLabel = new LinkedHashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"k8s-workload".equals(edge.optString("section", ""))) continue;
            JSONObject f = edge.optJSONObject("fields");
            // A rendered Helm chart's labels hang on the release name, which is a placeholder
            // (rule 52): two charts' selectors can collide on it (yas: pgadmin's on postgres).
            if (f == null || f.has("helm")) continue;
            String name = clean(f.optString("name", ""));
            for (String label : f.optString("labels", "").split(",")) {
                if (!label.isBlank() && !label.contains("${")) podsByLabel.computeIfAbsent(label.trim(), k -> new LinkedHashSet<>()).add(name);
            }
        }
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"k8s-service".equals(edge.optString("section", ""))) continue;
            JSONObject f = edge.optJSONObject("fields");
            if (f == null || f.has("helm")) continue;
            String service = clean(f.optString("name", ""));
            String selector = f.optString("selector", "");
            if (service.isEmpty() || selector.isBlank() || selector.contains("${")) continue;
            Set<String> picked = null;
            for (String label : selector.split(",")) {
                Set<String> pods = podsByLabel.getOrDefault(label.trim(), Set.of());
                if (picked == null) picked = new LinkedHashSet<>(pods);
                else picked.retainAll(pods);
            }
            if (picked == null || picked.size() != 1) continue;
            String raw = picked.iterator().next();
            String workload = workloadAliases.getOrDefault(raw, unversionedWorkload(raw, k8sServices, rawWorkloadNames));
            // A Compose service of the same name is a deployment unit of its own, not an alias.
            if (workload.equals(service) || composeServiceNames.contains(service) || composeServiceNames.contains(workload)) continue;
            boolean serviceHasCode = moduleNodeNames.contains(service);
            boolean workloadHasCode = moduleNodeNames.contains(workload);
            if (serviceHasCode == workloadHasCode) continue;
            if (workloadHasCode) {
                if (RuleTrace.hitIf(composeAliases.computeIfAbsent("*", k -> new LinkedHashMap<>()).putIfAbsent(service, workload) == null,
                        59, "selector " + service + "=" + workload)) knownNodes.remove(service);
            } else {
                RuleTrace.hit(59, "selector " + workload + "=" + service);
                workloadAliases.put(raw, service);
                composeAliases.computeIfAbsent("*", k -> new LinkedHashMap<>()).putIfAbsent(workload, service);
                knownNodes.remove(workload);
            }
        }
    }

    /** Repo file → the Compose services that mount it. */
    final Map<String, Set<String>> mounts = new LinkedHashMap<>();
    /** Istio Gateway name → the ingress workload its selector names. */
    final Map<String, Set<String>> gatewayWorkloads = new LinkedHashMap<>();

    /** Whether a service-root other than {@code dir} lies under the directory {@code context}. */
    static boolean otherModuleUnder(String context, String dir, JSONArray edges) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !"service-root".equals(edge.optString("section", ""))) continue;
            JSONObject f = edge.optJSONObject("fields");
            String d = f == null ? "" : f.optString("dir", "").replace('\\', '/');
            // The context directory's own module (its Dockerfile) is the image, not another module.
            if (!d.equals(dir) && !d.equals(context) && d.startsWith(context + "/")) return true;
        }
        return false;
    }

    /**
     * The service a Compose link alias stands for, as seen from {@code source}: the
     * declaration of the reading service itself, else the one target every declaring
     * service agrees on; null when the host is no alias or the declarations differ.
     */
    String composeAlias(String host, String source) {
        if (host == null || (composeAliases.isEmpty() && networkAliases.isEmpty())) return null;
        String h = clean(host);
        if (h.isEmpty()) return null;
        if (source != null && networkAliases.containsKey(source)) {
            String target = networkAliases.get(source).get(h);
            if (RuleTrace.hitIf(target != null, 25, "network-resolve " + source + ":" + h + "=" + target)) return target;
        }
        if (source != null && composeAliases.containsKey(source)) {
            String target = composeAliases.get(source).get(h);
            if (RuleTrace.hitIf(target != null, 25, "resolve " + source + ":" + h + "=" + target)) return target;
        }
        String unanimous = null;
        for (Map<String, String> aliases : composeAliases.values()) {
            String target = aliases.get(h);
            if (target == null) continue;
            if (unanimous != null && !unanimous.equals(target)) return null;
            unanimous = target;
        }
        if (unanimous != null) RuleTrace.hit(composeAliases.getOrDefault("*", Map.of()).containsKey(h) ? 37 : 25, "resolve " + h + "=" + unanimous);
        return unanimous;
    }

    /**
     * Where a host lands in this deployment (convention ①). {@code node} is the graph node;
     * {@code external} a domain outside the deployment; {@code byPort} a loopback address read
     * back to whoever publishes or listens on its port; {@code fresh} a plausible name the
     * deployment never declared, introduced as a new node; {@code dropped} a host that is
     * nobody's (reserved, loopback with no owner, an external domain the reader may not reach).
     */
    record Target(String node, boolean external, boolean byPort, boolean fresh, boolean dropped) {
        static final Target DROPPED = new Target(null, false, false, false, true);
    }

    /**
     * Convention ①, once for every reader: an address → the name table. In this order — a
     * reserved domain is nobody's; an alias (a Compose link or network alias the reader
     * declared, a hostname every reader agrees on) is the service it names; loopback is the
     * caller itself, except where the value is read on the developer's machine ({@code
     * devSide}: a module's own config, a code default), where {@code localhost:PORT} reaches
     * the one service that publishes or listens on PORT; a domain outside the deployment is
     * external; then the declared names, with the spellings deployment files give them; and
     * last, if allowed, a plausible new name. Null when nothing settles it (residue).
     */
    Target resolveTarget(String raw, String source, boolean devSide, boolean allowExternal, boolean allowNew) {
        String host = CodeGraphMerger.stripToHost(raw);
        if (host == null || host.isEmpty()) return null;
        if (RuleTrace.hitIf(isReservedHost(host), 33, "drop " + host)) return Target.DROPPED;
        host = unalias(host, source);
        if (CodeGraphMerger.isLoopback(host)) {
            String port = CodeGraphMerger.portOf(raw);
            String owner = devSide ? portOwner(port) : null;
            if (RuleTrace.hitIf(owner != null && !owner.equals(source), 15, "edge " + source + "->" + owner + " :" + port)) {
                return new Target(owner, false, true, false, false);
            }
            RuleTrace.hit(8, "loopback " + host + (port == null ? "" : ":" + port));
            return Target.DROPPED;
        }
        if (external(host)) return allowExternal ? new Target(host, true, false, false, false) : Target.DROPPED;
        String node = matchNodeLoose(host);
        if (node != null) return new Target(node, false, false, false, false);
        if (!allowNew) return null;
        String label = cleanLabel(host);
        return label != null && isPlausibleName(label) ? new Target(label, false, false, true, false) : null;
    }

    /** {@code host} with a Compose link alias resolved to the service it names, else {@code host} itself. */
    String unalias(String host, String source) {
        String target = composeAlias(host, source);
        return target == null ? host : target;
    }

    /**
     * Hosts under a TLD reserved for documentation and testing (RFC 2606 / 6761: .test,
     * .example, .invalid, .localhost, example.com/net/org) or for mDNS (RFC 6762: .local)
     * never name a deployment: {@code https://vcr.local/} is a test cassette's placeholder,
     * {@code api.example.com} a sample. Not a dependency, not residue (2026-10-26).
     */
    static boolean isReservedHost(String host) {
        return AddressShape.isReservedHost(host);
    }

    /** Registers the letters-only key variants of a service node for path resolution. */
    void registerServiceKeys(String node) {
        String base = node.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.length() < 3) return;
        serviceKeys.putIfAbsent(base, node);                                  // tsorderservice
        String noPrefix = base.startsWith("ts") ? base.substring(2) : base;   // orderservice
        if (noPrefix.length() >= 3) serviceKeys.putIfAbsent(noPrefix, node);
        for (String b : new String[]{base, noPrefix}) {
            if (b.endsWith("service") && b.length() > 9) {                     // order / tsorder (len>9 avoids bare "service")
                serviceKeys.putIfAbsent(b.substring(0, b.length() - 7), node);
            }
        }
    }

    /**
     * The service a URL path names by convention, or null. Scans path segments in
     * order (the service token is conventionally first, e.g. {@code
     * /api/v1/orderservice/order}) and returns the first that matches a known
     * service key. Used only as a fallback when the host cannot be resolved.
     */
    String resolveServiceFromPath(String path) {
        if (path == null || path.isEmpty() || serviceKeys.isEmpty()) return null;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            String key = seg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (key.length() < 3) continue;
            String node = serviceKeys.get(key);
            if (node != null) return node;
        }
        return null;
    }

    /**
     * Learns the common module-directory prefix from the dirs that DID resolve to a
     * workload via the {@code -suffix} rule (e.g. {@code spring-petclinic-customers-service}
     * → workload {@code customers-service} implies prefix {@code spring-petclinic}). It
     * is then stripped from a module dir that has no workload (an undeployed service),
     * so its source node reads {@code genai-service}, not {@code spring-petclinic-genai-service}.
     */
    void learnModulePrefix(JSONArray edges) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            String seg = firstPathSegment(edge.optString("file", ""));
            if (seg == null) continue;
            String c = clean(seg);
            for (String node : knownNodes) {
                if (node.length() >= 3 && c.endsWith("-" + node)) {
                    String prefix = c.substring(0, c.length() - node.length() - 1);
                    if (!prefix.isBlank()) counts.merge(prefix, 1, Integer::sum);
                    break;
                }
            }
        }
        modulePrefix = counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    String nodeNamedByVariable(String name) {
        // A variable named for a host (CATALOGUE_HOST, ORDER_SERVICE_URL, catalogueHost): the
        // words in front of the host word(s), and of a "service" before them, name the service.
        int drop = AddressShape.hostWords(name);
        if (drop == 0) return null; // no host-shaped name: not a host variable
        List<String> words = new ArrayList<>(AddressShape.words(name.trim()));
        for (int i = 0; i < drop && !words.isEmpty(); i++) words.remove(words.size() - 1);
        if (!words.isEmpty() && words.get(words.size() - 1).equals("service")) words.remove(words.size() - 1);
        if (words.isEmpty()) return null;
        String label = String.join("-", words);
        String node = matchNode(label);
        if (node == null) node = serviceKeys.get(label.replaceAll("[^a-z0-9]", ""));
        return node;
    }

    /**
     * The one service on the port, or null when none or several are. The modules' own
     * statements of where they listen ({@code server.port}, the same layer as the dev default
     * that names the port) come first; only a port no module claims falls to the Compose
     * services that publish it on the host.
     */
    String portOwner(String port) {
        if (port == null) return null;
        Set<String> owners = portOwners.get(port);
        if (owners != null && !owners.isEmpty()) return owners.size() == 1 ? owners.iterator().next() : null;
        owners = publishedPorts.get(port);
        return RuleTrace.hitIf(owners != null && owners.size() == 1, 15, "published " + port) ? owners.iterator().next() : null;
    }

    /**
     * The node a constant's string value names: a known node outright, or a module
     * directory's name in its node-safe spelling ({@code tools.descartes.teastore.persistence}
     * → {@code tools-descartes-teastore-persistence} → the workload it aligned to).
     */
    String resolveModuleName(String value) {
        String node = matchNode(value);
        if (node != null) return node;
        String normalised = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalised.isEmpty()) return null;
        node = moduleNameNodes.get(normalised);
        if (node != null) return node;
        return matchNode(normalised);
    }

    /**
     * Resolves a docker-compose service name onto the graph vocabulary: an existing
     * workload if known, otherwise the cleaned name introduced as a new node — a service
     * declared in compose but not observed at runtime (e.g. {@code tracing-server}) is a
     * real dependency, drawn dashed. Null only for a blank or implausible name.
     */
    String resolveComposeService(String raw) {
        String node = matchNodeLoose(raw);
        if (node != null) return node;
        String label = cleanLabel(raw);
        return (label != null && isPlausibleName(label) && !SOURCE_STOP.contains(label)) ? label : null;
    }

    boolean declaredName(String word) {
        if (word == null || word.isBlank()) return false;
        if (matchNodeLoose(word) != null) return true;
        String c = clean(word);
        for (String name : composeServiceNames) if (name.equalsIgnoreCase(c)) return true;
        return false;
    }

    /**
     * {@link #matchNode}, then the spellings a deployment file gives the same workload: a
     * {@code -container} suffix stripped, or the bare last word of a prefixed workload
     * name when it is unambiguous — TeaStore's compose file says {@code persistence} and
     * {@code db} for the workloads its manifests call {@code teastore-persistence} and
     * {@code teastore-db}. A short name that fits several nodes resolves to none.
     */
    String matchNodeLoose(String candidate) {
        String node = matchNode(candidate);
        if (node != null) return node;
        String c = clean(candidate);
        if (c.isEmpty()) return null;
        for (String suffix : DEPLOY_NAME_SUFFIXES) {
            if (c.endsWith(suffix) && c.length() > suffix.length()) {
                node = matchNode(c.substring(0, c.length() - suffix.length()));
                if (RuleTrace.hitIf(node != null, 7, "strip-suffix " + c + "=" + node)) return node;
            }
        }
        String unique = null;
        for (String known : knownNodes) {
            if (known.length() > c.length() + 1 && known.endsWith("-" + c)) {
                if (unique != null) return null; // ambiguous
                unique = known;
            }
        }
        RuleTrace.hitIf(unique != null, 7, "unique-suffix " + c + "=" + unique);
        return unique;
    }

    /** Resolve a host/name to a known workload id, or null. Deterministic only. */
    String matchNode(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String c = clean(candidate);
        if (c.isEmpty()) return null;

        for (String node : knownNodes) {
            if (node.equalsIgnoreCase(c)) return node;
        }
        // The same name in module-directory spelling (Basket.API ↔ basket.api ↔ basket-api).
        if (c.indexOf('.') >= 0 || c.indexOf('-') >= 0) {
            String h = hyphenated(c);
            for (String node : knownNodes) {
                if (RuleTrace.hitIf(node.indexOf('.') >= 0 && hyphenated(node).equals(h), 36, "dotted-name " + c + "=" + node)) return node;
            }
        }
        // Feign/gRPC client identifiers: "CatalogueClient" / "catalogue-client" -> "catalogue".
        String stripped = c.replaceAll("(?i)[-_]?client$", "");
        if (!stripped.equals(c)) {
            for (String node : knownNodes) {
                if (node.equalsIgnoreCase(stripped)) return node;
            }
        }
        return null;
    }

    /**
     * {@link NameTable#cleanHost}, then rule 54: an ambiguous name (declared in two
     * namespaces) is qualified by its own namespace label, else by the reader's.
     */
    String clean(String raw) {
        String s = cleanHost(raw, namespaces);
        if (ambiguousNames.isEmpty() || !ambiguousNames.contains(s)) return s;
        // An ambiguous name (rule 54): its own namespace label says which, else the reader's.
        String ns = NameTable.namespaceLabel(raw);
        if (ns != null && namespaces.contains(ns)) return s + "." + ns;
        if (readerNamespace != null) return s + "." + readerNamespace;
        return s;
    }

    /** The namespace of the reader whose hosts are being resolved (rule 54), or null. */
    String readerNamespace;
    /** Service names declared in two or more namespaces (rule 54). */
    final Set<String> ambiguousNames = new LinkedHashSet<>();
    /** Workload node → the namespace its manifest puts it in. */
    final Map<String, String> nodeNamespaces = new LinkedHashMap<>();

    /**
     * Whether a dotted host is an external domain as seen from this graph: not a declared
     * node (a Compose service {@code catalog.api}), not under a declared namespace, not an
     * in-cluster DNS name or an IP.
     */
    boolean external(String host) {
        if (host == null || !host.contains(".")) return false;
        if (matchNode(host) != null) return false;
        int dot = host.lastIndexOf('.');
        if (namespaces.contains(host.substring(dot + 1).toLowerCase(Locale.ROOT))) return false;
        return isExternal(host, graphNamespace);
    }

    /** A host reduced to its DNS name (cluster suffixes off), or null when nothing usable remains. */
    String cleanLabel(String raw) {
        if (raw == null) return null;
        String s = clean(raw);
        return s.isEmpty() ? null : s;
    }

    // ---------- source attribution ----------

    /** Directory names that are never a service (so their files don't invent a source). */
    static final Set<String> SOURCE_STOP = Vocabulary.SOURCE_STOP;

    /**
     * The calling service for a code edge. A dependency needs a real source: prefer
     * an existing workload (from the file's module directory, or the repo name);
     * only introduce a new source node when the directory clearly names a service
     * the runtime graph simply never saw traffic for.
     */
    String resolveSource(String file) {
        // Layout-aware first: the service is the deepest scanned service directory
        // (service-root) that contains this file. This is what makes a nested repo
        // (src/<group>/<service>/…) attribute to <service> instead of collapsing to
        // the top path segment "src". Falls through to the flat-layout rule below.
        String svc = serviceForFile(file);
        if (svc != null) {
            String matched = matchNodeForSource(svc);
            if (matched != null) return matched;
        }
        // One build, many services (hotelReservation: `build: .` with `entrypoint: search`,
        // code under services/search/): a file in no module, under a directory that is
        // exactly one deployed service's name, is that service's code. A file inside a
        // module stays the module's (yas: common-library/…/kafka/ is not the kafka broker).
        String inner = svc == null ? innerServiceDir(file) : null;
        String first = firstPathSegment(file);
        String firstNode = first == null ? null : matchNodeForSource(first);
        if (RuleTrace.hitIf(inner != null && (firstNode == null || !declaredWorkloads.contains(firstNode)), 44, "dir " + file + "=" + inner)) return inner;
        if (svc != null && greenfield && isPlausibleName(svc) && !SOURCE_STOP.contains(svc)) return svc;
        // A file in no module that exactly one Compose service mounts is that service's config.
        if (svc == null && file != null) {
            Set<String> by = mounts.get(file.replace('\\', '/'));
            if (by != null && by.size() == 1) {
                String mounted = resolveComposeService(by.iterator().next());
                if (RuleTrace.hitIf(mounted != null, 56, "mount " + file + "=" + mounted)) return mounted;
            }
        }

        String seg = firstPathSegment(file);
        if (seg != null) {
            String matched = matchNodeForSource(seg);
            if (matched != null) return matched;
            // A directory is named in module spelling (rule 2: dots become hyphens), not as a host.
            String label = cleanLabel(seg);
            if (label != null && label.indexOf('.') >= 0 && matchNode(label) == null) label = hyphenated(label);
            // An undeployed service's module dir has no workload to align to; strip the
            // learned repo prefix so it reads "genai-service", not the full module name.
            if (label != null && modulePrefix != null && label.startsWith(modulePrefix + "-")) {
                String stripped = label.substring(modulePrefix.length() + 1);
                if (isPlausibleName(stripped) && !SOURCE_STOP.contains(stripped)) label = stripped;
            }
            if (label != null && !SOURCE_STOP.contains(label) && isPlausibleName(label)) return label;
        }
        return defaultSource; // repo name matched a real workload, or null
    }

    /**
     * The service name of the deepest {@code service-root} directory that contains
     * this file, or null if no scanned service directory does. "Deepest" so a file
     * under {@code src/ledger/ledgerwriter/…} attributes to {@code ledgerwriter},
     * not to an ancestor module that also carries a manifest.
     */
    String serviceForFile(String file) {
        if (file == null || file.isBlank() || serviceDirs.isEmpty()) return null;
        String path = file.replace('\\', '/');
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> entry : serviceDirs.entrySet()) {
            String dir = entry.getKey();
            if ((path.equals(dir) || path.startsWith(dir + "/")) && dir.length() > bestLen) {
                best = entry.getValue();
                bestLen = dir.length();
            }
        }
        return best;
    }

    /** The one deployed service a directory of this path (below its first segment) is named after, or null. */
    String innerServiceDir(String file) {
        if (file == null || file.isBlank()) return null;
        Set<String> named = new LinkedHashSet<>();
        String[] parts = file.replace('\\', '/').split("/");
        for (int i = 1; i < parts.length - 1; i++) if (declaredWorkloads.contains(parts[i])) named.add(parts[i]);
        return named.size() == 1 ? named.iterator().next() : null;
    }

    /**
     * Like {@link #matchNode}, but also accepts a module directory whose name ends
     * with a known workload — e.g. {@code spring-petclinic-customers-service}
     * resolves to the workload {@code customers-service}.
     */
    String matchNodeForSource(String seg) {
        String matched = matchNode(seg);
        if (matched != null) return matched;
        String c = clean(seg);
        for (String node : knownNodes) {
            if (node.length() >= 3 && (c.endsWith("-" + node) || c.endsWith("_" + node))) return node;
        }
        return null;
    }

}
