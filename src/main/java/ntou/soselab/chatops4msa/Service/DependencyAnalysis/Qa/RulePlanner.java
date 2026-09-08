package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The deterministic planner: maps the common question shapes straight to graph
 * queries, so that "what breaks if X goes down" runs {@code impact-of(X)} every
 * time, in either language, without a model in the loop.
 *
 * It sits in front of {@link GraphQueryPlanner}. When a rule fires, the model is not
 * asked at all — the answer to a recognised question shape should not depend on how
 * the model felt about it, and each avoided call is a second or two and a little cost
 * saved. The model planner remains for the phrasings the rules do not know; the
 * rules are the phrasings the real-environment runs showed to matter.
 *
 * Patterns are deliberately loose (a stem, not a sentence): the cost of a false
 * positive is one extra query block in the context, the cost of a miss is a model
 * call. Every query still goes through the same validation as a model plan.
 */
public final class RulePlanner {

    static final int MAX_QUERIES = 4;

    private static final Pattern IMPACT = p("影響|波及|掛(了|掉)|壞(了|掉)|故障|當機|停掉|停了|down|fail|impact|affect|break|outage|crash");
    private static final Pattern STARTUP = p("先(起|跑|啟|部署|有|準備)|前置|前提|prerequisite|must be (running|up)|before .*(start|run|work)|needs? to (be )?(run|up)|start ?up needs|what does .* need|要能(跑|運作|正常)");
    private static final Pattern ORDER = p("(部署|啟動|上線|deploy\\w*|start ?up|boot\\w*|bring ?up).*(順序|order|sequence|first)|(順序|order|sequence).*(部署|啟動|上線|deploy|start)|deploy first|先部署(哪|什麼)");
    private static final Pattern PATH = p("怎麼(連|到|叫|呼叫|打|走|找到)|路徑|經過|path|reach|route|how does .*(call|get|talk|reach)|hop|link between|之間");
    private static final Pattern UNCOVERED = p("沒(有)?(被)?(跑|觀測|覆蓋|驅動|看)|未(被)?(觀測|覆蓋|驅動)|覆蓋率|coverage|uncovered|not (yet )?(observed|exercised|covered|seen|hit)|never (observed|exercised|hit)|declared but|only declared|virtual|虛線|dashed");
    // Checked only after UNCOVERED, so "沒觀測到" / "never observed" never lands here.
    private static final Pattern OBSERVED = p("觀測|觀察|實線|solid|observed|runtime.?(saw|seen)|actually (saw|called)|istio (saw|seen)|有跑到|有流量");

    /** Questions about the report itself, not the graph: a query plan has nothing to add. */
    private static final Pattern META = p("這份報告|報告(有|是|裡|的|中|本身)|限制|limitation|collection status|查過|有沒有查|was .* queried|report('s| is| was| say| itself)|confidence summary|誰寫|who wrote|section \\d");
    private static final Pattern DB = p("資料庫|database|\\bdbs?\\b|datastore|persist|postgres|mysql|mongo|redis|儲存");
    private static final Pattern UNDEPLOYED = p("沒(有)?部署|未部署|not deployed|undeployed|not running|missing from the cluster|isn'?t running|沒在跑");
    private static final Pattern EXTERNAL = p("外部|external|third.?party|第三方|outside the (mesh|cluster)|internet");
    private static final Pattern ASYNC = p("佇列|queue|broker|kafka|rabbit|amqp|非同步|async|event|訊息(佇列|匯流)|pub.?sub|topic");
    private static final Pattern MENTIONED = p("只(被)?.{0,4}提到|只有文件|文件才|mentioned.?only|only mentioned|no usage evidence|dotted|點線|inferred");
    private static final Pattern DEPENDENCY_WORDS = p("依賴|相依|呼叫|叫|用到|使用|depend|call|use|talk|rely|need");

    /** Question shapes that ask for a set or a traversal — a fact sheet alone will not answer them. */
    private static final Pattern NEEDS_SET = p("哪些|哪幾|所有|全部|每個|多少|幾(個|條)|which|what (are|edges|services|nodes)|all|every|list|how many|enumerate");

    private RulePlanner() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * @param question  the user's question
     * @param mentioned the graph nodes the question names, in order of mention
     * @return the queries the rules recognise, at most {@link #MAX_QUERIES}; empty when none fires
     */
    public static List<GraphQuery> plan(String question, List<DependencyGraph.Node> mentioned, DependencyGraph graph) {
        List<GraphQuery> out = new ArrayList<>();
        if (question == null || question.isBlank() || graph == null) return out;
        String q = question.toLowerCase(Locale.ROOT);
        List<DependencyGraph.Node> named = mentioned == null ? List.of() : mentioned;

        // Pairs first: a two-node question is about their relation, whatever else it says.
        if (named.size() >= 2 && (PATH.matcher(q).find() || DEPENDENCY_WORDS.matcher(q).find())) {
            add(out, "path", named.get(0).id, named.get(1).id);
        }
        if (IMPACT.matcher(q).find()) {
            for (int i = 0; i < named.size() && i < 2; i++) add(out, "impact-of", named.get(i).id);
        }
        if (STARTUP.matcher(q).find()) {
            for (int i = 0; i < named.size() && i < 2; i++) add(out, "startup-needs", named.get(i).id);
        }
        if (ORDER.matcher(q).find()) add(out, "deploy-order");

        boolean uncovered = UNCOVERED.matcher(q).find();
        if (uncovered) {
            add(out, "uncovered");
            add(out, "unobserved-edges");
        } else if (OBSERVED.matcher(q).find()) {
            add(out, "observed-edges");
        }
        if (MENTIONED.matcher(q).find()) add(out, "mentioned-only");
        if (UNDEPLOYED.matcher(q).find()) add(out, "undeployed");
        if (EXTERNAL.matcher(q).find()) add(out, "externals");
        if (ASYNC.matcher(q).find()) add(out, "async");
        // "which services use a database" is a set question; "does X use a db" is
        // answered by X's fact sheet, so the rule only fires with no node named.
        if (named.isEmpty() && DB.matcher(q).find()) add(out, "db-users");

        return out.size() > MAX_QUERIES ? new ArrayList<>(out.subList(0, MAX_QUERIES)) : out;
    }

    /**
     * Whether the model planner is worth a call once the rules found nothing: yes when
     * the question names no node (the fact sheets have nothing to say) or asks for a set
     * or count; no for a plain question about a named node, which its fact sheet answers.
     */
    public static boolean needsLlmPlanner(String question, List<DependencyGraph.Node> mentioned) {
        if (question == null || question.isBlank()) return false;
        String q = question.toLowerCase(Locale.ROOT);
        if (META.matcher(q).find()) return false; // answered from the report text, not the graph
        if (mentioned == null || mentioned.isEmpty()) return true;
        return NEEDS_SET.matcher(q).find();
    }

    private static void add(List<GraphQuery> out, String op, String... args) {
        GraphQuery q = new GraphQuery(op, List.of(args));
        if (!out.contains(q)) out.add(q);
    }
}
