package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DiscordToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.LlmToolkit;
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
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
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

    @Autowired
    public DependencyReportService(DependencyAnalysisStateStore stateStore,
                                   LlmToolkit llmToolkit,
                                   DiscordToolkit discordToolkit,
                                   @Lazy JDAService jdaService) {
        this.stateStore = stateStore;
        this.llmToolkit = llmToolkit;
        this.discordToolkit = discordToolkit;
        this.jdaService = jdaService;
    }

    /**
     * Generates and posts the report for the given user from the stored evidence.
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

        String prompt = "## Analysis mode (greenfield = static, no cluster; runtime = a namespace was given)\n"
                + mode + "\n\n"
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
                + spliceInfrastructureSection(response, graph);
        try {
            // toolkitDiscordText auto-sends as a file when the text is long,
            // matching how the report is delivered from the low-code flow.
            discordToolkit.toolkitDiscordText(report);
        } catch (IOException e) {
            jdaService.sendChatOpsChannelErrorMessage("[ERROR] failed to send the report: " + e.getMessage());
        }

        // Alongside the prose report, post the dependency graph as Mermaid. It is
        // built deterministically from the raw Istio Prometheus JSON (no LLM), so
        // it is another, more scannable reading of the same runtime evidence.
        postRuntimeGraph(graph, state);

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
        String section = infrastructureSection(graph);
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
     * external host, whether the mesh observed it, and how strong the evidence is.
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
        StringBuilder sb = new StringBuilder("# 5. Infrastructure Dependencies\n\n");
        if (graph == null || graph.isEmpty()) {
            sb.append("None resolved from the collected evidence.\n\n");
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
                    + "collected evidence.\n\n");
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
            sb.append("- Evidence: ").append(String.join(", ", edge.provenance)).append('\n');
            sb.append("- Runtime observed: ").append(edge.runtimeObserved ? "Yes" : "No").append('\n');
            if (edge.runtimeObserved) {
                // Named precisely: for a database this is connections, not requests.
                sb.append("- Runtime evidence: ").append(edge.count).append(
                        DependencyGraph.KIND_DB.equals(kind)
                                ? " TCP connections observed (a connection count, not a request count)"
                                : " observed by the mesh").append('\n');
            }
            sb.append("- Confidence: ").append(confidenceWord(edge)).append('\n');
            sb.append("- Deployed: ").append(
                    Boolean.TRUE.equals(target.deployed) ? "Yes"
                            : Boolean.FALSE.equals(target.deployed) ? "No — referenced but not running"
                            : "Not determined (externally managed, or a StatefulSet rather than a Deployment)")
                    .append('\n');
            if (!edge.evidence.isEmpty()) {
                sb.append("- Evidence reference: ").append(edge.evidence.get(0)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("_This section is generated deterministically from the dependency graph, "
                + "not written by the language model, so it always agrees with the graph "
                + "posted alongside this report._\n\n");
        return sb.toString();
    }

    /** How the report should describe an edge's evidence strength. */
    private static String confidenceWord(DependencyGraph.Edge edge) {
        if (edge.runtimeObserved) return "High — confirmed at runtime";
        if (DependencyGraph.CONF_DOCUMENTED.equals(edge.confidence)) {
            return "Medium — declared in code/docs with usage evidence, not observed at runtime";
        }
        return "Low — declared only (configuration or documentation), no usage evidence";
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
        try {
            String raw = state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC_RAW);
            DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(raw, state.namespace);

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
            resolveResidueWithLlm(graph, residue);

            // Merge documentation (DeepWiki) evidence as doc-provenance edges: the
            // dependencies only the docs name (an externalised datasource, a
            // documented association). Never runtime fact — dashed/dotted, and a
            // db a service really uses (persistence code) outranks a doc-only one.
            DocGraphMerger.merge(graph, state.stage(DependencyAnalysisStateStore.STAGE_MERGED_NOTES));

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
            // only ever touches undeployed nodes, never a live service.
            GraphNormalizer.normalize(graph);

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
     */
    private void postRuntimeGraph(DependencyGraph graph, DependencyAnalysisStateStore.State state) {
        try {
            if (graph == null) return;

            if (graph.isEmpty()) {
                // No runtime edges and nothing resolvable from code (or an old
                // checkpoint without the raw/code stages). The prose report already
                // covers this, so stay quiet rather than post an empty graph.
                return;
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

            postCoverage(graph, state.repoName);
        } catch (Exception e) {
            // The graph is a bonus view; never let it break the report delivery.
            System.out.println("[WARNING] could not post the dependency graph: " + e.getMessage());
        }
    }

    /**
     * Posts the deterministic runtime traffic-coverage summary derived from the graph:
     * of the service-to-service sync edges, how many the mesh actually observed, and
     * which ones traffic never reached. The uncovered edges are exactly the dashed
     * business edges — the concrete targets for driving more traffic and Resuming.
     */
    private void postCoverage(DependencyGraph graph, String repoName) {
        CoverageAnalyzer.Report coverage = CoverageAnalyzer.analyze(graph);
        if (!coverage.hasEdges()) return; // nothing measurable (e.g. no service→service edges)

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
        jdaService.sendChatOpsChannelMessage(msg.toString());
    }

    /**
     * Resolves the code edges the deterministic pass could not map, using the LLM
     * purely for name alignment onto the known nodes. Strictly additive and fully
     * guarded: any failure (LLM unreachable, non-JSON output, a hallucinated node)
     * leaves the deterministic graph untouched. An edge is added only when both
     * endpoints validate against the known vocabulary.
     */
    private void resolveResidueWithLlm(DependencyGraph graph, List<CodeGraphMerger.Unresolved> residue) {
        if (residue == null || residue.isEmpty()) return;
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
            if (mapped == null) return;

            int added = 0;
            for (int i = 0; i < mapped.length(); i++) {
                JSONObject row = mapped.optJSONObject(i);
                if (row == null) continue;
                String source = row.optString("source", "");
                String target = row.optString("target", "");
                String type = row.optString("type", "sync-http");
                String confidence = row.optString("confidence", DependencyGraph.CONF_INFERRED);
                if (addLlmEdge(graph, knownNodes, source, target, type, confidence)) added++;
            }
            if (added > 0) System.out.println("[INFO] dependency graph: LLM aligned " + added
                    + " of " + residue.size() + " residual code edge(s).");
        } catch (Exception e) {
            // Necessary-only LLM step: on any problem, keep the deterministic graph.
            System.out.println("[WARNING] residue LLM alignment skipped: " + e.getMessage());
        }
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
