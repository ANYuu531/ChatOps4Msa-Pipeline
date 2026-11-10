package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.AliasResolution;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphEdit;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conversation that turns "add an edge from X to Y" into a {@link GraphEdit}: it asks
 * for what the request left open, one question per turn, and looks for the evidence
 * itself before asking the operator for any.
 *
 * <p>Order of the questions, and why:
 * <ol>
 *   <li><b>Which node.</b> A name not on the graph gets the closest ids as numbered
 *       choices, or "new" — the same answer forms as the Resolve names form.</li>
 *   <li><b>What kind</b>, only for a node the operator introduces.</li>
 *   <li><b>Evidence.</b> The repository is searched for the pair first
 *       ({@link EvidenceFinder}); the operator picks the lines that back the edge, points
 *       at one of their own (it is read and checked), or says there is none. This is
 *       where an edge the static layer missed can turn out to be written down after all.</li>
 *   <li><b>Edge type</b>, with the type the chosen evidence suggests offered first.</li>
 *   <li><b>Confirmation</b> of the whole edit, direction included.</li>
 * </ol>
 * Every answer is parsed by code; nothing a model writes decides a slot. An answer that
 * cannot be read leaves the question open ({@link Turn#understood} false), so the caller
 * can treat the message as an ordinary question instead.
 */
public final class GraphEditDialogue {

    /** What the dialogue needs from the world: the graph, the repository, who is speaking. */
    public interface Env {
        DependencyGraph graph();

        /** Traces of source → target (source null: of the node alone) in the repository; empty when it cannot be read. */
        List<EvidenceFinder.Evidence> search(String source, String target);

        /** One line of a repository file, or null when the file or line does not exist or the repository cannot be read. */
        String readLine(String file, int line);

        String user();

        String now();
    }

    /** One turn's outcome. */
    public static final class Turn {
        public final String reply;
        /** The dialogue state to keep, or null when the dialogue ended (applied or cancelled). */
        public final Pending next;
        /** The confirmed edit, when this turn confirmed one. */
        public final GraphEdit applied;
        /** False when the message was not an answer to the open question. */
        public final boolean understood;

        Turn(String reply, Pending next, GraphEdit applied, boolean understood) {
            this.reply = reply;
            this.next = next;
            this.applied = applied;
            this.understood = understood;
        }
    }

    // ---------- the state between turns ----------

    public static final class Pending {
        String action = GraphEdit.ADD_EDGE;
        String lang = "en";
        String step = "";
        String sourceRaw = "";
        String targetRaw = "";
        String source = "";
        String target = "";
        boolean sourceNew;
        boolean targetNew;
        String sourceKind = "";
        String targetKind = "";
        final List<String> candidates = new ArrayList<>();
        boolean searched;
        /** The repository could not be read, so "nothing found" would be untrue. */
        boolean searchFailed;
        final List<EvidenceFinder.Evidence> evidence = new ArrayList<>();
        final List<String> refs = new ArrayList<>();
        String confidence = "";
        String suggestedType = "";
        String type = "";
        /** Whether the evidence answer has been given (an empty {@link #refs} is also an answer). */
        boolean evidenceAnswered;

        public String summary() {
            if (GraphEdit.ADD_NODE.equals(action)) return "add node " + label(targetRaw, target);
            return (GraphEdit.REMOVE_EDGE.equals(action) ? "remove edge " : "add edge ")
                    + label(sourceRaw, source) + " -> " + label(targetRaw, target);
        }

        private static String label(String raw, String id) {
            return id.isBlank() ? (raw.isBlank() ? "?" : raw) : id;
        }

        public JSONObject toJson() {
            return new JSONObject().put("action", action).put("lang", lang).put("step", step)
                    .put("sourceRaw", sourceRaw).put("targetRaw", targetRaw).put("source", source).put("target", target)
                    .put("sourceNew", sourceNew).put("targetNew", targetNew).put("sourceKind", sourceKind).put("targetKind", targetKind)
                    .put("candidates", new JSONArray(candidates)).put("searched", searched).put("searchFailed", searchFailed)
                    .put("evidence", EvidenceFinder.listToJson(evidence)).put("refs", new JSONArray(refs))
                    .put("confidence", confidence).put("suggestedType", suggestedType).put("type", type)
                    .put("evidenceAnswered", evidenceAnswered);
        }

        public static Pending fromJson(JSONObject j) {
            if (j == null) return null;
            Pending p = new Pending();
            p.action = j.optString("action", GraphEdit.ADD_EDGE);
            p.lang = j.optString("lang", "en");
            p.step = j.optString("step", "");
            p.sourceRaw = j.optString("sourceRaw", "");
            p.targetRaw = j.optString("targetRaw", "");
            p.source = j.optString("source", "");
            p.target = j.optString("target", "");
            p.sourceNew = j.optBoolean("sourceNew", false);
            p.targetNew = j.optBoolean("targetNew", false);
            p.sourceKind = j.optString("sourceKind", "");
            p.targetKind = j.optString("targetKind", "");
            JSONArray c = j.optJSONArray("candidates");
            if (c != null) for (int i = 0; i < c.length(); i++) p.candidates.add(c.optString(i));
            p.searched = j.optBoolean("searched", false);
            p.searchFailed = j.optBoolean("searchFailed", false);
            p.evidence.addAll(EvidenceFinder.listFromJson(j.optJSONArray("evidence")));
            JSONArray r = j.optJSONArray("refs");
            if (r != null) for (int i = 0; i < r.length(); i++) p.refs.add(r.optString(i));
            p.confidence = j.optString("confidence", "");
            p.suggestedType = j.optString("suggestedType", "");
            p.type = j.optString("type", "");
            p.evidenceAnswered = j.optBoolean("evidenceAnswered", false);
            return p;
        }
    }

    private static final String RESOLVE_SOURCE = "resolve-source";
    private static final String RESOLVE_TARGET = "resolve-target";
    private static final String KIND_SOURCE = "kind-source";
    private static final String KIND_TARGET = "kind-target";
    private static final String EVIDENCE = "evidence";
    private static final String TYPE = "type";
    private static final String CONFIRM = "confirm";
    private static final String UPGRADE = "upgrade";
    /** A removal whose edge is not on the graph, but its reverse is: only "reverse" or "cancel" go on. */
    private static final String MISSING = "missing";

    public static final List<String> TYPES = List.of("sync-http", "grpc", "async", "db", "external", DependencyGraph.TYPE_STARTUP);
    public static final List<String> KINDS = List.of(DependencyGraph.KIND_SERVICE, DependencyGraph.KIND_DB,
            DependencyGraph.KIND_QUEUE, DependencyGraph.KIND_EXTERNAL, DependencyGraph.KIND_GATEWAY);

    private GraphEditDialogue() {
    }

    // ---------- starting ----------

    /**
     * Opens a dialogue for a request the planner recognised.
     *
     * @param action {@link GraphEdit#ADD_EDGE} or {@link GraphEdit#REMOVE_EDGE} with
     *               [source, target] (source may be blank), or {@link GraphEdit#ADD_NODE} with [name]
     * @param question the operator's message, for the reply language
     */
    public static Turn start(String action, List<String> args, String question, Env env) {
        Pending p = new Pending();
        p.action = GraphEdit.ADD_NODE.equals(action) ? GraphEdit.ADD_NODE
                : GraphEdit.REMOVE_EDGE.equals(action) ? GraphEdit.REMOVE_EDGE : GraphEdit.ADD_EDGE;
        p.lang = hasCjk(question) ? "zh" : "en";
        if (GraphEdit.ADD_NODE.equals(p.action)) {
            p.targetRaw = args.isEmpty() ? "" : args.get(0);
        } else if (args.size() >= 2) {
            p.sourceRaw = args.get(0);
            p.targetRaw = args.get(1);
        } else if (args.size() == 1) {
            p.targetRaw = args.get(0);
        }
        DependencyGraph graph = env.graph();
        if (!p.sourceRaw.isBlank()) p.source = resolve(p.sourceRaw, graph);
        if (!p.targetRaw.isBlank()) p.target = resolve(p.targetRaw, graph);

        StringBuilder head = new StringBuilder();
        if (GraphEdit.ADD_NODE.equals(p.action)) {
            if (!p.target.isBlank()) {
                return new Turn(t(p, "`" + p.target + "` 已經在圖上了，不用新增。要加它的線的話，可以說「新增 A → " + p.target + "」。",
                        "`" + p.target + "` is already on the graph. To add an edge to it, say \"add an edge from A to " + p.target + "\"."),
                        null, null, true);
            }
            head.append(t(p, "好，要在圖上新增節點 `" + name(p.targetRaw) + "`。", "OK — adding the node `" + name(p.targetRaw) + "` to the graph."));
        } else if (GraphEdit.REMOVE_EDGE.equals(p.action)) {
            head.append(t(p, "好，要把 " + endpoint(p, true) + " → " + endpoint(p, false) + " 這條線從圖上拿掉（方向反了請回「反過來」）。",
                    "OK — removing the edge " + endpoint(p, true) + " → " + endpoint(p, false) + " from the graph (reply \"reverse\" if that is backwards)."));
        } else {
            DependencyGraph.Edge existing = existingEdge(graph, p.source, p.target);
            if (existing != null) {
                String refs = existing.provenanceRefs.isEmpty() ? t(p, "（沒有出處）", "(no reference)") : "`" + existing.provenanceRefs.get(0) + "`";
                if (!DependencyGraph.CONF_INFERRED.equals(existing.confidence) || existing.runtimeObserved) {
                    return new Turn(t(p, "圖上已經有 `" + p.source + "` → `" + p.target + "` 了（" + existing.type + "，信心 " + existing.confidence + "，出處 " + refs + "）。",
                            "The graph already has `" + p.source + "` → `" + p.target + "` (" + existing.type + ", confidence " + existing.confidence + ", from " + refs + ")."),
                            null, null, true);
                }
                p.step = UPGRADE;
                return new Turn(t(p, "圖上已經有 `" + p.source + "` → `" + p.target + "`，但它是最低信心（點線：只有宣告、沒有使用的證據；出處 " + refs + "）。要我回 repo 找證據補上嗎？回「好」或「取消」。",
                        "The graph already has `" + p.source + "` → `" + p.target + "`, but at the lowest confidence (dotted: declared, no sign of use; from " + refs + "). Shall I search the repository for evidence? Reply \"yes\" or \"cancel\"."),
                        p, null, true);
            }
            head.append(t(p, "好，要在圖上新增一條線：" + endpoint(p, true) + " → " + endpoint(p, false) + "（前者呼叫或連到後者；方向反了請回「反過來」）。",
                    "OK — adding an edge " + endpoint(p, true) + " → " + endpoint(p, false) + " (the first calls or connects to the second; reply \"reverse\" if that is backwards)."));
        }
        return next(p, env, head.toString());
    }

    // ---------- one answer ----------

    public static Turn answer(Pending p, String text, Env env) {
        String a = text == null ? "" : text.trim();
        String lower = a.toLowerCase(Locale.ROOT);
        if (CANCEL.contains(lower) || lower.startsWith("取消") || lower.startsWith("cancel")) {
            return new Turn(t(p, "好，取消了，圖沒有改。", "Cancelled; the graph is unchanged."), null, null, true);
        }
        if (isEdge(p) && REVERSE.contains(lower) && !p.source.isBlank() && !p.target.isBlank()) {
            swap(p);
            return next(p, env, t(p, "好，方向改成 `" + p.source + "` → `" + p.target + "`。", "Reversed: `" + p.source + "` → `" + p.target + "`."));
        }
        switch (p.step) {
            case UPGRADE:
                if (NO.contains(lower)) return new Turn(t(p, "好，維持原樣。", "OK, left as it is."), null, null, true);
                if (!YES.contains(lower)) return notUnderstood(p, env);
                return next(p, env, "");
            case MISSING:
                return notUnderstood(p, env);
            case RESOLVE_SOURCE:
            case RESOLVE_TARGET: {
                boolean isSource = RESOLVE_SOURCE.equals(p.step);
                String raw = isSource ? p.sourceRaw : p.targetRaw;
                String decided;
                if (raw.isBlank()) {
                    // An open question ("which service connects to postgres?"): the answer is the name.
                    if (a.isBlank() || a.length() > 80) return notUnderstood(p, env);
                    if (isSource) p.sourceRaw = a;
                    else p.targetRaw = a;
                    decided = resolve(a, env.graph());
                    if (decided.isBlank()) return next(p, env, "");
                } else {
                    decided = AliasResolution.parseAnswer(a, question(raw, p.candidates), nodeIds(env.graph()));
                    if (decided == null) return notUnderstood(p, env);
                }
                if (AliasResolution.IGNORE.equals(decided)) return notUnderstood(p, env);
                boolean isNew = AliasResolution.NEW.equals(decided);
                if (isNew && GraphEdit.REMOVE_EDGE.equals(p.action)) return notOnGraph(p, isSource ? p.sourceRaw : p.targetRaw);
                String id = isNew ? AliasResolution.nodeId(isSource ? p.sourceRaw : p.targetRaw) : decided;
                if (id.isBlank()) return notUnderstood(p, env);
                if (isSource) {
                    p.source = id;
                    p.sourceNew = isNew;
                } else {
                    p.target = id;
                    p.targetNew = isNew;
                }
                p.candidates.clear();
                return next(p, env, "");
            }
            case KIND_SOURCE:
            case KIND_TARGET: {
                boolean isSource = KIND_SOURCE.equals(p.step);
                String suggested = DependencyGraph.classifyKind(isSource ? p.source : p.target);
                String kind = YES.contains(lower) ? suggested : parseKind(lower);
                if (kind == null) return notUnderstood(p, env);
                if (isSource) p.sourceKind = kind;
                else p.targetKind = kind;
                return next(p, env, "");
            }
            case EVIDENCE:
                return answerEvidence(p, a, env);
            case TYPE: {
                String type = YES.contains(lower) && !p.suggestedType.isBlank() ? p.suggestedType : parseType(lower);
                if (type == null) return notUnderstood(p, env);
                p.type = type;
                return next(p, env, "");
            }
            case CONFIRM: {
                if (NO.contains(lower)) return new Turn(t(p, "好，取消了，圖沒有改。", "Cancelled; the graph is unchanged."), null, null, true);
                if (!YES.contains(lower)) return notUnderstood(p, env);
                GraphEdit edit = toEdit(p, env);
                return new Turn(appliedMessage(p, edit), null, edit, true);
            }
            default:
                return next(p, env, "");
        }
    }

    private static Turn answerEvidence(Pending p, String a, Env env) {
        String lower = a.toLowerCase(Locale.ROOT);
        if (NONE.contains(lower)) {
            p.refs.clear();
            p.confidence = DependencyGraph.CONF_INFERRED;
            p.evidenceAnswered = true;
            return next(p, env, t(p, "好，沒有出處，這條線會以你的判斷新增、標成最低信心（點線）。",
                    "OK, no reference: the edge is added on your word, at the lowest confidence (dotted)."));
        }
        // "1", "1,3", "1 和 2", "all"
        List<Integer> picked = new ArrayList<>();
        if (ALL.contains(lower)) {
            for (int i = 1; i <= p.evidence.size(); i++) picked.add(i);
        } else if (lower.matches("[\\d\\s,，、和and&]+") && !p.evidence.isEmpty()) {
            for (String n : lower.split("[^\\d]+")) {
                if (n.isEmpty()) continue;
                int i = Integer.parseInt(n);
                if (i < 1 || i > p.evidence.size()) return notUnderstood(p, env);
                if (!picked.contains(i)) picked.add(i);
            }
        }
        if (!picked.isEmpty()) {
            p.refs.clear();
            for (int i : picked) p.refs.add(p.evidence.get(i - 1).ref());
            p.confidence = DependencyGraph.CONF_DOCUMENTED;
            p.suggestedType = p.evidence.get(picked.get(0) - 1).suggestedType;
            p.evidenceAnswered = true;
            return next(p, env, "");
        }
        // "path/to/file.yml:12": read it, and say what it says.
        Matcher ref = FILE_LINE.matcher(a);
        if (ref.find()) {
            String file = ref.group(1).replaceFirst("^\\./", "");
            int line = Integer.parseInt(ref.group(2));
            String text = env.readLine(file, line);
            if (text == null) {
                return new Turn(t(p, "我在 repo 裡找不到 `" + file + ":" + line + "`（檔案或行號不存在）。請再確認一次，或回「沒有」。",
                        "I cannot find `" + file + ":" + line + "` in the repository (no such file or line). Please check it, or reply \"none\"."),
                        p, null, true);
            }
            p.refs.clear();
            p.refs.add(file + ":" + line);
            p.evidenceAnswered = true;
            if (EvidenceFinder.namesTarget(file, text, p.target)) {
                p.confidence = DependencyGraph.CONF_DOCUMENTED;
                p.suggestedType = EvidenceFinder.suggestType(env.graph(), p.target, text, null);
                return next(p, env, t(p, "我看了 `" + file + ":" + line + "`：`" + clip(text) + "`，有寫到 `" + p.target + "`，算 repo 出處。",
                        "I read `" + file + ":" + line + "`: `" + clip(text) + "` — it names `" + p.target + "`, so it counts as a repository reference."));
            }
            p.confidence = DependencyGraph.CONF_INFERRED;
            return next(p, env, t(p, "我看了 `" + file + ":" + line + "`：`" + clip(text) + "`，這行沒有寫到 `" + p.target + "`。先記成你的說明，信心標最低。",
                    "I read `" + file + ":" + line + "`: `" + clip(text) + "` — it does not name `" + p.target + "`. Recorded as your note, at the lowest confidence."));
        }
        // A link, or a sentence: the operator's reference, kept verbatim, not checked. A
        // question is not one ("postgres 被誰用？" asked mid-edit): it is left to the thread
        // to answer, with the edit kept open.
        if (a.length() >= 4 && a.length() <= 300 && !a.endsWith("?") && !a.endsWith("？")) {
            p.refs.clear();
            p.refs.add("operator: " + a);
            p.confidence = DependencyGraph.CONF_INFERRED;
            p.evidenceAnswered = true;
            return next(p, env, t(p, "記下你的說明。工具沒辦法查證 repo 以外的出處，所以信心標最低（點線）。",
                    "Noted. A reference outside the repository cannot be checked, so the confidence is the lowest (dotted)."));
        }
        return notUnderstood(p, env);
    }

    // ---------- the next open question ----------

    private static Turn next(Pending p, Env env, String preface) {
        DependencyGraph graph = env.graph();
        StringBuilder sb = new StringBuilder(preface == null ? "" : preface);
        boolean remove = GraphEdit.REMOVE_EDGE.equals(p.action);
        boolean edge = isEdge(p);

        // A removal names nodes that must be on the graph: a name nothing resembles ends it.
        if (remove && p.source.isBlank() && !p.sourceRaw.isBlank()
                && AliasResolution.rankCandidates(p.sourceRaw, nodeIds(graph), AliasResolution.MAX_CANDIDATES).isEmpty()) return notOnGraph(p, p.sourceRaw);
        if (remove && p.target.isBlank() && !p.targetRaw.isBlank()
                && AliasResolution.rankCandidates(p.targetRaw, nodeIds(graph), AliasResolution.MAX_CANDIDATES).isEmpty()) return notOnGraph(p, p.targetRaw);

        if (edge && p.source.isBlank()) return ask(p, sb, resolveQuestion(p, true, graph), RESOLVE_SOURCE);
        if (p.target.isBlank()) {
            if (!edge) {
                // A node to add: its name is the operator's; only a name the graph already
                // knows under another spelling is worth a question.
                List<String> close = AliasResolution.rankCandidates(p.targetRaw, nodeIds(graph), AliasResolution.MAX_CANDIDATES);
                if (p.candidates.isEmpty() && !close.isEmpty() && !RESOLVE_TARGET.equals(p.step)) {
                    return ask(p, sb, resolveQuestion(p, false, graph), RESOLVE_TARGET);
                }
                p.target = AliasResolution.nodeId(p.targetRaw);
                p.targetNew = true;
            } else {
                return ask(p, sb, resolveQuestion(p, false, graph), RESOLVE_TARGET);
            }
        }
        if (!edge && graph.findNode(p.target) != null) {
            return new Turn(sb + (sb.length() > 0 ? "\n" : "") + t(p, "`" + p.target + "` 已經在圖上了，不用新增。", "`" + p.target + "` is already on the graph."), null, null, true);
        }
        if (edge && p.source.equals(p.target)) {
            return new Turn(sb + (sb.length() > 0 ? "\n" : "") + t(p, "兩端是同一個節點，不畫自己連自己的線。已取消。", "Both ends are the same node; a self-edge is not drawn. Cancelled."), null, null, true);
        }
        if (remove) {
            // Nothing to ask but the confirmation: the edge's own record is shown there.
            if (existingEdge(graph, p.source, p.target) == null) {
                if (existingEdge(graph, p.target, p.source) == null) {
                    return new Turn(sb + (sb.length() > 0 ? "\n" : "") + t(p, "圖上沒有 `" + p.source + "` → `" + p.target + "` 這條線，不用拿掉。",
                            "The graph has no edge `" + p.source + "` → `" + p.target + "`; there is nothing to remove."), null, null, true);
                }
                return ask(p, sb, missingQuestion(p), MISSING);
            }
            return ask(p, sb, confirmQuestion(p, env), CONFIRM);
        }
        if (edge && p.sourceNew && p.sourceKind.isBlank()) return ask(p, sb, kindQuestion(p, p.source), KIND_SOURCE);
        if (p.targetNew && p.targetKind.isBlank()) return ask(p, sb, kindQuestion(p, p.target), KIND_TARGET);

        if (!p.searched) {
            p.evidence.clear();
            List<EvidenceFinder.Evidence> found = env.search(edge ? p.source : null, p.target);
            if (found != null) p.evidence.addAll(found);
            p.searchFailed = found == null;
            p.searched = true;
        }
        if (!p.evidenceAnswered) return ask(p, sb, evidenceQuestion(p), EVIDENCE);

        if (!edge) {
            if (p.type.isBlank()) p.type = "-";
            return ask(p, sb, confirmQuestion(p, env), CONFIRM);
        }
        if (p.type.isBlank()) {
            if (p.suggestedType.isBlank()) p.suggestedType = EvidenceFinder.suggestType(graph, p.target, null, null);
            return ask(p, sb, typeQuestion(p), TYPE);
        }
        return ask(p, sb, confirmQuestion(p, env), CONFIRM);
    }

    private static Turn ask(Pending p, StringBuilder preface, String question, String step) {
        p.step = step;
        String reply = preface.length() == 0 ? question : preface + "\n" + question;
        return new Turn(reply, p, null, true);
    }

    private static Turn notUnderstood(Pending p, Env env) {
        String again = switch (p.step) {
            case RESOLVE_SOURCE -> resolveQuestion(p, true, env.graph());
            case RESOLVE_TARGET -> resolveQuestion(p, false, env.graph());
            case KIND_SOURCE -> kindQuestion(p, p.source);
            case KIND_TARGET -> kindQuestion(p, p.target);
            case EVIDENCE -> evidenceQuestion(p);
            case TYPE -> typeQuestion(p);
            case CONFIRM -> confirmQuestion(p, env);
            case UPGRADE -> t(p, "要我回 repo 找證據嗎？回「好」或「取消」。", "Shall I search the repository? Reply \"yes\" or \"cancel\".");
            case MISSING -> missingQuestion(p);
            default -> "";
        };
        String open = GraphEdit.REMOVE_EDGE.equals(p.action) ? t(p, "刪除", "removal") : t(p, "新增", "edit");
        return new Turn(t(p, "我沒看懂這個回答（" + open + "還在進行中，回「取消」可以放棄）。\n", "I could not read that as an answer (the " + open + " is still open; reply \"cancel\" to drop it).\n") + again,
                p, null, false);
    }

    private static Turn notOnGraph(Pending p, String raw) {
        return new Turn(t(p, "`" + name(raw) + "` 不在圖上，就沒有它的線可以拿掉。已取消。",
                "`" + name(raw) + "` is not on the graph, so it has no edge to remove. Cancelled."), null, null, true);
    }

    private static String missingQuestion(Pending p) {
        return t(p, "圖上沒有 `" + p.source + "` → `" + p.target + "`，但有反方向的 `" + p.target + "` → `" + p.source + "`。要拿掉那條的話回「反過來」，否則回「取消」。",
                "The graph has no `" + p.source + "` → `" + p.target + "`, but it has the reverse, `" + p.target + "` → `" + p.source + "`. Reply \"reverse\" to remove that one, or \"cancel\".");
    }

    // ---------- the questions ----------

    private static String resolveQuestion(Pending p, boolean isSource, DependencyGraph graph) {
        String raw = isSource ? p.sourceRaw : p.targetRaw;
        if (raw.isBlank()) {
            String other = isSource ? p.target : p.source;
            return isSource
                    ? t(p, "哪個服務呼叫或連到 `" + other + "`？回它的名字。", "Which service calls or connects to `" + other + "`? Reply with its name.")
                    : t(p, "`" + other + "` 呼叫或連到誰？回它的名字。", "What does `" + other + "` call or connect to? Reply with its name.");
        }
        p.candidates.clear();
        p.candidates.addAll(AliasResolution.rankCandidates(raw, nodeIds(graph), AliasResolution.MAX_CANDIDATES));
        StringBuilder sb = new StringBuilder();
        if (GraphEdit.ADD_NODE.equals(p.action)) {
            sb.append(t(p, "圖上有名字很像的節點，`" + name(raw) + "` 是其中一個嗎？",
                    "The graph has nodes with similar names. Is `" + name(raw) + "` one of them?"));
        } else {
            sb.append(t(p, "`" + name(raw) + "` 不在圖上。你指的是：", "`" + name(raw) + "` is not on the graph. Did you mean:"));
        }
        for (int i = 0; i < p.candidates.size(); i++) sb.append("\n").append(i + 1).append(") `").append(p.candidates.get(i)).append('`');
        if (GraphEdit.REMOVE_EDGE.equals(p.action)) {
            sb.append("\n").append(t(p, "回編號或節點名稱；或回「取消」。", "Reply with a number or a node id, or \"cancel\"."));
        } else {
            sb.append("\n").append(t(p, "回編號或節點名稱；或回「新增」把 `" + AliasResolution.nodeId(raw) + "` 當成新的節點。",
                    "Reply with a number or a node id, or \"new\" to add `" + AliasResolution.nodeId(raw) + "` as a node of its own."));
        }
        return sb.toString();
    }

    private static String kindQuestion(Pending p, String id) {
        String suggested = DependencyGraph.classifyKind(id);
        StringBuilder sb = new StringBuilder(t(p, "新節點 `" + id + "` 是哪一種？", "What is the new node `" + id + "`?"));
        for (int i = 0; i < KINDS.size(); i++) sb.append("\n").append(i + 1).append(") ").append(kindLabel(p, KINDS.get(i)));
        sb.append("\n").append(t(p, "依名字猜是「" + kindLabel(p, suggested) + "」，回「好」採用，或回編號。",
                "From its name: " + kindLabel(p, suggested) + ". Reply \"ok\" to take that, or a number."));
        return sb.toString();
    }

    private static String evidenceQuestion(Pending p) {
        boolean edge = GraphEdit.ADD_EDGE.equals(p.action);
        String what = edge ? "`" + p.source + "` → `" + p.target + "`" : "`" + p.target + "`";
        StringBuilder sb = new StringBuilder();
        if (p.searchFailed) {
            sb.append(t(p, "這次讀不到 repo（clone 失敗），所以沒辦法替 " + what + " 找證據。",
                    "The repository could not be read this time (the clone failed), so I could not search for " + what + "."));
        } else if (p.evidence.isEmpty()) {
            sb.append(t(p, "我依這個要求回 repo 找了一次（位址、主機名稱的鍵、名字），沒有找到 " + what + " 的痕跡。",
                    "I searched the repository for " + what + " (addresses, host-named keys, the name) and found no trace."));
        }
        if (p.evidence.isEmpty()) {
            sb.append("\n").append(t(p, "有出處的話請貼上：`檔案:行號`、文件網址，或一句說明；沒有就回「沒有」，會以你的判斷新增、標成最低信心。",
                    "If you have a reference, paste it: `file:line`, a document link, or a sentence; otherwise reply \"none\" and it is added on your word, at the lowest confidence."));
            return sb.toString();
        }
        sb.append(t(p, "我依這個要求回 repo 找了一次，找到 " + p.evidence.size() + " 處可能的證據（分析時沒有畫成這條線）：",
                "I searched the repository for " + what + " and found " + p.evidence.size() + " possible trace(s) the analysis did not draw:"));
        for (int i = 0; i < p.evidence.size(); i++) {
            EvidenceFinder.Evidence e = p.evidence.get(i);
            sb.append("\n").append(i + 1).append(") `").append(e.ref()).append("` ").append(clip(e.text))
                    .append("\n   ").append(describe(p, e));
        }
        sb.append("\n").append(t(p, "回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。",
                "Reply with the number(s) to use (e.g. 1,2); or paste your own reference (`file:line`, a link or a sentence); or \"none\"."));
        return sb.toString();
    }

    /** Why this line is offered, and why the analysis did not already draw it. */
    private static String describe(Pending p, EvidenceFinder.Evidence e) {
        List<String> parts = new ArrayList<>();
        switch (e.shape) {
            case RESIDUE -> parts.add(t(p, "分析時抽到了這一列，但名字對不上圖上的節點", "extracted during the analysis, but its name matched no node"));
            case ADDRESS -> parts.add(t(p, "位址裡寫著 `" + p.target + "`", "an address naming `" + p.target + "`"));
            case ADDRESS_KEY -> parts.add(t(p, "主機／位址的鍵，值是 `" + p.target + "`", "a host/address key whose value is `" + p.target + "`"));
            case NAME -> parts.add(t(p, "提到名字 `" + p.target + "`", "mentions the name `" + p.target + "`"));
        }
        if (e.owner != null) {
            boolean bySource = !p.source.isBlank() && EvidenceFinder.nameKeys(e.owner).stream().anyMatch(EvidenceFinder.nameKeys(p.source)::contains);
            parts.add(bySource ? t(p, "屬於 `" + e.owner + "`", "belongs to `" + e.owner + "`")
                    : t(p, "工具把這個檔歸給 `" + e.owner + "`", "the tool attributes this file to `" + e.owner + "`"));
        } else if (!p.source.isBlank() && EvidenceFinder.pathRelation(e.file, p.source) >= 0.5) {
            parts.add(t(p, "路徑對得上 `" + p.source + "`、但工具沒有把這個檔歸給任何服務", "the path spells `" + p.source + "`, but the tool attributes the file to no service"));
        }
        if (e.shape != EvidenceFinder.Shape.RESIDUE) {
            parts.add(e.toolRead ? t(p, "靜態層有讀這個檔、但沒畫這條線", "the static layer read this file but drew no such edge")
                    : t(p, "靜態層沒有讀這個檔（格式不在讀取範圍）", "the static layer does not read this file (format not covered)"));
        }
        return String.join(" · ", parts);
    }

    private static String typeQuestion(Pending p) {
        StringBuilder sb = new StringBuilder(t(p, "這條線是哪一種？", "What kind of edge is it?"));
        for (int i = 0; i < TYPES.size(); i++) sb.append("\n").append(i + 1).append(") ").append(typeLabel(p, TYPES.get(i)));
        sb.append("\n").append(t(p, "依" + (p.refs.isEmpty() || p.confidence.equals(DependencyGraph.CONF_INFERRED) ? "目標節點" : "你選的證據")
                        + "建議「" + p.suggestedType + "」，回「好」採用，或回編號。",
                "Suggested from the " + (p.refs.isEmpty() || p.confidence.equals(DependencyGraph.CONF_INFERRED) ? "target node" : "evidence you chose")
                        + ": " + p.suggestedType + ". Reply \"ok\" to take it, or a number."));
        return sb.toString();
    }

    private static String confirmQuestion(Pending p, Env env) {
        if (GraphEdit.REMOVE_EDGE.equals(p.action)) return removeConfirmQuestion(p, env);
        GraphEdit edit = toEdit(p, env);
        StringBuilder sb = new StringBuilder(t(p, "確認一下要寫進圖的內容：", "Please confirm the change:"));
        if (GraphEdit.ADD_NODE.equals(p.action)) {
            sb.append("\n• ").append(t(p, "新節點 `", "new node `")).append(edit.target).append("`（").append(kindLabel(p, edit.kind)).append("）");
        } else {
            sb.append("\n• `").append(edit.source).append("` → `").append(edit.target).append("` · ").append(edit.type)
                    .append(" · ").append(t(p, "信心 ", "confidence ")).append(edit.confidence)
                    .append(DependencyGraph.CONF_DOCUMENTED.equals(edit.confidence) ? t(p, "（有 repo 出處）", " (repository reference)")
                            : t(p, "（只有你的判斷，畫成點線）", " (your word only; drawn dotted)"));
            if (p.sourceNew) sb.append("\n• ").append(t(p, "新節點 `", "new node `")).append(edit.source).append("`（").append(kindLabel(p, edit.sourceKind)).append("）");
            if (p.targetNew) sb.append("\n• ").append(t(p, "新節點 `", "new node `")).append(edit.target).append("`（").append(kindLabel(p, edit.targetKind)).append("）");
        }
        sb.append("\n• ").append(t(p, "出處：", "references: ")).append(edit.refs.isEmpty() ? t(p, "（無）", "(none)") : String.join(", ", edit.refs));
        sb.append("\n• ").append(t(p, "標成人工新增（provenance: human，", "marked as a human edit (provenance: human, by "))
                .append(edit.by).append(t(p, "）", ")"));
        sb.append("\n").append(t(p, "回「確定」寫進圖，回「取消」放棄" + (GraphEdit.ADD_EDGE.equals(p.action) ? "，回「反過來」改方向" : "") + "。",
                "Reply \"yes\" to write it, \"cancel\" to drop it" + (GraphEdit.ADD_EDGE.equals(p.action) ? ", \"reverse\" to flip the direction" : "") + "."));
        return sb.toString();
    }

    /** What the removal takes off the graph, as the graph records it, and what the removal means afterwards. */
    private static String removeConfirmQuestion(Pending p, Env env) {
        DependencyGraph.Edge e = existingEdge(env.graph(), p.source, p.target);
        StringBuilder sb = new StringBuilder(t(p, "確認要從圖上拿掉這條線：", "Please confirm the removal:"));
        sb.append("\n• `").append(p.source).append("` → `").append(p.target).append("`");
        if (e != null) {
            sb.append(" · ").append(e.type).append(" · ").append(t(p, "信心 ", "confidence ")).append(e.confidence)
                    .append(" · ").append(t(p, "來源 ", "from ")).append(e.provenance.isEmpty() ? "?" : String.join(", ", e.provenance));
            List<String> refs = e.provenanceRefs.subList(0, Math.min(3, e.provenanceRefs.size()));
            sb.append("\n• ").append(t(p, "出處：", "references: ")).append(refs.isEmpty() ? t(p, "（無）", "(none)") : String.join(", ", refs))
                    .append(e.provenanceRefs.size() > refs.size() ? t(p, "（還有 " + (e.provenanceRefs.size() - refs.size()) + " 筆）", " (+" + (e.provenanceRefs.size() - refs.size()) + ")") : "");
            if (e.runtimeObserved) {
                sb.append("\n• ⚠️ ").append(t(p, "這條線在執行期有觀測到" + (e.count > 0 ? "（" + e.count + " 次請求）" : "") + "，拿掉等於說觀測到的不算。",
                        "This edge was observed at runtime" + (e.count > 0 ? " (" + e.count + " requests)" : "") + "; removing it overrides that observation."));
            }
        }
        sb.append("\n• ").append(t(p, "拿掉後，這個 repo 下次分析若再抽到這條線，也會自動拿掉（記成人工修改，by " + (env.user() == null ? "" : env.user()) + "）；回「撤銷」可以復原。",
                "Afterwards, the next analysis of this repository drops the edge again if it finds it (a human edit, by " + (env.user() == null ? "" : env.user()) + "); \"undo\" puts it back."));
        sb.append("\n").append(t(p, "回「確定」拿掉，回「取消」放棄，回「反過來」改方向。",
                "Reply \"yes\" to remove it, \"cancel\" to drop it, \"reverse\" to flip the direction."));
        return sb.toString();
    }

    private static String appliedMessage(Pending p, GraphEdit edit) {
        StringBuilder sb = new StringBuilder();
        if (GraphEdit.REMOVE_EDGE.equals(p.action)) {
            sb.append(t(p, "✅ 已從圖上拿掉：`" + edit.source + "` → `" + edit.target + "`。\n", "✅ Removed: `" + edit.source + "` → `" + edit.target + "`.\n"));
            sb.append(t(p, "這次分析的圖已更新；這個 repo 下次分析若再抽到這條線，也會自動拿掉（記在 repo 的人工修改裡）。想復原就回「撤銷」。",
                    "This report's graph is updated, and the next analysis of this repository drops the edge again if it finds it (kept with the repository's human edits). Reply \"undo\" to put it back."));
            return sb.toString();
        }
        sb.append(t(p, "✅ 已加進圖：", "✅ Added: ")).append(edit.describe()).append('\n');
        sb.append(t(p, "這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。",
                "This report's graph is updated, and the next analysis of this repository applies it again (kept with the repository's human edits)."));
        if (GraphEdit.ADD_EDGE.equals(p.action)) {
            sb.append(' ').append(t(p, "之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。",
                    "If a runtime run later observes the call, the edge turns solid like any other."));
        } else {
            // Who addresses the new node: the edges worth adding next.
            Set<String> owners = new LinkedHashSet<>();
            for (EvidenceFinder.Evidence e : p.evidence) if (e.owner != null && !e.owner.equals(edit.target)) owners.add(e.owner);
            if (!owners.isEmpty()) {
                List<String> first = new ArrayList<>(owners).subList(0, Math.min(3, owners.size()));
                sb.append('\n').append(t(p, "repo 裡寫著它的位址的是 ", "Its address appears in files of "))
                        .append("`").append(String.join("`, `", first)).append("`")
                        .append(t(p, "；要畫線的話可以說「新增 " + first.get(0) + " → " + edit.target + "」。",
                                "; to draw one, say \"add an edge from " + first.get(0) + " to " + edit.target + "\"."));
            }
        }
        return sb.toString();
    }

    // ---------- the edit itself ----------

    static GraphEdit toEdit(Pending p, Env env) {
        GraphEdit e = new GraphEdit();
        e.action = p.action;
        e.target = p.target;
        e.by = env.user() == null ? "" : env.user();
        e.at = env.now() == null ? "" : env.now();
        if (GraphEdit.REMOVE_EDGE.equals(p.action)) {
            // The record of what was taken off, as the graph had it.
            e.source = p.source;
            DependencyGraph.Edge was = existingEdge(env.graph(), p.source, p.target);
            if (was != null) {
                if (was.type != null) e.type = was.type;
                if (was.confidence != null) e.confidence = was.confidence;
            }
            return e;
        }
        if (GraphEdit.ADD_NODE.equals(p.action)) {
            e.kind = p.targetKind.isBlank() ? DependencyGraph.classifyKind(p.target) : p.targetKind;
        } else {
            e.source = p.source;
            e.type = p.type.isBlank() ? "sync-http" : p.type;
            if (p.sourceNew) e.sourceKind = p.sourceKind;
            if (p.targetNew) e.targetKind = p.targetKind;
        }
        e.confidence = p.confidence.isBlank() ? DependencyGraph.CONF_INFERRED : p.confidence;
        e.refs.addAll(p.refs);
        return e;
    }

    /** An edge is being added or removed (as opposed to a node). */
    private static boolean isEdge(Pending p) {
        return !GraphEdit.ADD_NODE.equals(p.action);
    }

    // ---------- reading names and words ----------

    /** The node a raw name denotes: exact, case-insensitive, any spelling of its key, or the one node the name mentions. "" when none. */
    static String resolve(String raw, DependencyGraph graph) {
        if (raw == null || raw.isBlank() || graph == null) return "";
        String r = name(raw);
        for (DependencyGraph.Node n : graph.getNodes()) if (n.id.equals(r)) return n.id;
        for (DependencyGraph.Node n : graph.getNodes()) if (n.id.equalsIgnoreCase(r)) return n.id;
        String k = AliasResolution.key(r);
        for (DependencyGraph.Node n : graph.getNodes()) if (AliasResolution.key(n.id).equals(k)) return n.id;
        List<DependencyGraph.Node> named = GraphGrounding.mentionedNodes(r, graph);
        return named.size() == 1 ? named.get(0).id : "";
    }

    private static AliasResolution.Question question(String raw, List<String> candidates) {
        AliasResolution.Questions q = new AliasResolution.Questions();
        q.addWithCandidates(raw, "edit", "", candidates);
        return q.list().isEmpty() ? null : q.list().get(0);
    }

    private static List<String> nodeIds(DependencyGraph graph) {
        List<String> ids = new ArrayList<>();
        if (graph != null) for (DependencyGraph.Node n : graph.getNodes()) ids.add(n.id);
        return ids;
    }

    private static DependencyGraph.Edge existingEdge(DependencyGraph graph, String source, String target) {
        if (graph == null || source.isBlank() || target.isBlank()) return null;
        for (DependencyGraph.Edge e : graph.getEdges()) if (e.source.equals(source) && e.target.equals(target)) return e;
        return null;
    }

    private static void swap(Pending p) {
        String s = p.source, sr = p.sourceRaw, sk = p.sourceKind;
        boolean sn = p.sourceNew;
        p.source = p.target;
        p.sourceRaw = p.targetRaw;
        p.sourceKind = p.targetKind;
        p.sourceNew = p.targetNew;
        p.target = s;
        p.targetRaw = sr;
        p.targetKind = sk;
        p.targetNew = sn;
        // The traces were of the other direction.
        p.searched = false;
        p.evidenceAnswered = false;
        p.evidence.clear();
        p.refs.clear();
        p.confidence = "";
        p.suggestedType = "";
        p.type = "";
    }

    private static String parseKind(String lower) {
        if (lower.matches("\\d")) {
            int i = Integer.parseInt(lower) - 1;
            return i >= 0 && i < KINDS.size() ? KINDS.get(i) : null;
        }
        return switch (lower) {
            case "service", "服務", "微服務", "app" -> DependencyGraph.KIND_SERVICE;
            case "db", "database", "資料庫", "datastore" -> DependencyGraph.KIND_DB;
            case "queue", "broker", "mq", "佇列", "訊息佇列" -> DependencyGraph.KIND_QUEUE;
            case "external", "外部", "外部主機", "saas", "third-party" -> DependencyGraph.KIND_EXTERNAL;
            case "gateway", "閘道", "入口" -> DependencyGraph.KIND_GATEWAY;
            default -> null;
        };
    }

    static String parseType(String lower) {
        if (lower.matches("\\d")) {
            int i = Integer.parseInt(lower) - 1;
            return i >= 0 && i < TYPES.size() ? TYPES.get(i) : null;
        }
        return switch (lower) {
            case "sync-http", "http", "https", "rest", "sync", "同步", "http 呼叫" -> "sync-http";
            case "grpc" -> "grpc";
            case "async", "queue", "mq", "kafka", "rabbitmq", "event", "events", "非同步", "佇列", "訊息", "事件" -> "async";
            case "db", "database", "資料庫" -> "db";
            case "external", "外部", "ext" -> "external";
            case "startup", "啟動", "啟動順序", "depends_on" -> DependencyGraph.TYPE_STARTUP;
            default -> null;
        };
    }

    private static String kindLabel(Pending p, String kind) {
        return switch (kind == null ? "" : kind) {
            case DependencyGraph.KIND_DB -> t(p, "資料庫", "database");
            case DependencyGraph.KIND_QUEUE -> t(p, "訊息佇列", "message broker");
            case DependencyGraph.KIND_EXTERNAL -> t(p, "外部主機", "external host");
            case DependencyGraph.KIND_GATEWAY -> t(p, "閘道", "gateway");
            default -> t(p, "服務", "service");
        };
    }

    private static String typeLabel(Pending p, String type) {
        return switch (type) {
            case "sync-http" -> t(p, "sync-http（HTTP／REST 呼叫）", "sync-http (an HTTP/REST call)");
            case "grpc" -> t(p, "grpc（gRPC 呼叫）", "grpc (a gRPC call)");
            case "async" -> t(p, "async（經訊息佇列）", "async (through a message broker)");
            case "db" -> t(p, "db（連資料庫）", "db (a database connection)");
            case "external" -> t(p, "external（叢集外的主機）", "external (a host outside the cluster)");
            default -> t(p, "startup（只是啟動順序，不是呼叫）", "startup (start-up order only, not a call)");
        };
    }

    private static String endpoint(Pending p, boolean isSource) {
        String id = isSource ? p.source : p.target;
        String raw = isSource ? p.sourceRaw : p.targetRaw;
        if (!id.isBlank()) return "`" + id + "`";
        return raw.isBlank() ? t(p, "（未指定）", "(unspecified)") : "`" + name(raw) + "`";
    }

    private static String name(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("^[`'\"]+|[`'\"]+$", "");
    }

    private static String clip(String s) {
        String one = s == null ? "" : s.replace('`', '\'').trim();
        return one.length() <= 140 ? one : one.substring(0, 139) + "…";
    }

    static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(s.charAt(i));
            if (sc == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static String t(Pending p, String zh, String en) {
        return "zh".equals(p.lang) ? zh : en;
    }

    private static final Pattern FILE_LINE = Pattern.compile("([A-Za-z0-9_./\\-]+\\.[A-Za-z0-9]+|[A-Za-z0-9_./\\-]*Dockerfile):(\\d{1,6})");
    /** "undo" while an edit is open means this one: nothing has been written yet. */
    private static final Set<String> CANCEL = Set.of("取消", "算了", "不要了", "放棄", "撤銷", "cancel", "stop", "abort", "never mind", "nevermind", "undo");
    private static final Set<String> REVERSE = Set.of("反過來", "反了", "相反", "方向反了", "reverse", "flip", "other way", "the other way");
    private static final Set<String> YES = Set.of("確定", "好", "好的", "對", "是", "是的", "沒錯", "可以", "ok", "okay", "yes", "y", "yep", "sure", "confirm", "對的", "要");
    private static final Set<String> NO = Set.of("不", "不要", "否", "no", "n", "nope");
    private static final Set<String> NONE = Set.of("沒有", "無", "沒", "none", "no", "nothing", "skip", "略過", "不知道", "n/a");
    private static final Set<String> ALL = Set.of("全部", "都要", "all", "both", "都是", "兩個都要");
}
