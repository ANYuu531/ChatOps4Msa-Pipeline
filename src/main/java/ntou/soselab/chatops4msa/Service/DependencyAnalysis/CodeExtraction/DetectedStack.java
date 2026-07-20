package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import java.util.List;

/**
 * One language/framework found in the repository, and how it will be extracted.
 *
 * A repository can yield several of these: microservice demos are routinely
 * polyglot (Istio's bookinfo is Python + Java + Ruby + Node in one repo), so the
 * detector returns a list and each stack is extracted by whichever tier supports
 * it. The results merge into a single EdgeLedger.
 */
public class DetectedStack {

    /** Which extraction tier can handle this stack. */
    public enum Tier {
        /** tree-sitter grammar + a framework-specific query pack. */
        FRAMEWORK,
        /** tree-sitter grammar, but the framework is unrecognised: generic pack only. */
        GENERIC,
        /** No tree-sitter grammar bundled: the LLM reads the source instead. */
        LLM
    }

    public final String language;
    /** null when no framework was recognised. */
    public final String framework;
    public final Tier tier;
    /** File extensions belonging to this language, e.g. [".java"]. */
    public final List<String> extensions;
    /** What made us decide this, e.g. "pom.xml (spring-boot-starter-web)". */
    public final String evidence;

    public DetectedStack(String language, String framework, Tier tier,
                         List<String> extensions, String evidence) {
        this.language = language;
        this.framework = framework;
        this.tier = tier;
        this.extensions = extensions;
        this.evidence = evidence;
    }

    /** e.g. "java/spring (FRAMEWORK)" or "ruby (LLM)". */
    public String describe() {
        return language
                + (framework == null ? "" : "/" + framework)
                + " (" + tier + ", from " + evidence + ")";
    }

    /** Base name of the query packs to load, most specific first. */
    public List<String> queryPacks() {
        if (tier == Tier.FRAMEWORK) return List.of(language + "-" + framework, language + "-generic");
        if (tier == Tier.GENERIC) return List.of(language + "-generic");
        return List.of();
    }
}
