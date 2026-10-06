package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DiscordToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.LlmToolkit;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DocGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DotEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphLayerAssigner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphNormalizer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphvizRenderer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.K8sGraphBuilder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.RuntimeGraphBuilder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa.ReportQaService;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the final dependency-analysis report from the stored checkpoint, so
 * clicking "Generate report" never re-runs collection.
 *
 * Mirrors the final LLM step of the low-code flow: same inputs, same prompt
 * template (dependency_analysis).
 */
@Service
public class DependencyReportService {

    private final DependencyAnalysisStateStore stateStore;
    private final LlmToolkit llmToolkit;
    private final DiscordToolkit discordToolkit;
    private final JDAService jdaService;
    private final ReportQaService reportQaService;

    @Autowired
    public DependencyReportService(DependencyAnalysisStateStore stateStore,
                                   LlmToolkit llmToolkit,
                                   DiscordToolkit discordToolkit,
                                   @Lazy JDAService jdaService,
                                   ReportQaService reportQaService) {
        this.stateStore = stateStore;
        this.llmToolkit = llmToolkit;
        this.discordToolkit = discordToolkit;
        this.jdaService = jdaService;
        this.reportQaService = reportQaService;
    }

    /**
     * Generates and posts the report for the given user from the stored provenance.
     * UserContextHolder must already be set to this user (LlmToolkit needs it).
     */
    /** No namespace (blank / "none" / "greenfield") means a static, no-cluster run. */
    private static boolean isGreenfield(String namespace) {
        if (namespace == null) return true;
        String ns = namespace.trim();
        return ns.isEmpty() || ns.equalsIgnoreCase("none") || ns.equalsIgnoreCase("greenfield");
    }

    public void generateAndPost(String userId) {
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) {
            jdaService.sendChatOpsChannelWarningMessage(
                    "[WARNING] No dependency-analysis checkpoint found (it may have expired). "
                            + "Please re-run get-dependency-analysis.");
            return;
        }

        // No namespace = greenfield: a static, code-and-docs-only run with no cluster.
        // The runtime stages are empty by design; tell the report so it does not
        // invent Kubernetes/Istio/pod/traffic facts that were never collected.
        boolean greenfield = isGreenfield(state.namespace);
        String mode = greenfield ? "greenfield" : "runtime";

