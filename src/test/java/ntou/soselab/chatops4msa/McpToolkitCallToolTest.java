package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Entity.ToolkitFunction.McpToolkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolkitCallToolTest {

    @Test
    void connect_then_call_execute_kubectl() {
        McpToolkit tk = new McpToolkit();

        String r1 = tk.toolkitMcpConnect("k8s");
        System.out.println(r1);
        assertTrue(r1.contains("MCP connected") || r1.contains("already connected"));

        String r2 = tk.toolkitMcpListTools("k8s");
        System.out.println(r2);

        String out = tk.toolkitMcpCallTool("k8s", "execute_kubectl", "{\"command\":\"get ns\"}");
        System.out.println(out);

        assertTrue(out.contains("default") || out.contains("kube-system"));

        String r3 = tk.toolkitMcpDisconnect("k8s");
        System.out.println(r3);
        assertTrue(r3.contains("disconnected") || r3.contains("not found"));
    }
}