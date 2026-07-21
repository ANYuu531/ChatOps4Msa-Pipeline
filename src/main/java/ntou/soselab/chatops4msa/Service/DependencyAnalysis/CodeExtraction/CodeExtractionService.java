package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clones the repository once, detects every stack in it, and routes each stack to
 * the extraction tier that can handle it:
 *
 *   Tier 1  grammar + framework query pack  -> tree-sitter (deterministic)
 *   Tier 2  grammar, unknown framework      -> tree-sitter, generic pack only
 *   Tier 3  no grammar                      -> the LLM reads the source
 *
 * A polyglot repository uses several tiers at once and still produces one ledger,
 * which is the normal case for microservice repos (bookinfo is Python + Java +
 * Ruby + Node).
 */
@Service
public class CodeExtractionService {

    /** The ledger plus the external hosts found in it. */
    public static class ExtractionResult {
        public final String ledger;
        public final List<ExternalHost> externalHosts;
        /** The same edges as structured JSON, for the dependency-graph merge (see EdgeLedger.toJson). */
        public final String edgesJson;

        ExtractionResult(String ledger, List<ExternalHost> externalHosts, String edgesJson) {
            this.ledger = ledger;
            this.externalHosts = externalHosts;
            this.edgesJson = edgesJson;
        }
    }

    private final StackDetector stackDetector;
    private final TreeSitterExtractor treeSitterExtractor;
    private final LlmCodeExtractor llmCodeExtractor;
    private final ConfigExtractor configExtractor;
    private final ExternalHostDetector externalHostDetector;

    @Autowired
    public CodeExtractionService(StackDetector stackDetector,
                                 TreeSitterExtractor treeSitterExtractor,
                                 LlmCodeExtractor llmCodeExtractor,
                                 ConfigExtractor configExtractor,
                                 ExternalHostDetector externalHostDetector) {
        this.stackDetector = stackDetector;
        this.treeSitterExtractor = treeSitterExtractor;
        this.llmCodeExtractor = llmCodeExtractor;
        this.configExtractor = configExtractor;
        this.externalHostDetector = externalHostDetector;
    }

    /**
     * @param repo "owner/repo" or a clone URL
     * @return the rendered Code-Extracted Edge Ledger plus the external hosts it
     *         mentions; never throws, so a failure here degrades the dependency
     *         analysis instead of aborting it.
     */
    public ExtractionResult extract(String repo) {
        EdgeLedger ledger = new EdgeLedger();
        ledger.setRepo(repo == null ? "" : repo);

        if (repo == null || repo.isBlank()) {
            ledger.fail("no repository was provided");
            return new ExtractionResult(ledger.render(), List.of(), ledger.toJson().toString());
        }

        try (RepoWorkspace workspace = RepoWorkspace.clone(repo)) {
            if (!workspace.isCloned()) {
                ledger.fail(workspace.getFailure());
                return new ExtractionResult(ledger.render(), List.of(), ledger.toJson().toString());
            }
            Path root = workspace.getRoot();

            List<DetectedStack> stacks = stackDetector.detect(root);
            if (stacks.isEmpty()) {
                ledger.fail("no recognisable source code found in the repository");
                return new ExtractionResult(ledger.render(), List.of(), ledger.toJson().toString());
            }

            List<String> stackDescriptions = new ArrayList<>();
            List<String> methods = new ArrayList<>();

            for (DetectedStack stack : stacks) {
                stackDescriptions.add(stack.describe());
                try {
                    if (stack.tier == DetectedStack.Tier.LLM) {
                        methods.add("LLM source reading (" + stack.language + ")");
                        llmCodeExtractor.extract(root, stack, ledger);
                    } else {
                        methods.add("tree-sitter " + String.join("+", stack.queryPacks()));
                        treeSitterExtractor.extract(root, stack, ledger);
                    }
                } catch (Exception e) {
                    // One bad stack must not lose the edges the others already found.
                    ledger.addWarning("extraction failed for " + stack.language + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            try {
                configExtractor.extract(root, ledger);
            } catch (Exception e) {
                ledger.addWarning("config extraction failed: " + e.getMessage());
            }

            ledger.setStack(String.join("; ", stackDescriptions));
            ledger.setMethod(String.join("; ", methods) + "; config parsing");

            // The external hosts are only knowable from the code: Istio cannot see
            // an unmeshed destination. They become ServiceEntry suggestions so the
            // mesh can then confirm these edges at runtime too.
            List<ExternalHost> externalHosts = externalHostDetector.detect(ledger);
            return new ExtractionResult(ledger.render(), externalHosts, ledger.toJson().toString());

        } catch (Exception e) {
            ledger.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            return new ExtractionResult(ledger.render(), List.of(), ledger.toJson().toString());
        }
    }
}
