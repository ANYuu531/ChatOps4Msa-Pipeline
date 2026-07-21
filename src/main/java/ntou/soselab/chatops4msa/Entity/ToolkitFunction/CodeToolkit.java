package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.CodeExtractionService;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHost;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ExternalHostDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Source-code extraction of microservice dependencies.
 *
 * The repository is cloned once, its stacks are detected from the build
 * manifests, and each stack is extracted by the tier that supports it:
 * tree-sitter query packs where a grammar exists, otherwise the LLM reads the
 * source directly. See CodeExtractionService.
 *
 * The toolkit is no longer Java-specific, hence toolkit-code-extract rather than
 * the previous toolkit-code-extract-java.
 */
@Component
public class CodeToolkit extends ToolkitFunction {

    private final CodeExtractionService codeExtractionService;
    private final ExternalHostDetector externalHostDetector;
    private final DependencyAnalysisStateStore stateStore;

    @Autowired
    public CodeToolkit(CodeExtractionService codeExtractionService,
                       ExternalHostDetector externalHostDetector,
                       DependencyAnalysisStateStore stateStore) {
        this.codeExtractionService = codeExtractionService;
        this.externalHostDetector = externalHostDetector;
        this.stateStore = stateStore;
    }

    /**
     * @param repo GitHub repository as "owner/repo", or a full clone URL.
     * @return a Code-Extracted Edge Ledger. Fails soft: a clone or parse failure
     *         is reported inside the ledger so the surrounding dependency-analysis
     *         flow still completes on documentation and runtime evidence.
     */
    public String toolkitCodeExtract(String repo) {
        CodeExtractionService.ExtractionResult result = codeExtractionService.extract(repo);

        // Stash the external hosts on the checkpoint so the ServiceEntry manifest
        // can be produced (and reproduced after a resume) without cloning again.
        String userId = UserContextHolder.getUserId();
        if (userId != null && !userId.isBlank()) {
            stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_EXTERNAL_HOSTS,
                    ExternalHost.toJson(result.externalHosts).toString());
            // Structured code edges for the dependency-graph merge (Phase 2).
            stateStore.putStage(userId, DependencyAnalysisStateStore.STAGE_CODE_EDGES,
                    result.edgesJson);
        }
        return result.ledger;
    }

    /**
     * Renders ServiceEntry manifests for the external hosts the code extraction
     * found, reading them from the checkpoint (no second clone).
     *
     * Istio cannot observe an external dependency on its own: the callee has no
     * sidecar, and without a ServiceEntry the caller's sidecar routes the traffic
     * through PassthroughCluster, which loses the hostname. Declaring these turns a
     * static-only finding into an edge the mesh can confirm at runtime.
     */
    public String toolkitCodeServiceEntries(String namespace) {
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            return "# no user context; cannot read the external hosts from the checkpoint.";
        }
        List<ExternalHost> hosts = ExternalHost.fromJson(
                stateStore.getStage(userId, DependencyAnalysisStateStore.STAGE_EXTERNAL_HOSTS));

        return externalHostDetector.summarize(hosts)
                + "\n\n"
                + externalHostDetector.renderServiceEntries(hosts, namespace);
    }
}
