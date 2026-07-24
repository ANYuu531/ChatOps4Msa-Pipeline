package ntou.soselab.chatops4msa.Service.DependencyAnalysis;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DiscordToolkit;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.LlmToolkit;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DocGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DotEmitter;
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
    public void generateAndPost(String userId) {
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) {
            jdaService.sendChatOpsChannelWarningMessage(
                    "[WARNING] No dependency-analysis checkpoint found (it may have expired). "
                            + "Please re-run get-dependency-analysis.");
            return;
        }

        String prompt = "## Documentation + code dependency notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_MERGED_NOTES) + "\n\n"
                + "## Kubernetes / Istio runtime notes\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_K8S) + "\n\n"
                + "## Istio runtime-observed edge ledger (internal, mesh-to-mesh traffic)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC) + "\n\n"
                + "## Istio egress edge ledger (external dependencies leaving the mesh)\n"
                + state.stage(DependencyAnalysisStateStore.STAGE_EGRESS);

        String response = llmToolkit.toolkitLlmCall(prompt, "dependency_analysis");

        String report = "## Microservice Dependency Analysis Report\n"
                + "**Repository:** `" + state.repoName + "` | **Namespace:** `" + state.namespace + "`\n\n"
                + response;
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
        postRuntimeGraph(state);

        stateStore.remove(userId);
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
    private void postRuntimeGraph(DependencyAnalysisStateStore.State state) {
        try {
            String raw = state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC_RAW);
            DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(raw, state.namespace);

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
                        "## Dependency Graph — `" + state.repoName + "`\n"
                                + "Solid arrows are edges Istio observed at runtime; dashed arrows are "
                                + "declared in code/doc but not observed. Greyed dashed nodes are referenced "
                                + "but not deployed in the cluster; live nodes show image · replicas · created "
                                + "date. The `.mmd` source is attached too (edit at https://mermaid.live).");
                jdaService.sendChatOpsChannelFile(base + ".png", new ByteArrayInputStream(png));
                jdaService.sendChatOpsChannelFile(base + ".mmd",
                        new ByteArrayInputStream(mermaid.getBytes(StandardCharsets.UTF_8)));
            } else {
                jdaService.sendChatOpsChannelMessage(
                        "## Dependency Graph — `" + state.repoName + "`\n"
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
        msg.append("## Runtime Traffic Coverage — `").append(repoName).append("`\n")
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
