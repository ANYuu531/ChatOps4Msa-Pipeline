package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHost;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyReportService;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CoverageAnalyzer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphNormalizer;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.K8sGraphBuilder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.RuntimeGraphBuilder;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.AskItem;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checkpointing for the dependency analysis.
 *
 * Each collection stage stores its result as it completes, so that pausing to
 * supplement provenance (typically: drive traffic through the mesh) can be resumed
 * from the breakpoint. Resuming re-runs only the stale stage and its downstream
 * steps; DeepWiki and the repository clone are not repeated.
 */
@Component
public class DepstateToolkit extends ToolkitFunction {

    public static final String CONTINUE_BUTTON_ID = "DepContinue";
    public static final String PAUSE_BUTTON_ID = "DepPause";
    public static final String RESUME_BUTTON_ID = "DepResume";
    public static final String APPLY_SERVICE_ENTRIES_BUTTON_ID = "DepApplyServiceEntries";
    /** Opens the form that collects the values the traffic generator asked a human for. */
    public static final String ASK_VALUES_BUTTON_ID = "DepProvideValues";
    public static final String ASK_VALUES_MODAL_ID = "DepProvideValuesModal";
    /** Opens the form that asks which service each unaligned documented/coded name is. */
    public static final String RESOLVE_ALIASES_BUTTON_ID = "DepResolveAliases";
    public static final String RESOLVE_ALIASES_MODAL_ID = "DepResolveAliasesModal";
    /** The form input ids are positional ({@code alias_0} …) because a raw name may not be a valid id. */
    public static final String ALIAS_INPUT_PREFIX = "alias_";
    /** Discord allows at most five inputs in one modal; the rest are asked next round. */
    public static final int MAX_ASKS_PER_MODAL = 5;

    private final DependencyAnalysisStateStore stateStore;
    private final JDAService jdaService;
    private final DependencyReportService reportService;

    @Autowired
    public DepstateToolkit(DependencyAnalysisStateStore stateStore,
                           @Lazy JDAService jdaService,
                           @Lazy DependencyReportService reportService) {
        this.stateStore = stateStore;
        this.jdaService = jdaService;
        this.reportService = reportService;
    }

