package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

@Component
public class McpToolkit extends ToolkitFunction {

    private final Map<String, McpSyncClient> sessions = new ConcurrentHashMap<>();

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private String error(String msg) {
        return generateFunctionErrorMessage() + " " + msg;
    }

    /**
     * Backward compatible version.
     * Uses environment variables:
     * MCP_SERVER_BASE_URL
     * MCP_SERVER_ENDPOINT
     */
    public String toolkitMcpConnect(String server_name) {
        return toolkitMcpConnect(server_name, null, null);
    }

    /**
     * Connect to selected MCP server.
     *
     * Example:
     * server_name = "k8s"
     * base_url = "http://k8s-mcp-server:8000"
     * endpoint = "/mcp"
     *
     * DeepWiki:
     * server_name = "deepwiki"
     * base_url = "https://mcp.deepwiki.com"
     * endpoint = "/mcp"
     */
    public String toolkitMcpConnect(String server_name, String base_url, String endpoint) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }

            if (sessions.containsKey(server_name)) {
                return "MCP session already connected: " + server_name;
            }

            if (base_url == null || base_url.isBlank()) {
                base_url = System.getenv().getOrDefault(
                        "MCP_SERVER_BASE_URL",
                        "http://k8s-mcp-server:8000"
                );
            }

            if (endpoint == null || endpoint.isBlank()) {
                endpoint = System.getenv().getOrDefault(
                        "MCP_SERVER_ENDPOINT",
                        "/mcp"
                );
            }

            if (!endpoint.startsWith("/")) {
                endpoint = "/" + endpoint;
            }

            var transport = HttpClientStreamableHttpTransport
                    .builder(base_url)
                    .endpoint(endpoint)
                    .build();

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();

            client.initialize();
            sessions.put(server_name, client);

            return "MCP connected: " + server_name + " -> " + base_url + endpoint;

        } catch (Exception e) {
            return error("MCP connect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
            return error("MCP list tools failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
            return error("MCP call tool failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public String toolkitMcpDisconnect(String server_name) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }

            McpSyncClient client = sessions.remove(server_name);
            if (client == null) {
                return "MCP session not found: " + server_name;
            }

            try {
                client.closeGracefully();
            } catch (Exception ignore) {
            }

            return "MCP disconnected: " + server_name;

        } catch (Exception e) {
            return error("MCP disconnect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}