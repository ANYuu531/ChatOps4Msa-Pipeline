package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

@Component
public class McpToolkit extends ToolkitFunction {

    private final Map<String, McpSyncClient> sessions = new ConcurrentHashMap<>();
    private final Map<String, Path> tempKubeconfigs = new ConcurrentHashMap<>();
    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private String error(String msg) {
        return generateFunctionErrorMessage() + " " + msg;
    }

    public String toolkitMcpConnect(String server_name) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }

            if (sessions.containsKey(server_name)) {
                return "MCP session already connected: " + server_name;
            }

            Path kubeConfig = Path.of(System.getProperty("user.home"), ".kube", "config");
            if (!Files.exists(kubeConfig)) {
                return error("~/.kube/config not found. Please configure kubectl context first.");
            }
            String raw = Files.readString(kubeConfig);

            String patched = raw
                    .replaceAll("server: https://127\\.0\\.0\\.1:\\d+", "server: https://mcp-control-plane:6443")
                    .replaceAll("server: https://localhost:\\d+", "server: https://mcp-control-plane:6443");

            Path tmp = Files.createTempFile("kubeconfig-mcp-", ".yaml");
            Files.writeString(tmp, patched);
            tempKubeconfigs.put(server_name, tmp);

            StdioClientTransport transport = new StdioClientTransport(
                    ServerParameters.builder("docker")
                            .args(
                                    "run",
                                    "-i",
                                    "--rm",
                                    "--network", "kind",
                                    "-v", tmp.toAbsolutePath() + ":/home/appuser/.kube/config:ro",
                                    "-e", "KUBECONFIG=/home/appuser/.kube/config",
                                    "ghcr.io/alexei-led/k8s-mcp-server:latest"
                            )
                            .build()
            );

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();

            client.initialize();
            sessions.put(server_name, client);

            return "MCP connected: " + server_name;

        } catch (Exception e) {
            return error("MCP connect failed: " + e.getMessage());
        }
    }

    public String toolkitMcpListTools(String server_name) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }

            McpSyncClient client = sessions.get(server_name);
            if (client == null) {
                return error("MCP session not connected: " + server_name);
            }

            var tools = client.listTools();
            return tools.toString();

        } catch (Exception e) {
            return error("MCP list tools failed: " + e.getMessage());
        }
    }

    public String toolkitMcpCallTool(String server_name, String tool_name, String tool_arguments) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }
            if (tool_name == null || tool_name.isBlank()) {
                return error("tool_name is required");
            }
            if (tool_arguments == null || tool_arguments.isBlank()) {
                tool_arguments = "{}";
            }

            McpSyncClient client = sessions.get(server_name);
            if (client == null) {
                return error("MCP session not connected: " + server_name);
            }

            Map<String, Object> args = OM.readValue(tool_arguments, MAP_TYPE);

            McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(tool_name, args);
            McpSchema.CallToolResult result = client.callTool(req);

            return result.toString();

        } catch (Exception e) {
            return error("MCP call tool failed: " + e.getMessage());
        }
    }

    public String toolkitMcpDisconnect(String server_name) {
        try {
            McpSyncClient client = sessions.remove(server_name);
            if (client == null) {
                return "MCP session not found: " + server_name;
            }
            client.closeGracefully();
            Path tmp = tempKubeconfigs.remove(server_name);
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
            return "MCP disconnected: " + server_name;
        } catch (Exception e) {
            return error("MCP disconnect failed: " + e.getMessage());
        }
    }
}