        // Build the graph FIRST: it is the deterministic answer, and both the report's
        // dependency section and the posted picture are derived from it.
        DependencyGraph graph = buildGraph(state);
        AliasResolution.Answers aliasAnswers = AliasResolution.Answers.fromJson(
                state.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS));

        String prompt = "## Analysis mode (greenfield = static, no cluster; runtime = a namespace was given)\n"
                + mode + "\n\n"
                // The notes name services by module or class; the graph by deployment name.
                // Without these the report said cloud-api-gateway -> cloud-simple-serviceB
                // while the graph beside it said gateway -> simple-serviceb (2026-10-06).
                + serviceNamesForPrompt(graph, aliasAnswers)
                + "## Documentation + code dependency notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_MERGED_NOTES) + "\n\n"
                + "## Kubernetes / Istio runtime notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_K8S) + "\n\n"
                + "## Istio runtime-observed edge ledger (internal, mesh-to-mesh traffic)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC) + "\n\n"
                + "## Istio egress edge ledger (external dependencies leaving the mesh)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_EGRESS) + "\n\n"
                // Without this the report calls every database edge "runtime observed:
                // unknown" while the graph posted beside it draws that same edge solid —
                // the two contradict each other and the reader cannot tell which is right.
                // Neither ledger above can carry a db edge: Istio emits no HTTP metric for
                // a non-HTTP protocol, and the egress one is scoped outside the mesh.
                + "## Data-layer edges observed at runtime (TCP)\n"
                + dataLayerLedger(state);

        String response = llmToolkit.toolkitLlmCall(prompt, "dependency_analysis");

        String report = "## Microservice Dependency Analysis Report\n"
                + "**Repository:** `" + state.repoName + "` | **Namespace:** `" + state.namespace + "`\n\n"
                + spliceFactSections(normalizeServiceNames(response, graph, aliasAnswers), graph, greenfield)
                + nameResolutionSection(aliasAnswers);
        try {
            // toolkitDiscordText auto-sends as a file when the text is long,
            // matching how the report is delivered from the low-code flow.
            discordToolkit.toolkitDiscordText(report);
        } catch (IOException e) {
            jdaService.sendChatOpsChannelErrorMessage("[ERROR] failed to send the report: " + e.getMessage());
        }

        // Alongside the prose report, post the dependency graph as Mermaid. It is
        // built deterministically from the raw Istio Prometheus JSON (no LLM), so
        // it is another, more scannable reading of the same runtime observations.
        String coverage = postRuntimeGraph(graph, state);

        // Open the "ask the report" thread BEFORE the checkpoint goes: the archive it
        // builds takes the provenance notes from the state. It never throws.
        reportQaService.openQaThread(userId, state, mode, report, graph, coverage);

        stateStore.remove(userId);
    }

    /**
     * Puts the generated section 5 into the model's report, where section 5 belongs.
     *
     * The prompt tells the model to skip it and go from 4 straight to 6, so the splice
     * point is the "# 6." heading. If the model emitted its own section 5 anyway (they
     * do drift), that text is dropped — a report must not contain two answers to the
     * same question. With no recognisable heading the section is appended instead, so
     * the facts are never lost to a formatting surprise.
     */
    static String spliceInfrastructureSection(String response, DependencyGraph graph) {
        return spliceInfrastructureSection(response, graph, false);
    }

    /**
     * Puts the two generated fact sections — 4 (service-to-service) and 5
     * (infrastructure) — into the model's report, in place of whatever the model wrote
     * there. The model is told to skip both; if it drifted and wrote them anyway, its
     * version is dropped. With no "# 6." heading to anchor on, the sections are
     * appended instead: a duplicated section is a smaller harm than a lost fact.
     *
     * <p>Why section 4 too (2026-10-06, spring-cloud-microservice): the model listed
     * MyAppThriftClient -> FooService after the operator had said neither is a service,
     * and simple-ui -> simple-service while the graph beside it did not draw that edge.
     * The prompt asked for neither; asking harder would only move the error.
     */
    public static String spliceFactSections(String response, DependencyGraph graph, boolean greenfield) {
        String facts = synchronousSection(graph, greenfield) + infrastructureSection(graph, greenfield);
        if (response == null || response.isBlank()) return facts;

        java.util.regex.Matcher six = java.util.regex.Pattern.compile("(?m)^#+\\s*6\\.").matcher(response);
        if (!six.find()) return response + "\n\n" + facts;

        int cut = six.start();
        for (String n : new String[]{"5", "4"}) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^#+\\s*" + n + "\\.").matcher(response);
            if (m.find() && m.start() < cut) cut = m.start();
        }
        return response.substring(0, cut) + facts + response.substring(six.start());
    }

    /**
     * Section 4 — the service-to-service dependencies — written from the graph: exactly
     * the edges the graph draws, under its names, with their confidence and sources.
     */
    public static String synchronousSection(DependencyGraph graph, boolean greenfield) {
        StringBuilder sb = new StringBuilder("# 4. Synchronous Dependency Candidates\n\n");
        if (graph == null || graph.isEmpty()) {
            sb.append("None resolved from the collected provenance.\n\n");
            return sb.toString();
        }
        java.util.Map<String, DependencyGraph.Node> byId = new java.util.HashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) byId.put(node.id, node);

        List<DependencyGraph.Edge> sync = new ArrayList<>();
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            DependencyGraph.Node s = byId.get(edge.source);
            DependencyGraph.Node t = byId.get(edge.target);
            if (s == null || t == null || !isServiceKind(s) || !isServiceKind(t)) continue;
            if ("async".equals(edge.type) || "db".equals(edge.type) || "external".equals(edge.type)) continue;
            sync.add(edge);
        }
        sync.sort((a, b) -> {
            int o = Boolean.compare(b.runtimeObserved, a.runtimeObserved);
            if (o != 0) return o;
            int c = a.source.compareTo(b.source);
            return c != 0 ? c : a.target.compareTo(b.target);
        });

        long observed = sync.stream().filter(e -> e.runtimeObserved).count();
        if (greenfield) {
            sb.append("- Runtime observation: not measured (static run: no cluster was queried)\n");
            sb.append("- Declared in code, configuration or documentation: ").append(sync.size()).append("\n\n");
        } else {
            sb.append("- Direct runtime-observed synchronous invocations: ").append(observed).append('\n');
            sb.append("- Declared in code, configuration or documentation, not observed: ")
                    .append(sync.size() - observed).append("\n\n");
        }
        if (sync.isEmpty()) {
            sb.append("No service-to-service dependency was resolved.\n\n");
        }
        for (DependencyGraph.Edge edge : sync) {
            DependencyGraph.Node target = byId.get(edge.target);
            sb.append("### Candidate: ").append(edge.source).append(" -> ").append(edge.target).append('\n');
            // The graph records that an edge is synchronous, not its protocol: a Compose
            // "links" entry and a Thrift client both arrive as sync-http. Only the mesh
            // (an HTTP metric) or a gRPC capture says what is on the wire; the rest is
            // stated as not determined rather than written as HTTP (2026-10-06).
            sb.append("- Protocol: ").append(
                    "grpc".equals(edge.type) ? "gRPC"
                            : edge.runtimeObserved ? "HTTP (observed by the mesh)"
                            : "not determined (a synchronous dependency declared statically; the protocol is not recorded)")
                    .append('\n');
            sb.append("- Provenance: ").append(String.join(", ", edge.provenance)).append('\n');
            sb.append("- Runtime observed: ").append(
                    edge.runtimeObserved ? "Yes — " + edge.count + " requests observed by the mesh"
                            : greenfield ? "Unknown (static run: not measured)" : "No").append('\n');
            sb.append("- Confidence: ").append(confidenceWord(edge)).append('\n');
            sb.append("- Target deployed: ").append(
                    Boolean.TRUE.equals(target.deployed) ? "Yes"
                            : Boolean.FALSE.equals(target.deployed) ? "No — referenced but not running"
                            : greenfield ? "Not determined (static run: no cluster was queried)"
                            : "Not determined").append('\n');
            if (!edge.provenanceRefs.isEmpty()) {
                sb.append("- Provenance reference: ")
                        .append(String.join("; ", edge.provenanceRefs.subList(0, Math.min(3, edge.provenanceRefs.size()))))
                        .append('\n');
            }
            sb.append('\n');
        }
        if (!greenfield && observed == 0 && !sync.isEmpty()) {
            sb.append("No synchronous service-to-service invocation was directly observed in the "
                    + "collected runtime observations.\n\n");
        }
        sb.append("_This section is generated deterministically from the dependency graph, "
                + "not written by the language model, so it lists exactly the edges the graph draws._\n\n");
        return sb.toString();
    }

    private static boolean isServiceKind(DependencyGraph.Node n) {
        return n.kind == null || DependencyGraph.KIND_SERVICE.equals(n.kind) || DependencyGraph.KIND_GATEWAY.equals(n.kind);
    }

    /**
     * Rewrites the service names in the model's prose to the graph's names. The prompt
     * asks for this and the model mostly complies, but "hystrix-turbine" still appeared
     * where the graph says turbine (2026-10-06). Two rules, both deterministic:
     * <ol>
     *   <li>a name the operator mapped onto a service becomes that service;</li>
     *   <li>a hyphenated name that is a graph service with a prefix or a suffix of three
     *       or more letters ({@code cloud-api-gateway}, {@code hystrix-turbine},
     *       {@code discovery-server}) becomes that service, when exactly one fits —
     *       a suffix match is preferred, since modules prefix their deployment's name.</li>
     * </ol>
     * A name inside a path, a file name or a URL is left alone: {@code /cloud-simple-service/**}
     * is a route that really is called that, and {@code cloud-api-gateway/src/…} is a
     * file. Only prose changes; the graph and the generated sections are untouched.
     */
    public static String normalizeServiceNames(String text, DependencyGraph graph, AliasResolution.Answers answers) {
        if (text == null || text.isEmpty() || graph == null) return text;
        java.util.Map<String, String> lettersToNode = new java.util.LinkedHashMap<>();
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (isServiceKind(n)) lettersToNode.put(n.id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", ""), n.id);
        }

        String out = text;
        if (answers != null) {
            for (Map.Entry<String, String> e : answers.asMap().entrySet()) {
                String d = e.getValue();
                if (AliasResolution.IGNORE.equals(d) || AliasResolution.NEW.equals(d) || graph.findNode(d) == null) continue;
                out = replaceOutsidePaths(out, java.util.regex.Pattern.quote(e.getKey()), d);
            }
        }

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![\\w/.:@\\\\-])[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+(?![\\w/\\\\-]|\\.[A-Za-z])")
                .matcher(out);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String token = m.group();
            String letters = token.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (lettersToNode.containsKey(letters)) continue;   // already a graph name (any spelling)
            String suffix = null, prefix = null;
            int suffixCount = 0, prefixCount = 0;
            for (Map.Entry<String, String> n : lettersToNode.entrySet()) {
                String nl = n.getKey();
                if (nl.length() < 5 || letters.length() - nl.length() < 3) continue;
                if (letters.endsWith(nl)) { suffix = n.getValue(); suffixCount++; }
                else if (letters.startsWith(nl)) { prefix = n.getValue(); prefixCount++; }
            }
            String replacement = suffixCount == 1 ? suffix : (suffixCount == 0 && prefixCount == 1 ? prefix : null);
            if (replacement == null) continue;
            sb.append(out, last, m.start()).append(replacement);
            last = m.end();
        }
        sb.append(out.substring(last));
        return sb.toString();
    }

    /** Replaces a phrase where it is a whole word and not part of a path, a file name or a URL. */
    private static String replaceOutsidePaths(String text, String quotedPhrase, String replacement) {
        return text.replaceAll("(?<![\\w/.:@\\\\-])" + quotedPhrase + "(?![\\w/\\\\-]|\\.[A-Za-z])",
                java.util.regex.Matcher.quoteReplacement(replacement));
    }

    static String spliceInfrastructureSection(String response, DependencyGraph graph, boolean greenfield) {
        String section = infrastructureSection(graph, greenfield);
        if (response == null || response.isBlank()) return section;

        java.util.regex.Matcher six = java.util.regex.Pattern
                .compile("(?m)^#+\\s*6\\.").matcher(response);
        if (!six.find()) return response + "\n\n" + section;

        java.util.regex.Matcher five = java.util.regex.Pattern
                .compile("(?m)^#+\\s*5\\.").matcher(response);
        int cut = (five.find() && five.start() < six.start()) ? five.start() : six.start();
        return response.substring(0, cut) + section + response.substring(six.start());
    }

    /**
     * Section 5 of the report — the infrastructure dependencies — written by code
     * rather than by the model.
     *
     * This section is pure fact: which workload depends on which datastore/broker/
     * external host, whether the mesh observed it, and how confident the tool is.
     * The graph already holds all of it. Asking the model to restate it added nothing
     * and produced, on consecutive runs of the same system, "runtime observed:
     * unknown" for edges the graph drew solid, and then a list of build-time libraries
     * (Micrometer, Log4j2) and cloud alternatives (Cloud SQL, GKE) that are not
     * dependencies of the analysed deployment at all. Each time, tightening the prompt
     * moved the failure rather than removing it.
     *
     * So the section is generated here and the model is told to skip it. That is the
     * project's standing rule — deterministic first, the LLM only for what needs
     * language — applied to the last place that was still ignoring it.
     */
    static String infrastructureSection(DependencyGraph graph) {
        return infrastructureSection(graph, false);
    }

    /**
     * @param greenfield a static run: no cluster was queried, so "deployed" is unknown
     *                   for that reason and no other — the runtime wording ("externally
     *                   managed, or a StatefulSet") gave a static report a reason that
     *                   was not true (2026-10-06)
     */
    public static String infrastructureSection(DependencyGraph graph, boolean greenfield) {
        StringBuilder sb = new StringBuilder("# 5. Infrastructure Dependencies\n\n");
        if (graph == null || graph.isEmpty()) {
            sb.append("None resolved from the collected provenance.\n\n");
            return sb.toString();
        }

        java.util.Map<String, DependencyGraph.Node> byId = new java.util.HashMap<>();
        for (DependencyGraph.Node node : graph.getNodes()) byId.put(node.id, node);

        List<DependencyGraph.Edge> infra = new java.util.ArrayList<>();
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            DependencyGraph.Node target = byId.get(edge.target);
            if (target == null) continue;
            String kind = target.kind == null ? DependencyGraph.KIND_SERVICE : target.kind;
            if (DependencyGraph.KIND_DB.equals(kind) || DependencyGraph.KIND_QUEUE.equals(kind)
                    || DependencyGraph.KIND_EXTERNAL.equals(kind)) {
                infra.add(edge);
            }
        }
        if (infra.isEmpty()) {
            sb.append("No datastore, broker or external dependency was found in the "
                    + "collected provenance.\n\n");
            return sb.toString();
        }

        // Observed first: those are the measurements, and they are what a reader
        // checking the graph against the report will look for.
        infra.sort((a, b) -> Boolean.compare(b.runtimeObserved, a.runtimeObserved));

        for (DependencyGraph.Edge edge : infra) {
            DependencyGraph.Node target = byId.get(edge.target);
            String kind = target.kind;
            sb.append("### Infrastructure dependency: ").append(edge.source)
                    .append(" -> ").append(edge.target).append('\n');
            sb.append("- Dependency type: ").append(
                    DependencyGraph.KIND_DB.equals(kind) ? "database"
                            : DependencyGraph.KIND_QUEUE.equals(kind) ? "message broker"
                            : "external service").append('\n');
            sb.append("- Provenance: ").append(String.join(", ", edge.provenance)).append('\n');
            sb.append("- Runtime observed: ").append(
                    edge.runtimeObserved ? "Yes"
                            : greenfield ? "Unknown (static run: not measured)" : "No").append('\n');
            if (edge.runtimeObserved) {
                // Named precisely: for a database this is connections, not requests.
                sb.append("- Runtime provenance: ").append(edge.count).append(
                        DependencyGraph.KIND_DB.equals(kind)
                                ? " TCP connections observed (a connection count, not a request count)"
                                : " observed by the mesh").append('\n');
            }
            sb.append("- Confidence: ").append(confidenceWord(edge)).append('\n');
            sb.append("- Deployed: ").append(
                    Boolean.TRUE.equals(target.deployed) ? "Yes"
                            : Boolean.FALSE.equals(target.deployed) ? "No — referenced but not running"
                            : greenfield ? "Not determined (static run: no cluster was queried)"
                            : "Not determined (externally managed, or a StatefulSet rather than a Deployment)")
                    .append('\n');
            if (!edge.provenanceRefs.isEmpty()) {
                sb.append("- Provenance reference: ").append(edge.provenanceRefs.get(0)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("_This section is generated deterministically from the dependency graph, "
                + "not written by the language model, so it always agrees with the graph "
                + "posted alongside this report._\n\n");
        return sb.toString();
    }

    /**
     * The graph's service names and the operator's name answers, for the report prompt,
     * so the prose names each service the way the graph beside it does.
     */
    public static String serviceNamesForPrompt(DependencyGraph graph, AliasResolution.Answers answers) {
        StringBuilder sb = new StringBuilder();
        if (graph != null && !graph.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (DependencyGraph.Node n : graph.getNodes()) {
                if (n.kind == null || DependencyGraph.KIND_SERVICE.equals(n.kind)
                        || DependencyGraph.KIND_GATEWAY.equals(n.kind)) names.add(n.id);
            }
            java.util.Collections.sort(names);
            sb.append("## Service names on the graph (use these names)\n")
                    .append(String.join(", ", names)).append("\n\n");
        }
        if (answers != null && !answers.isEmpty()) {
            sb.append("## Service names resolved by the operator\n");
            for (Map.Entry<String, String> e : answers.asMap().entrySet()) {
                String d = e.getValue();
                sb.append("- ").append(e.getKey()).append(" = ").append(
                        AliasResolution.IGNORE.equals(d) ? "ignored (not a service)"
                                : AliasResolution.NEW.equals(d) ? AliasResolution.nodeId(e.getKey()) + " (a service of its own)"
                                : d).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The names the operator resolved for this project, written by code so the reader
     * can see which edges rest on a human's word rather than on a rule. Empty when
     * nothing was asked — the section is then absent, not an empty heading.
     */
    public static String nameResolutionSection(AliasResolution.Answers answers) {
        if (answers == null || answers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n# Names resolved by the operator\n\n");
        sb.append("The documentation or the code used these names for services and no alignment "
                + "rule could map them, so the tool asked instead of guessing. The answers below "
                + "are applied wherever the name appears and are remembered for this repository.\n\n");
        for (Map.Entry<String, String> e : answers.asMap().entrySet()) {
            sb.append("- `").append(e.getKey()).append("` ")
                    .append(AliasResolution.describe(e.getValue())).append('\n');
        }
        sb.append("\n_This section is generated from the operator's answers, not written by the "
                + "language model._\n\n");
        return sb.toString();
    }

    /** How the report should describe an edge's confidence. */
    private static String confidenceWord(DependencyGraph.Edge edge) {
        if (edge.runtimeObserved) return "High — confirmed at runtime";
        if (DependencyGraph.CONF_DOCUMENTED.equals(edge.confidence)) {
            return "Medium — declared in code/docs with a usage signal, not observed at runtime";
        }
        return "Low — declared only (configuration or documentation), no usage signal";
    }

    /**
     * The database edges the mesh has observed, as a deterministic ledger for the
     * report's LLM step. Built straight from the TCP stage — no full graph merge — so
     * it states a fact rather than handing the model raw Prometheus JSON to re-derive
     * (which is how the previous report ended up disagreeing with its own graph).
     *
     * @return the ledger, or a line stating there is none (an empty section reads as
     *         an omission and invites the model to fill it in).
     */
    private String dataLayerLedger(DependencyAnalysisStateStore.State state) {
        DependencyGraph graph = new DependencyGraph(state.namespace);
        RuntimeGraphBuilder.mergeIstioTcp(graph,
                state.stage(DependencyAnalysisStateStore.STAGE_TCP_RAW));
        if (graph.isEmpty()) {
            return "No data-layer (TCP) edges were observed. In greenfield mode none are "
                    + "collected at all; in a runtime run this means no database connection "
                    + "was seen.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Istio emits no istio_requests_total for a non-HTTP protocol, so a database "
                + "dependency appears ONLY here, never in the HTTP ledger above. Every edge "
                + "listed is CONFIRMED runtime-observed — do NOT report it as 'unknown':\n");
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            sb.append("- ").append(edge.source).append(" -> ").append(edge.target)
                    .append("  (").append(edge.count)
                    .append(" TCP connections observed; a connection count, NOT a request count)\n");
        }
        return sb.toString();
    }

    /**
     * Builds the fully-merged dependency graph from the checkpoint.
     *
     * Split out so the report and the picture are produced from the SAME object. They
     * used to be derived separately — the graph here, the report from raw stages via an
     * LLM — and they contradicted each other: the graph drew userservice -> accounts-db
     * solid while the report called it "runtime observed: unknown". One source, one
     * answer.
     */
    private DependencyGraph buildGraph(DependencyAnalysisStateStore.State state) {
        return buildGraph(state, null);
    }

    /**
     * The names the documentation and the residual code edges use that no rule could
     * map onto the graph — for the operator to resolve BEFORE the report, the way the
     * traffic generator asks for a value it cannot derive. Builds the same merged
     * graph the report will build, so the questions are exactly the names the report
     * would otherwise have dropped; the graph itself is discarded here. Names the
     * operator already answered (this run, or an earlier run of the same repository)
     * are applied, not asked again.
     */
    public AliasResolution.Questions aliasQuestions(DependencyAnalysisStateStore.State state) {
        AliasResolution.Questions questions = new AliasResolution.Questions();
        if (state != null) buildGraph(state, questions);
        return questions;
    }

    /**
     * @param questions receives every service name the merge could not align and the
     *                  operator has not decided on; null when the caller does not ask
     */
    private DependencyGraph buildGraph(DependencyAnalysisStateStore.State state,
                                       AliasResolution.Questions questions) {
        try {
            String raw = state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC_RAW);
            DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(raw, state.namespace);
            AliasResolution.Answers answers = AliasResolution.Answers.fromJson(
                    state.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS));

            // Fold in runtime-observed EXTERNAL edges from the egress telemetry: an
            // attributed external host (a ServiceEntry exists, e.g. github.com) merges
            // onto the code-declared external edge and upgrades it from dashed to solid.
            RuntimeGraphBuilder.mergeIstioEgress(graph, state.stage(DependencyAnalysisStateStore.STAGE_EGRESS_RAW));

            // Fold in runtime-observed IN-MESH TCP edges — in practice the database.
            // Istio emits istio_requests_total only for HTTP/gRPC, so a MySQL/Postgres
            // dependency is invisible above; without this it can never be more than a
            // code-declared dashed edge, however real it is.
            RuntimeGraphBuilder.mergeIstioTcp(graph, state.stage(DependencyAnalysisStateStore.STAGE_TCP_RAW));

            // Enrich with code edges: deterministic first, LLM only for the residue.
            List<CodeGraphMerger.Unresolved> residue = CodeGraphMerger.merge(
                    graph,
                    state.stage(DependencyAnalysisStateStore.STAGE_CODE_EDGES),
                    state.repoName);
            // The operator's word first: a name they already resolved is not a residue.
            residue = applyAliasAnswers(graph, residue, answers);
            List<CodeGraphMerger.Unresolved> leftovers = resolveResidueWithLlm(graph, residue);
            // What neither the rules nor the LLM could place is a question, not a loss.
            recordCodeQuestions(graph, leftovers, questions);

            // Merge the documentation (DeepWiki) ledger as doc-provenance edges: the
            // dependencies only the docs name (an externalised datasource, a
            // documented association). Never runtime fact — dashed/dotted, and a
            // db a service really uses (persistence code) outranks a doc-only one.
            DocGraphMerger.merge(graph, state.stage(DependencyAnalysisStateStore.STAGE_MERGED_NOTES),
                    answers, questions, state.repoName);

            // Promote any db a persistence-bearing service uses to "really used",
            // whichever provenance the db edge came from. The datasource is often
            // externalised (petclinic keeps it in the config-server), so its db edges
            // are doc-derived — the JPA proof in the service source must still reach them.
            CodeGraphMerger.promoteReallyUsedDbs(graph, CodeGraphMerger.persistenceServices(
                    graph, state.stage(DependencyAnalysisStateStore.STAGE_CODE_EDGES), state.repoName));

            // Enrich the (now complete) node set with K8s deployment status, so a
            // service referenced in code/docs but not running in the cluster renders
            // greyed/dashed, and a live one carries its image/replicas/created date.
            // Deterministic; a no-op on an old checkpoint without the raw k8s stage.
            K8sGraphBuilder.enrich(graph, state.stage(DependencyAnalysisStateStore.STAGE_K8S_RAW));

            // Final clean-up: collapse code/doc aliases (api-gateway-controller -> api-gateway)
            // and drop framework-library / grouping pseudo-nodes (resilience4j, jolokia,
            // all-services, …) that are not real workloads. Runs after k8s enrichment so it
            // only ever touches undeployed nodes, never a live service. A library name the
            // deployment layer drew edges on (Compose's "hystrix" dashboard) is a question
            // for the operator, not a silent drop.
            GraphNormalizer.normalize(graph, answers, questions);
            if (questions != null) {
                List<String> services = new ArrayList<>();
                for (DependencyGraph.Node n : graph.getNodes()) {
                    if (n.kind == null || DependencyGraph.KIND_SERVICE.equals(n.kind)
                            || DependencyGraph.KIND_GATEWAY.equals(n.kind)) services.add(n.id);
                }
                questions.addVocabulary(services);
            }

            // Tier the nodes (ingress -> services by call depth -> data stores), so a
            // graph the size of train-ticket's reads as a system instead of a hairball.
            // After normalize on purpose: a phantom node would otherwise occupy a tier
            // and push everything below it one level deeper.
            GraphLayerAssigner.assign(graph);
            return graph;
        } catch (Exception e) {
            System.out.println("[WARNING] could not build the dependency graph: " + e.getMessage());
            return null;
        }
    }

    /**
     * Renders the dependency graph and posts it.
     *
     * The backbone is built deterministically from the raw Istio Prometheus JSON
     * (no LLM). The structured code edges are then merged on deterministically
     * where their targets resolve onto known workloads; only the residue the
     * deterministic pass cannot map is handed to the LLM for name alignment
     * ("prefer not to use the LLM, only where necessary"). Runtime edges render
     * solid; code/doc-only edges render dashed.
     *
     * It is rendered to a PNG via Graphviz so it is visible inline in the channel;
     * if {@code dot} is unavailable the Mermaid source is attached instead (still
     * renderable at mermaid.live). Either way the .mmd source is attached too.
     *
     * @return the coverage message that was posted, or {@code null} when there was
     *         nothing to measure or the graph could not be posted — the Q&amp;A archive
     *         keeps it as an authoritative source
     */
    private String postRuntimeGraph(DependencyGraph graph, DependencyAnalysisStateStore.State state) {
        try {
            if (graph == null) return null;

            if (graph.isEmpty()) {
                // No runtime edges and nothing resolvable from code (or an old
                // checkpoint without the raw/code stages). The prose report already
                // covers this, so stay quiet rather than post an empty graph.
                return null;
            }

            String mermaid = MermaidEmitter.emit(graph);
            byte[] png = GraphvizRenderer.toPng(DotEmitter.emit(graph));

            // Name the files after the repo so a graph is identifiable on its own.
            String base = graphBaseName(state);
            if (png != null) {
                jdaService.sendChatOpsChannelMessage(
                        "## " + DependencyGraph.TOOL_NAME + " · Dependency Graph — `" + state.repoName + "`\n"
                                + "Solid arrows are edges Istio observed at runtime; dashed arrows are "
                                + "declared in code/doc but not observed. Greyed dashed nodes are referenced "
                                + "but not deployed in the cluster; live nodes show image · replicas · created "
                                + "date. The `.mmd` source is attached too (edit at https://mermaid.live).");
                jdaService.sendChatOpsChannelFile(base + ".png", new ByteArrayInputStream(png));
                jdaService.sendChatOpsChannelFile(base + ".mmd",
                        new ByteArrayInputStream(mermaid.getBytes(StandardCharsets.UTF_8)));
            } else {
                jdaService.sendChatOpsChannelMessage(
                        "## " + DependencyGraph.TOOL_NAME + " · Dependency Graph — `" + state.repoName + "`\n"
                                + "Paste the attached `.mmd` into https://mermaid.live (or a Markdown "
                                + "file) to render it. Solid arrows are edges Istio observed at runtime; "
                                + "dashed arrows are declared in code/doc but not observed.");
                jdaService.sendChatOpsChannelFile(base + ".mmd",
                        new ByteArrayInputStream(mermaid.getBytes(StandardCharsets.UTF_8)));
            }

            String coverage = isGreenfield(state.namespace)
                    ? staticCoverageMessage(graph, state.repoName)
                    : coverageMessage(graph, state.repoName);
            if (coverage != null) jdaService.sendChatOpsChannelMessage(coverage);
            return coverage;
        } catch (Exception e) {
            // The graph is a bonus view; never let it break the report delivery.
            System.out.println("[WARNING] could not post the dependency graph: " + e.getMessage());
            return null;
        }
    }

    /**
     * The deterministic runtime traffic-coverage summary derived from the graph:
     * of the service-to-service sync edges, how many the mesh actually observed, and
     * which ones traffic never reached. The uncovered edges are exactly the dashed
     * business edges — the concrete targets for driving more traffic and Resuming.
     *
     * @return the message, or {@code null} when nothing is measurable (no service→service edges)
     */
    /**
     * The coverage message for a static (greenfield) run, where nothing was measured.
     *
     * The runtime message used to be posted here too and read "Istio observed 0 / 23 …
     * 0% runtime coverage" and "the datastore is deployed" — on a run that had no
     * cluster, no Istio and no deployment (spring-cloud-microservice, 2026-10-06). That
     * is the failure the language's fifth pattern is about: an unmeasured value shown as
     * zero. So a static run states that coverage was not measured and why, and lists the
     * same declared edges as what to verify once the system runs — not as gaps.
     *
     * @return the message, or {@code null} when there are no business edges at all
     */
    public static String staticCoverageMessage(DependencyGraph graph, String repoName) {
        CoverageAnalyzer.Report coverage = CoverageAnalyzer.analyze(graph);
        if (!coverage.hasEdges() && !coverage.hasDbEdges()) return null;

        StringBuilder msg = new StringBuilder();
        msg.append("## ").append(DependencyGraph.TOOL_NAME)
                .append(" · Runtime Traffic Coverage — `").append(repoName).append("`\n")
                .append("**Not measured.** This was a static (greenfield) run: no namespace was given, so "
                        + "no cluster was queried, no traffic was driven and no telemetry was collected. "
                        + "Runtime coverage is unknown, not 0%.\n");
        if (coverage.hasEdges()) {
            msg.append("\nThe code and documentation declare **").append(coverage.total)
                    .append("** business (service→service) edge(s). Once the system is deployed, "
                            + "run the analysis with its namespace to see which of them traffic actually crosses:\n");
            for (String edge : coverage.uncovered) msg.append("- `").append(edge).append("`\n");
        }
        if (coverage.hasDbEdges()) {
            msg.append("\n**Data layer:** not measured either. **").append(coverage.dbTotal)
                    .append("** datastore edge(s) are declared; whether they are deployed or connected is unknown:\n");
            for (String edge : coverage.dbUncovered) msg.append("- `").append(edge).append("`\n");
        }
        if (coverage.mentionedOnly > 0) {
            msg.append("\n_Also ").append(coverage.mentionedOnly)
                    .append(" edge(s) are only mentioned (no usage signal), drawn dotted._\n");
        }
        return msg.toString();
    }

    static String coverageMessage(DependencyGraph graph, String repoName) {
        CoverageAnalyzer.Report coverage = CoverageAnalyzer.analyze(graph);
        if (!coverage.hasEdges()) return null;

        StringBuilder msg = new StringBuilder();
        msg.append("## ").append(DependencyGraph.TOOL_NAME)
                .append(" · Runtime Traffic Coverage — `").append(repoName).append("`\n")
                .append("Istio observed **").append(coverage.observed).append(" / ")
                .append(coverage.total).append("** business (service→service) edges — **")
                .append(coverage.percent()).append("%** runtime coverage.\n");
        if (coverage.uncovered.isEmpty()) {
            msg.append("Every business edge was exercised by the driven traffic.");
        } else {
            msg.append("Uncovered edges (declared in code/doc, no traffic yet) — drive a journey "
                    + "through these and Resume to close the gap:\n");
            for (String edge : coverage.uncovered) msg.append("- `").append(edge).append("`\n");
        }

        // The data layer, reported as its own number rather than mixed into the ratio
        // above: a db connection is opaque TCP, observed through istio_tcp_* rather
        // than by a journey crossing it, so the two are not the same measurement.
        if (coverage.hasDbEdges()) {
            msg.append("\n**Data layer** (separate measure — TCP connections, not requests): **")
                    .append(coverage.dbObserved).append(" / ").append(coverage.dbTotal)
                    .append("** datastore edges observed — **").append(coverage.dbPercent())
                    .append("%**.\n");
            if (!coverage.dbUncovered.isEmpty()) {
                msg.append("Declared but no connection seen — the datastore is deployed, so either "
                        + "nothing exercised that service, or its pods started before the database "
                        + "was reachable (connection pools open once):\n");
                for (String edge : coverage.dbUncovered) msg.append("- `").append(edge).append("`\n");
            }
        }

        // The denominator's composition, stated. Excluding merely-mentioned edges keeps
        // the score from being diluted by documentation prose — but the same tier is
        // where a weak extraction lands, and a shrinking denominator flatters a run that
        // found nothing. The reader is given both numbers rather than one of them.
        if (coverage.mentionedOnly > 0) {
            msg.append("\n_Not scored: ").append(coverage.mentionedOnly)
                    .append(" edge(s) mentioned with no usage signal — drawn dotted, "
                            + "excluded from both ratios._");
            if (coverage.isLowConfidence()) {
                msg.append("\n⚠️ Those outnumber the scored edges: the extraction produced "
                        + "few confirmed edges for this project (low confidence), so this percentage rests "
                        + "on a small surface.");
            }
            msg.append('\n');
        }
        return msg.toString();
    }

    /**
     * Resolves the code edges the deterministic pass could not map, using the LLM
     * purely for name alignment onto the known nodes. Strictly additive and fully
     * guarded: any failure (LLM unreachable, non-JSON output, a hallucinated node)
     * leaves the deterministic graph untouched. An edge is added only when both
     * endpoints validate against the known vocabulary.
     */
    private List<CodeGraphMerger.Unresolved> resolveResidueWithLlm(DependencyGraph graph,
                                                                   List<CodeGraphMerger.Unresolved> residue) {
        List<CodeGraphMerger.Unresolved> leftovers = new ArrayList<>();
        if (residue == null || residue.isEmpty()) return leftovers;
        leftovers.addAll(residue);
        try {
            Set<String> knownNodes = new HashSet<>();
            for (DependencyGraph.Node node : graph.getNodes()) knownNodes.add(node.id);

            JSONArray rows = new JSONArray();
            for (CodeGraphMerger.Unresolved u : residue) rows.put(u.toJson());
            String prompt = "## KNOWN NODES\n" + new JSONArray(knownNodes)
                    + "\n\n## NAMESPACE\n" + graph.getNamespace()
                    + "\n\n## UNRESOLVED EDGES\n" + rows;

            String response = llmToolkit.toolkitLlmCall(prompt, "dependency_graph_residue");
            JSONArray mapped = parseJsonArray(response);
            if (mapped == null) return leftovers;

            int added = 0;
            Set<String> resolvedRaw = new HashSet<>();
            for (int i = 0; i < mapped.length(); i++) {
                JSONObject row = mapped.optJSONObject(i);
                if (row == null) continue;
                String source = row.optString("source", "");
                String target = row.optString("target", "");
                String type = row.optString("type", "sync-http");
                String confidence = row.optString("confidence", DependencyGraph.CONF_INFERRED);
                if (addLlmEdge(graph, knownNodes, source, target, type, confidence)) {
                    added++;
                    // The prompt asks the model to echo the row it resolved; an older
                    // model answer without it is matched on the target instead.
                    String echoed = row.optString("target_raw", "");
                    resolvedRaw.add(AliasResolution.key(echoed.isBlank() ? target : echoed));
                    resolvedRaw.add(AliasResolution.key(target));
                }
            }
            if (added > 0) System.out.println("[INFO] dependency graph: LLM aligned " + added
                    + " of " + residue.size() + " residual code edge(s).");
            leftovers.removeIf(u -> u.rawTarget != null && resolvedRaw.contains(AliasResolution.key(u.rawTarget)));
        } catch (Exception e) {
            // Necessary-only LLM step: on any problem, keep the deterministic graph.
            System.out.println("[WARNING] residue LLM alignment skipped: " + e.getMessage());
        }
        return leftovers;
    }

    /**
     * Applies the operator's alias answers to the code edges the deterministic pass
     * could not map, before the LLM sees them: a human's word is the strongest
     * alignment there is, and an edge they resolved is not a residue any more.
     *
     * @return the residue that is still open (no answer, or a source the graph does not know)
     */
    public static List<CodeGraphMerger.Unresolved> applyAliasAnswers(DependencyGraph graph,
                                                             List<CodeGraphMerger.Unresolved> residue,
                                                             AliasResolution.Answers answers) {
        List<CodeGraphMerger.Unresolved> rest = new ArrayList<>();
        if (residue == null) return rest;
        for (CodeGraphMerger.Unresolved u : residue) {
            // The caller first: a module directory the operator mapped onto a service
            // ("cloud-hystrix-dashboard" is "hystrix") becomes that service.
            String source = u.rawSource;
            String sourceDecision = (source == null || answers == null) ? null : answers.decisionFor(source);
            if (sourceDecision != null) {
                if (AliasResolution.IGNORE.equals(sourceDecision)) continue;   // that directory is not a service
                if (AliasResolution.NEW.equals(sourceDecision)) {
                    source = AliasResolution.nodeId(source);
                    if (source.isEmpty()) continue;
                    graph.addNode(source, DependencyGraph.classifyKind(source));
                } else if (graph.findNode(sourceDecision) != null) {
                    source = sourceDecision;
                }
            }
            boolean sourceKnown = source != null && graph.findNode(source) != null;

            String decided = (u.rawTarget == null || answers == null) ? null : answers.decisionFor(u.rawTarget);
            if (decided == null) {
                // No word on the callee. A caller the operator just settled is handed on
                // under its real name, so the LLM pass sees a known source.
                rest.add(sourceDecision != null && sourceKnown
                        ? new CodeGraphMerger.Unresolved(u.section, source, u.rawTarget, u.file, u.line) : u);
                continue;
            }
            if (AliasResolution.IGNORE.equals(decided)) continue;   // not a service: the edge goes nowhere
            if (!sourceKnown) {
                rest.add(u);   // the callee is settled, the caller is not: the LLM may still place it
                continue;
            }
            String target;
            if (AliasResolution.NEW.equals(decided)) {
                target = AliasResolution.nodeId(u.rawTarget);
                if (target.isEmpty()) continue;
                graph.addNode(target, DependencyGraph.classifyKind(target));
            } else if (graph.findNode(decided) != null) {
                target = decided;
            } else {
                rest.add(u);   // answered against a vocabulary this run no longer has
                continue;
            }
            if (source.equals(target)) continue;
            graph.addEdge(source, target, "sync-http", DependencyGraph.PROV_CODE,
                    DependencyGraph.CONF_DOCUMENTED, false, 0,
                    "code (operator-aligned): " + u.file + (u.line > 0 ? ":" + u.line : ""));
        }
        return rest;
    }

    /**
     * Turns the code residue nobody could place into questions for the operator. A
     * token with a dot or only digits is a host or an address, which the external-host
     * path owns; only a service-looking name is worth asking about.
     */
    public static void recordCodeQuestions(DependencyGraph graph, List<CodeGraphMerger.Unresolved> leftovers,
                                    AliasResolution.Questions questions) {
        if (questions == null || leftovers == null || leftovers.isEmpty()) return;
        List<String> services = new ArrayList<>();
        for (DependencyGraph.Node n : graph.getNodes()) {
            if (n.kind == null || DependencyGraph.KIND_SERVICE.equals(n.kind)
                    || DependencyGraph.KIND_GATEWAY.equals(n.kind)) services.add(n.id);
        }
        for (CodeGraphMerger.Unresolved u : leftovers) {
            String where = u.section + " at " + u.file + (u.line > 0 ? ":" + u.line : "");
            // The caller: a module directory no service on the graph is named after
            // ("cloud-hystrix-dashboard" when the deployment calls it "hystrix").
            String src = u.rawSource;
            if (src != null && !src.isBlank() && graph.findNode(src) == null && looksLikeServiceName(src)) {
                questions.add(src, "code", "the module directory of " + where
                        + " — no service on the graph has this name", services);
            }
            String raw = u.rawTarget;
            if (raw == null || raw.isBlank() || !looksLikeServiceName(raw)) continue;
            if (u.section != null && (u.section.startsWith("kafka") || u.section.startsWith("rabbit"))) continue;
            questions.add(raw, "code", where, services);
        }
    }

    /** A token worth asking about: a name, not a host, an address, a URL or a placeholder. */
    private static boolean looksLikeServiceName(String token) {
        String t = token.trim();
        if (t.isEmpty() || t.contains(".") || t.contains("/") || t.contains("$") || t.contains(":")) return false;
        return !t.matches("[\\d]+") && t.length() <= 63;
    }

    /** Adds one LLM-aligned edge iff its endpoints validate. Returns whether it was added. */
    private boolean addLlmEdge(DependencyGraph graph, Set<String> knownNodes,
                               String source, String target, String type, String confidence) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) return false;
        // The source must be an existing, real workload — never invent a caller.
        if (!knownNodes.contains(source)) return false;
        if (source.equals(target)) return false;

        String targetId;
        if (target.startsWith("external:")) {
            targetId = target.substring("external:".length()).trim();
            if (targetId.isEmpty()) return false;
            graph.addNode(targetId, DependencyGraph.KIND_EXTERNAL);
            type = "external";
        } else if (target.startsWith("queue:")) {
            targetId = target.substring("queue:".length()).trim();
            if (targetId.isEmpty()) return false;
            graph.addNode(targetId, DependencyGraph.KIND_QUEUE);
            type = "async";
        } else if (knownNodes.contains(target)) {
            targetId = target; // must be an existing workload, verbatim
        } else {
            return false; // a hallucinated / unknown target is rejected
        }

        String conf = DependencyGraph.CONF_DOCUMENTED.equals(confidence)
                ? DependencyGraph.CONF_DOCUMENTED : DependencyGraph.CONF_INFERRED;
        graph.addEdge(source, targetId, type,
                DependencyGraph.PROV_CODE, conf, false, 0, "code (LLM-aligned)");
        return true;
    }

    /** A file base name for the graph, keyed to the repo (e.g. "spring-petclinic-microservices-dependency-graph"). */
    private static String graphBaseName(DependencyAnalysisStateStore.State state) {
        String repo = state == null || state.repoName == null ? "" : state.repoName.trim();
        int slash = repo.lastIndexOf('/');
        if (slash >= 0 && slash < repo.length() - 1) repo = repo.substring(slash + 1);
        repo = repo.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-|-$)", "");
        return (repo.isEmpty() ? "" : repo + "-") + "dependency-graph";
    }

    /** Extracts the first JSON array from an LLM response, tolerating markdown fences/prose. */
    private static JSONArray parseJsonArray(String response) {
        if (response == null) return null;
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONArray(response.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
