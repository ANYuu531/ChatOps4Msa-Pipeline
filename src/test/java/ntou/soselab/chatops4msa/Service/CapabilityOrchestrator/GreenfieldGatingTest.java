package ntou.soselab.chatops4msa.Service.CapabilityOrchestrator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The greenfield gate: which body steps the orchestrator skips when no namespace
 * was given. No Spring context — a pure decision test.
 */
class GreenfieldGatingTest {

    @Test
    void alwaysClusterToolkitsAreGated() {
        assertTrue(CapabilityOrchestrator.requiresLiveCluster("toolkit-prometheus-query", Map.of()));
        assertTrue(CapabilityOrchestrator.requiresLiveCluster("toolkit-traffic-run", Map.of()));
        assertTrue(CapabilityOrchestrator.requiresLiveCluster("toolkit-depstate-apply-button", Map.of()));
    }

    @Test
    void mcpIsGatedOnlyForTheK8sServer() {
        // k8s MCP needs the cluster...
        assertTrue(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-mcp-connect", Map.of("server_name", "k8s")));
        assertTrue(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-mcp-call-tool", Map.of("server_name", "k8s")));
        // ...but the DeepWiki MCP is documentation and must still run in greenfield.
        assertFalse(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-mcp-call-tool", Map.of("server_name", "deepwiki")));
    }

    @Test
    void staticStepsAreNeverGated() {
        assertFalse(CapabilityOrchestrator.requiresLiveCluster("toolkit-code-extract", Map.of()));
        assertFalse(CapabilityOrchestrator.requiresLiveCluster("toolkit-llm-call", Map.of()));
        assertFalse(CapabilityOrchestrator.requiresLiveCluster("toolkit-discord-text", Map.of()));
        assertFalse(CapabilityOrchestrator.requiresLiveCluster("toolkit-depstate-put", Map.of()));
    }

    @Test
    void runtimeSummaryLlmStepsAreGatedButDocsAndReportAreNot() {
        // These LLM steps only summarise cluster/runtime evidence — skip them in
        // greenfield so they cannot hallucinate a k8s/Istio inventory from empty input.
        assertTrue(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-llm-call", Map.of("prompt_template", "k8s_runtime_notes")));
        assertTrue(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-llm-call", Map.of("prompt_template", "istio_runtime_edges")));
        assertTrue(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-llm-call", Map.of("prompt_template", "istio_egress_edges")));
        // Documentation/code summaries still run in greenfield.
        assertFalse(CapabilityOrchestrator.requiresLiveCluster(
                "toolkit-llm-call", Map.of("prompt_template", "deepwiki_dependency_notes")));
    }
}