    /**
     * Posts the "Resolve names" button — the alias counterpart of the Tier 3 ask.
     *
     * The documentation and the residual code edges name services in their own words.
     * The deterministic alignment rules (and, for code, the LLM name-alignment pass)
     * map the regular spellings; what is left is genuinely ambiguous, and both
     * automatic outcomes are wrong — dropping the edge hides a documented dependency,
     * fuzzy-matching it invents one. So the tool lists those names with the closest
     * candidates and asks. Answers are applied when the report is generated and are
     * remembered for the repository, so the next run does not ask again.
     *
     * Nothing is posted when every name aligned: a clean run is unchanged.
     */
    public String toolkitDepstateAliasButton() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the alias button.";
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) return "[ERROR] no checkpoint; the run was never started.";

        AliasResolution.Questions questions = reportService.aliasQuestions(state);
        if (questions.isEmpty()) {
            stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_PENDING_ALIASES, "");
            return "every documented/coded name aligned; nothing to ask.";
        }
        stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_PENDING_ALIASES, questions.toJson());

        List<AliasResolution.Question> list = questions.list();
        StringBuilder message = new StringBuilder();
        message.append("**").append(list.size()).append(" name(s) in the docs/code do not match any "
                        + "service on the graph**\n")
                .append("The tool does not guess which service a name means: each one is left off "
                        + "the graph until you say. For each, answer with the service it refers to "
                        + "(a candidate's number or the service id), `new` if it is a real service the "
                        + "other layers missed, or `ignore` if it is not a service (a library, a "
                        + "grouping, a technology label).\n\n");
        int shown = 0;
        for (AliasResolution.Question q : list) {
            if (shown++ >= MAX_ASKS_PER_MODAL) break;
            message.append("• `").append(q.name).append("` — ").append(q.origin)
                    .append(", seen in ").append(q.seenIn);
            if (q.mentions > 1) message.append(" (+").append(q.mentions - 1).append(" more)");
            if (!q.candidates.isEmpty()) {
                message.append("\n   closest: ");
                for (int i = 0; i < q.candidates.size(); i++) {
                    if (i > 0) message.append(" · ");
                    message.append(i + 1).append(' ').append('`').append(q.candidates.get(i)).append('`');
                }
            }
            message.append('\n');
        }
        if (list.size() > MAX_ASKS_PER_MODAL) {
            message.append("_(").append(list.size() - MAX_ASKS_PER_MODAL)
                    .append(" more will be asked in the next round — Discord allows five per form.)_\n");
        }
        message.append("\nClicking **Resolve names** opens a form. Answer what you know and leave the "
                + "rest blank; **Generate report** then uses your answers, and they are remembered "
                + "for `" + state.repoName + "`.");

        jdaService.sendChatOpsChannelMessageWithButtons(message.toString(),
                List.of(Button.primary(RESOLVE_ALIASES_BUTTON_ID, "Resolve names")));
        return "alias button posted for " + list.size() + " name(s)";
    }

    /**
     * Begins a fresh run, discarding any earlier checkpoint for this user.
     *
     * entry_url and auth_hint are stored so that resuming can re-drive traffic
     * without asking the user for them again.
     *
     * @return the analysis mode: {@code "greenfield"} when no namespace was given
     *         (the project is not deployed, so the cluster/runtime steps are skipped),
     *         otherwise {@code "runtime"}. The low-code flow assigns this to
     *         {@code analysis_mode}, which the orchestrator reads to gate the
     *         cluster-bound steps.
     */
    public String toolkitDepstateStart(String repo_name, String namespace,
                                       String entry_url, String auth_hint) {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot start a checkpoint.";

        stateStore.start(userId, repo_name, namespace);
        stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_ENTRY_URL, entry_url);
        stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_AUTH_HINT, auth_hint);
        return isGreenfield(namespace) ? "greenfield" : "runtime";
    }

    /** No namespace (blank / "none" / "greenfield") means the project is not deployed. */
    static boolean isGreenfield(String namespace) {
        if (namespace == null) return true;
        String ns = namespace.trim();
        return ns.isEmpty() || ns.equalsIgnoreCase("none") || ns.equalsIgnoreCase("greenfield");
    }

    /** Stores one collection stage. */
    public String toolkitDepstatePut(String stage, String value) {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot save stage.";
        stateStore.putStage(userId, stage, value);
        return "saved " + stage;
    }

    /**
     * @return the stored stage, or an empty string when it was never collected.
     *         The low-code flow tests the result to decide whether to skip the
     *         (expensive) stage or recompute it.
     */
    public String toolkitDepstateGet(String stage) {
        String userId = requireUser();
        if (userId == null) return "";
        return stateStore.getStage(userId, stage);
    }

    /**
     * Posts the continue / pause decision. The report itself is produced only when
     * the user clicks "Generate report", from the provenance already stored.
     */
    public String toolkitDepstateCheckpoint() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the checkpoint.";

        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) return "[ERROR] no checkpoint to post; the run was never started.";

        String message = "**Dependency Analysis - collection done, completeness checked**\n"
                + "Repository: `" + state.repoName + "` | Namespace: `" + state.namespace + "`\n\n"
                + "Review the completeness check and gap list above, then choose:\n"
                + "• **Generate report** — produce the final report from the current provenance\n"
                + "• **Pause & supplement** — go drive traffic or add provenance, then resume "
                + "(the analysis continues from here; DeepWiki and code extraction are not re-run)";

        List<Button> buttons = List.of(
                Button.success(CONTINUE_BUTTON_ID, "Generate report"),
                Button.secondary(PAUSE_BUTTON_ID, "Pause & supplement"));

        jdaService.sendChatOpsChannelMessageWithButtons(message, buttons);
        return "checkpoint posted";
    }

    /**
     * Posts the "Apply ServiceEntries" decision button, right after the manifest
     * has been shown. The apply itself is a change to the cluster, so it is never
     * done silently: it happens only when the user clicks (handled in
     * ButtonListener, which runs kubectl apply through the k8s MCP server).
     *
     * When the code extraction found no external hosts there is nothing to apply,
     * so no button is posted.
     */
    public String toolkitDepstateApplyButton() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the apply button.";

        List<ExternalHost> hosts = ExternalHost.fromJson(
                stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_EXTERNAL_HOSTS));
        if (hosts.isEmpty()) {
            return "no external hosts; nothing to apply.";
        }

        String message = "**Apply the ServiceEntry manifests above?**\n"
                + "They declare the " + hosts.size() + " external host(s) to Istio so the mesh can "
                + "observe and attribute those edges — without them the traffic goes through "
                + "PassthroughCluster and the hostname is lost.\n"
                + "Clicking **Apply** runs `kubectl apply` on the cluster through the k8s MCP server. "
                + "Nothing changes until you click.";

        List<Button> buttons = List.of(
                Button.primary(APPLY_SERVICE_ENTRIES_BUTTON_ID, "Apply ServiceEntries"));

        jdaService.sendChatOpsChannelMessageWithButtons(message, buttons);
        return "apply button posted";
    }

    /**
     * Posts the "Provide missing values" button — Tier 3 of the payload strategy.
     *
     * The generator reaches this point only when a value is genuinely irreducible: the
     * project's own example requests (Tier 1) and the 4xx feedback loop (Tier 2) could
     * not produce it. Rather than degrade that edge to UNREACHABLE, the tool asks the
     * operator, who usually knows it in one word (a real account number, a tenant id).
     *
     * Nothing is asked when the generator needed nothing, so the normal run is
     * unchanged — this button only appears when a human is actually the bottleneck.
     */
    public String toolkitDepstateAskButton() {
        String userId = requireUser();
        if (userId == null) return "[ERROR] no user context; cannot post the ask button.";

        List<AskItem> asks = AskItem.fromJson(
                stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_PENDING_ASKS));
        if (asks.isEmpty()) return "no values needed from the user.";

        StringBuilder message = new StringBuilder();
        message.append("**The traffic generator needs ").append(asks.size())
                .append(" value(s) only you can give**\n")
                .append("These could not be derived from the repository's own example requests, "
                        + "and guessing them just 4xx's — which stops the journey before the deep "
                        + "edge is ever crossed. The requests that need them were held back "
                        + "(`[WAIT]` in the traffic report above), not sent half-filled.\n\n");
        int shown = 0;
        for (AskItem ask : asks) {
            if (shown++ >= MAX_ASKS_PER_MODAL) break;
            message.append("• `").append(ask.key).append("` — ").append(ask.question);
            if (!ask.example.isEmpty()) message.append("  _(e.g. ").append(ask.example).append(")_");
            message.append('\n');
        }
        if (asks.size() > MAX_ASKS_PER_MODAL) {
            message.append("_(").append(asks.size() - MAX_ASKS_PER_MODAL)
                    .append(" more will be asked in the next round — Discord allows five per form.)_\n");
        }
        message.append("\nClicking **Provide values** opens a form. Answer what you know and leave "
                + "the rest blank; the analysis then re-drives traffic with them automatically.");

        jdaService.sendChatOpsChannelMessageWithButtons(message.toString(),
                List.of(Button.primary(ASK_VALUES_BUTTON_ID, "Provide values")));
        return "ask button posted for " + asks.size() + " value(s)";
    }

    /**
     * The values the user has already supplied, as variable names + what they were for
     * — <b>never the values themselves</b>.
     *
     * This goes into the next generation prompt so the LLM references {{key}} instead
     * of re-asking or inventing a replacement. The values are withheld on purpose: the
     * model does not need them to write `{{account_num}}`, and keeping a credential out
     * of the prompt entirely is stronger than trusting it not to echo one.
     */
    public String toolkitDepstateSuppliedValues() {
        String userId = requireUser();
        if (userId == null) return "";

        java.util.Map<String, String> values = AskItem.valuesFromJson(
                stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_USER_VALUES));
        if (values.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("The user has supplied these values. They are already bound as collection "
                + "variables: reference them as {{key}} and do not invent a replacement or "
                + "hard-code a literal in their place.\n");
        for (String key : values.keySet()) sb.append("- {{").append(key).append("}}\n");
        // A supplied value can be wrong FOR THIS ROUND's objective — an account number
        // that is the sender's own when the edge needs a transfer to someone else. The
        // loop used to be stuck there: the value was bound, re-asking was forbidden, so
        // the generator declared the edge UNREACHABLE rather than say what it needed.
        sb.append("If the execution report shows a supplied value is itself the problem — the "
                + "request keeps failing because of it, or it is semantically wrong for this "
                + "round's objective — you MAY ask again, under a NEW variable name that says "
                + "what is different (e.g. {{recipient_account}} rather than {{account_num}}), "
                + "and state in the question why the previous value does not work. Do not "
                + "silently reuse it and do not give up on the edge instead.\n");
        return sb.toString();
    }

    /**
     * The data-layer edges the mesh has actually observed, as a short deterministic
     * ledger for the LLM steps (the report and the completeness check).
     *
     * Why this exists: those steps read the HTTP ledger and the egress ledger, and
     * neither can carry a database. Istio emits no {@code istio_requests_total} for a
     * non-HTTP protocol, so without this the report says
     * "userservice -> accounts-db: runtime observed: Unknown" while the graph beside
     * it draws that very edge solid — the two disagree, and the reader is left to
     * guess which is right. Computed here rather than handed over as raw Prometheus
     * JSON so the model is given a conclusion to repeat, not data to re-derive.
     */
    public String toolkitDepstateDataLayer() {
        String userId = requireUser();
        if (userId == null) return "";
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) return "";

        DependencyGraph graph = new DependencyGraph(state.namespace);
        RuntimeGraphBuilder.mergeIstioTcp(graph,
                state.stage(DependencyAnalysisStateStore.STAGE_TCP_RAW));
        if (graph.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("These data-layer (database) edges ARE runtime-observed. Istio emits no "
                + "istio_requests_total for a non-HTTP protocol, so they appear in the TCP "
                + "metric instead of the HTTP ledger above — treat each as CONFIRMED at "
                + "runtime, never as 'unknown':\n");
        for (DependencyGraph.Edge edge : graph.getEdges()) {
            sb.append("- ").append(edge.source).append(" -> ").append(edge.target)
                    .append("  (").append(edge.count)
                    .append(" TCP connections observed; a connection count, NOT a request count)\n");
        }
        return sb.toString();
    }

    /**
     * The deterministic runtime coverage from the current checkpoint: builds the
     * graph from the raw Istio traffic + structured code edges (no LLM), and returns
     * the service→service synchronous edges the mesh has NOT yet observed — the
     * concrete, non-LLM objective the resume loop drives toward ("these edges still
     * have no traffic; make a journey that crosses them").
     *
     * Returns an empty string when there is nothing measurable yet (traffic not
     * driven, or an old checkpoint without the raw stages), so the caller falls back
     * to the LLM completeness check.
     */
    public String toolkitDepstateCoverage() {
        String userId = requireUser();
        if (userId == null) return "";
        DependencyAnalysisStateStore.State state = stateStore.get(userId);
        if (state == null) return "";

        DependencyGraph graph = RuntimeGraphBuilder.fromIstioRequests(
                state.stage(DependencyAnalysisStateStore.STAGE_TRAFFIC_RAW), state.namespace);
        // Same in-mesh TCP observations the report path folds in, so a database edge the
        // mesh HAS seen is not reported back to the resume loop as a missing target.
        RuntimeGraphBuilder.mergeIstioTcp(graph,
                state.stage(DependencyAnalysisStateStore.STAGE_TCP_RAW));
        CodeGraphMerger.merge(graph,
                state.stage(DependencyAnalysisStateStore.STAGE_CODE_EDGES), state.repoName);
        // Mark deployment status so CoverageAnalyzer can drop undeployed/phantom edges
        // (a framework name, a doc alias, an undeployed service) from the resume
        // objective — otherwise the loop chases traffic it can never send. Matches how
        // the report path builds the graph; a no-op when the k8s stage is absent.
        K8sGraphBuilder.enrich(graph, state.stage(DependencyAnalysisStateStore.STAGE_K8S_RAW));
        // Same alias-collapse / phantom-drop the report path applies, so the resume
        // objective is stated against real workloads, not build-time noise. The
        // operator's alias answers apply here too; nothing new is asked from this path.
        GraphNormalizer.normalize(graph, AliasResolution.Answers.fromJson(
                state.stage(DependencyAnalysisStateStore.STAGE_ALIAS_ANSWERS)), null);

        CoverageAnalyzer.Report coverage = CoverageAnalyzer.analyze(graph);
        if (!coverage.hasEdges()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Deterministic runtime coverage: ").append(coverage.observed).append("/")
                .append(coverage.total).append(" service-to-service edges observed (")
                .append(coverage.percent()).append("%).\n");
        if (coverage.uncovered.isEmpty()) {
            sb.append("Every service-to-service edge has already been exercised by traffic.");
        } else {
            sb.append("Edges still WITHOUT runtime traffic — build a journey that crosses each:\n");
            for (String edge : coverage.uncovered) sb.append("- ").append(edge).append('\n');
        }

        // Reported separately, and NOT part of the objective above: a db edge is not
        // crossed by a journey directly — it is crossed when the service that owns it
        // handles a request. Stating it here tells the next round which service's
        // endpoints are worth exercising, without polluting the traffic target list.
        if (coverage.hasDbEdges()) {
            sb.append("\nData layer (measured separately, TCP): ").append(coverage.dbObserved)
                    .append("/").append(coverage.dbTotal).append(" datastore edges observed (")
                    .append(coverage.dbPercent()).append("%).\n");
            for (String edge : coverage.dbUncovered) {
                sb.append("- no connection seen yet: ").append(edge).append('\n');
            }
        }
        // Stated, not hidden: these are excluded from both ratios, and if they dominate
        // the score is resting on very little.
        if (coverage.mentionedOnly > 0) {
            sb.append("\nNot scored: ").append(coverage.mentionedOnly)
                    .append(" edge(s) are mentioned somewhere but have no usage signal "
                            + "(drawn dotted). Do NOT target these with traffic.");
            if (coverage.isLowConfidence()) {
                sb.append(" NOTE: they outnumber the scored edges — the extraction found "
                        + "few confirmed edges (low confidence), so treat the percentage with caution.");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String requireUser() {
        String userId = UserContextHolder.getUserId();
        return (userId == null || userId.isBlank()) ? null : userId;
    }
}
