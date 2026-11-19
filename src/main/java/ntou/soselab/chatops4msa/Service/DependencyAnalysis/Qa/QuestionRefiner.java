package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.json.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rewords a question the semantic router was not sure about, towards the example
 * phrasings nearest to it, and routes the rewording.
 *
 * The router scores a question against example phrasings and answers only when one
 * intent clearly wins. A question the examples did not anticipate ("這個系統會打到外面的
 * API 嗎") scores low and goes to the model planner. The fix so far was to add the missing
 * phrasing by hand, each time. This step lets the model bridge the gap at run time
 * instead: retrieve the nearest examples (the retrieval-augmented part — the question's
 * own vector against the example vectors), show them to the model, and ask for the
 * question said again in their words.
 *
 * What keeps this from turning the calibrated router into "whatever the model says":
 * <ul>
 *   <li>the model only sees the {@value #INTENTS} nearest intents, so it can only pick
 *       among meanings the embedding already found plausible;</li>
 *   <li>the rewording is not trusted — it is routed by the same {@link SemanticRouter}
 *       with the same thresholds, and the node arguments still come from the
 *       <em>original</em> question's mentions;</li>
 *   <li>the route is accepted only when it is confident AND agrees with the intent the
 *       model named — a rewording that drifts to another meaning is thrown away;</li>
 *   <li>anything else (model says none, junk output, a call failure) falls through to the
 *       planner exactly as before.</li>
 * </ul>
 * The accepted rewording is logged, so the phrasings real users need can be read off
 * the log and, after a look, become examples.
 *
 * <p><b>Second opinion on a route that only just made it.</b> "這個系統會打到外面的 API 嗎"
 * scored 0.541 against T=0.51 with a lead of 0.102 against M=0.10: confident, and right,
 * but by 0.002. Nothing looked at that band. {@link #secondOpinion} runs the same
 * rewording on a confident decision the router calls {@link SemanticRouter#marginal
 * marginal}: a rewording that routes confidently to the <em>same</em> intent confirms
 * it (and is worth keeping as an example); one that routes to a <em>different</em>
 * intent means two readings disagree, and the planner decides; anything else leaves the
 * original route alone, since the thresholds already vouched for it. How wide the band
 * is ({@link #bandScore}, {@link #bandLead}) is a threshold like T and M, chosen by the
 * sweep in {@code QuestionRefinerHoldoutTest}; until it has run, the band is zero and
 * this stage is off.
 */
public final class QuestionRefiner {

    /** One chat completion; {@code null} on failure. */
    public interface Chat {
        String complete(String system, String user);
    }

    /** The menu the model is shown: the nearest intents with the nearest examples of each, every other intent with one. */
    static final int INTENTS = 5;
    static final int EXAMPLES_PER_INTENT = 3;
    static final int REST_EXAMPLES = 1;
    /** Before a rewording is accepted, ask once more whether it asks the same thing as the question (one small call). */
    static final boolean JUDGE = true;
    private static final String PROMPT_FILE = "prompts/question_refine.txt";
    private static final String JUDGE_FILE = "prompts/question_same.txt";
    /** The first line of the judge prompt, so a test double can tell the two prompts apart. */
    private static final String JUDGE_MARK = "You compare two questions";
    static final String NONE = "none";

    /** What the model proposed and what became of it. */
    public static final class Result {
        /** The intents the model was offered, best first. */
        public final List<String> shortlist;
        /** The model's own label; {@code none} when it declined or the output was unusable. */
        public final String proposedIntent;
        public final String rewrite;
        /** The route of the rewording; {@code null} when there was nothing to route. */
        public final SemanticRouter.Decision rerouted;
        /** True when {@link #rerouted} is the decision to use instead of the planner. */
        public final boolean accepted;
        public final String reason;
        /** The same-meaning judge's answer on a cleanly routed rewording; {@code null} when it was not asked or did not answer. */
        public final Boolean same;

        Result(List<String> shortlist, String proposedIntent, String rewrite, SemanticRouter.Decision rerouted,
               boolean accepted, String reason) {
            this(shortlist, proposedIntent, rewrite, rerouted, accepted, reason, null);
        }

        Result(List<String> shortlist, String proposedIntent, String rewrite, SemanticRouter.Decision rerouted,
               boolean accepted, String reason, Boolean same) {
            this.shortlist = shortlist;
            this.proposedIntent = proposedIntent;
            this.rewrite = rewrite;
            this.rerouted = rerouted;
            this.accepted = accepted;
            this.reason = reason;
            this.same = same;
        }

        @Override
        public String toString() {
            return (accepted ? "accepted" : "rejected (" + reason + ")") + " intent=" + proposedIntent
                    + " rewrite=\"" + rewrite + "\"" + (rerouted == null ? "" : " -> " + rerouted);
        }
    }

    /** The second opinion's verdict on a marginal route. */
    public enum Verdict {
        /** The rewording routes confidently to the same intent: the route stands, confirmed. */
        AGREE,
        /** The rewording routes confidently to another intent: two readings disagree, the planner decides. */
        DISAGREE,
        /** No usable rewording (model said none, unsure, drift, junk, failure): the route stands, unconfirmed. */
        FAILED
    }

    public static final class Opinion {
        public final Result result;
        public final Verdict verdict;

        Opinion(Result result, Verdict verdict) {
            this.result = result;
            this.verdict = verdict;
        }

        @Override
        public String toString() {
            return verdict + " " + result;
        }
    }

    /**
     * Band widths of the second opinion: zero until the sweep has chosen them, so a
     * marginal route is left as the thresholds decided it.
     */
    public static final double DEFAULT_BAND_SCORE = 0.0;
    public static final double DEFAULT_BAND_LEAD = 0.0;

    private final SemanticRouter router;
    private final SemanticRouter.Embedder embedder;
    private final Chat chat;
    private final String template;
    private final double bandScore;
    private final double bandLead;
    /** How many nearest intents carry {@link #EXAMPLES_PER_INTENT} examples; {@link #INTENTS} unless an experiment changes it. */
    private final int intents;
    /** Examples for every other intent: {@link #REST_EXAMPLES} (the whole menu) or 0 (the nearest only). */
    private final int restExamples;
    /** Whether a cleanly routed rewording is still compared with the question before it is accepted. */
    private final boolean judge;
    private final String judgeTemplate;

    public QuestionRefiner(SemanticRouter router, SemanticRouter.Embedder embedder, Chat chat) {
        this(router, embedder, chat, DEFAULT_BAND_SCORE, DEFAULT_BAND_LEAD);
    }

    public QuestionRefiner(SemanticRouter router, SemanticRouter.Embedder embedder, Chat chat, double bandScore, double bandLead) {
        this(router, embedder, chat, bandScore, bandLead, INTENTS, REST_EXAMPLES, JUDGE);
    }

    public QuestionRefiner(SemanticRouter router, SemanticRouter.Embedder embedder, Chat chat, double bandScore, double bandLead,
                           int intents, int restExamples, boolean judge) {
        this.router = router;
        this.embedder = embedder;
        this.chat = chat;
        this.bandScore = bandScore;
        this.bandLead = bandLead;
        this.intents = Math.max(1, intents);
        this.restExamples = Math.max(0, restExamples);
        this.judge = judge;
        this.template = loadTemplate(PROMPT_FILE, "Reword the user's question as one of these kinds, keeping its meaning and service names:\n<CANDIDATES>\n"
                + "Reply with {\"intent\": \"<kind or none>\", \"rewrite\": \"<one sentence>\"} only.");
        this.judgeTemplate = loadTemplate(JUDGE_FILE, JUDGE_MARK + " about a dependency graph. Does B ask exactly the same thing as A "
                + "(same kind, same services, same direction and polarity)? Reply with {\"same\": true} or {\"same\": false} only.");
    }

    /** Whether the same-meaning judge runs before a rewording is accepted. */
    public boolean judge() {
        return judge;
    }

    /** True for the judge's system prompt rather than the rewording prompt. */
    static boolean isJudgePrompt(String system) {
        return system != null && system.startsWith(JUDGE_MARK);
    }

    /** How many nearest intents carry the full set of examples. */
    public int intents() {
        return intents;
    }

    /** Examples per intent outside the nearest ones; 0 when the model sees the nearest only. */
    public int restExamples() {
        return restExamples;
    }

    /** True when both texts are in the same script: a CJK question must stay CJK, an English one English. */
    static boolean sameLanguage(String a, String b) {
        return hasCjk(a) == hasCjk(b);
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** A confident decision inside the second-opinion band; never when the band is zero. */
    public boolean marginal(SemanticRouter.Decision d) {
        return (bandScore > 0 || bandLead > 0) && router.marginal(d, bandScore, bandLead);
    }

    /** The same rewording, asked of a confident but marginal route, read as a verdict on it. */
    public Opinion secondOpinion(String question, double[] questionVector, List<DependencyGraph.Node> mentioned,
                                 SemanticRouter.Decision original) {
        Result r = refine(question, questionVector, mentioned);
        return new Opinion(r, verdict(r, original.intent));
    }

    static Verdict verdict(Result r, String originalIntent) {
        if (r == null || !r.accepted) return Verdict.FAILED;
        return r.rerouted.intent.equals(originalIntent) ? Verdict.AGREE : Verdict.DISAGREE;
    }

    /**
     * Whether this step can do anything for an unsure decision. A question that names no
     * node for an intent that needs one is the planner's (it sees the ids); so is a message
     * that reads as an edit, and so is nothing at all when no vector exists.
     */
    public static boolean applies(SemanticRouter.Decision unsure, double[] questionVector) {
        return unsure != null && !unsure.confident && !unsure.editLike && !unsure.missingNode && questionVector != null;
    }

    /**
     * @param question       the question as typed
     * @param questionVector the vector the router scored — the masked form when the question named nodes
     * @param mentioned      the nodes the question names; the rewording's arguments are these
     */
    public Result refine(String question, double[] questionVector, List<DependencyGraph.Node> mentioned) {
        List<SemanticRouter.Candidate> candidates = router.shortlist(questionVector, intents, EXAMPLES_PER_INTENT, restExamples);
        List<String> shortlist = new ArrayList<>();
        for (SemanticRouter.Candidate c : candidates) if (!shortlist.contains(c.intent.name)) shortlist.add(c.intent.name);
        if (shortlist.isEmpty()) return new Result(shortlist, NONE, "", null, false, "no examples indexed");

        String response;
        try {
            response = chat == null ? null : chat.complete(systemPrompt(candidates), question);
        } catch (Exception e) {
            response = null;
        }
        if (response == null) return new Result(shortlist, NONE, "", null, false, "no model answer");

        Proposal p = parse(response);
        if (p == null) return new Result(shortlist, NONE, "", null, false, "unparseable answer");
        if (NONE.equals(p.intent)) return new Result(shortlist, NONE, "", null, false, "model: none of these");
        if (!shortlist.contains(p.intent)) return new Result(shortlist, p.intent, p.rewrite, null, false, "intent not on the shortlist");
        if (p.rewrite.isBlank()) return new Result(shortlist, p.intent, "", null, false, "empty rewrite");
        // A rewording in another script is not the user's question any more (11/19: "translate the
        // report into Japanese?" came back as a Chinese about-report question and routed at 0.90).
        if (!sameLanguage(question, p.rewrite)) return new Result(shortlist, p.intent, p.rewrite, null, false, "rewrite changes language");

        List<double[]> v = embedder == null ? null : embedder.embed(List.of(p.rewrite));
        if (v == null || v.size() != 1 || v.get(0) == null) return new Result(shortlist, p.intent, p.rewrite, null, false, "rewrite not embedded");
        SemanticRouter.Decision d = router.routeQuestion(p.rewrite, v.get(0), mentioned);
        if (!d.confident) return new Result(shortlist, p.intent, p.rewrite, d, false, "rewrite routes unsure");
        if (!d.intent.equals(p.intent)) return new Result(shortlist, p.intent, p.rewrite, d, false, "rewrite routes to another intent");
        if (!judge) return new Result(shortlist, p.intent, p.rewrite, d, true, "");
        // Routing cleanly to the intent the model named only says the rewording is a good example of
        // that intent — not that it is still the user's question (11/19: "what is missing from the
        // namespace compared to the code?" became "what is documented but not evidenced in code?").
        Boolean same = sameMeaning(question, p.rewrite);
        if (same == null) return new Result(shortlist, p.intent, p.rewrite, d, false, "no judge answer", null);
        if (!same) return new Result(shortlist, p.intent, p.rewrite, d, false, "rewrite drifts from the question", false);
        return new Result(shortlist, p.intent, p.rewrite, d, true, "", true);
    }

    /** One small call: does the rewording ask exactly what the question asks? {@code null} when the model gave no usable answer. */
    Boolean sameMeaning(String question, String rewrite) {
        String response;
        try {
            response = chat == null ? null : chat.complete(judgeTemplate, "A: " + question + "\nB: " + rewrite);
        } catch (Exception e) {
            response = null;
        }
        if (response == null) return null;
        int from = response.indexOf('{'), to = response.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try {
            JSONObject o = new JSONObject(response.substring(from, to + 1));
            return o.has("same") ? o.getBoolean("same") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The prompt with the shortlisted intents and their nearest examples filled in. */
    String systemPrompt(List<SemanticRouter.Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        String current = null;
        for (SemanticRouter.Candidate c : candidates) {
            if (!c.intent.name.equals(current)) {
                current = c.intent.name;
                sb.append("- ").append(current).append('\n');
            }
            sb.append("    \"").append(c.example).append("\"\n");
        }
        return template.replace("<CANDIDATES>", sb.toString().stripTrailing());
    }

    static final class Proposal {
        final String intent;
        final String rewrite;

        Proposal(String intent, String rewrite) {
            this.intent = intent;
            this.rewrite = rewrite;
        }
    }

    /** The JSON object in the model's answer, fence or not; {@code null} when there is none. */
    static Proposal parse(String response) {
        if (response == null) return null;
        int from = response.indexOf('{'), to = response.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try {
            JSONObject o = new JSONObject(response.substring(from, to + 1));
            String intent = o.optString("intent", NONE).trim().toLowerCase(Locale.ROOT);
            String rewrite = o.optString("rewrite", "").trim().replaceAll("\\s+", " ");
            return new Proposal(intent.isEmpty() ? NONE : intent, rewrite);
        } catch (Exception e) {
            return null;
        }
    }

    private static String loadTemplate(String file, String fallback) {
        try {
            return new String(FileCopyUtils.copyToByteArray(new ClassPathResource(file).getInputStream()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[WARNING] prompt missing (" + file + ")");
            return fallback;
        }
    }
}
