package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
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

    public String toolkitMcpConnect(String server_name) {
        return toolkitMcpConnect(server_name, null, null);
    }

   public String toolkitMcpConnect(

            String server_name,

            String base_url,

            String endpoint

    ) {

        try {

            if (server_name == null || server_name.isBlank()) {

                return error("server_name is required");

            }

            if (sessions.containsKey(server_name)) {

                return """

                        ⚠️ MCP session already connected

                        Server:

                        `%s`

                        """.formatted(server_name);

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

            // 防止 base_url 已經帶 /mcp

            if (base_url.endsWith("/mcp")) {

                base_url = base_url.substring(0, base_url.length() - 4);

                endpoint = "/mcp";

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

            return """

                    ## MCP connected

                    Server:

                    `%s`

                    Endpoint:

                    `%s%s`

                    """.formatted(server_name, base_url, endpoint);

        } catch (Exception e) {

            StringBuilder sb = new StringBuilder();

            sb.append(error("MCP connect failed: "))

                    .append(e.getClass().getName())

                    .append(": ")

                    .append(e.getMessage());

            Throwable cause = e.getCause();

            while (cause != null) {

                sb.append("\nCaused by: ")

                        .append(cause.getClass().getName())

                        .append(": ")

                        .append(cause.getMessage());

                cause = cause.getCause();

            }

            return sb.toString();

        }

    }

    public String toolkitMcpListSessions() {
        try {
            if (sessions.isEmpty()) {
                return "No MCP sessions connected.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Connected MCP Sessions\n\n");

            for (String name : sessions.keySet()) {
                sb.append("- `").append(name).append("`\n");
            }

            return sb.toString();

        } catch (Exception e) {
            return error("MCP list sessions failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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

            McpSchema.ListToolsResult result = client.listTools();
            List<McpSchema.Tool> tools = result.tools();

            if (tools == null || tools.isEmpty()) {
                return "No tools available for MCP server: `" + server_name + "`";
            }

            StringBuilder sb = new StringBuilder();

            sb.append("## MCP 可用工具\n\n");
            sb.append("**Server:** `").append(server_name).append("`\n\n");
            sb.append("**Tools:** ").append(tools.size()).append("\n\n");
            sb.append("---\n\n");

            for (int i = 0; i < tools.size(); i++) {
                McpSchema.Tool tool = tools.get(i);

                sb.append("### ").append(i + 1).append(". `")
                        .append(tool.name())
                        .append("`\n\n");

                if (tool.description() != null && !tool.description().isBlank()) {
                    sb.append(tool.description()).append("\n\n");
                }

                sb.append("**參數:**\n");

                Object inputSchema = tool.inputSchema();

                try {
                    Map<String, Object> schemaMap = OM.convertValue(inputSchema, MAP_TYPE);

                    Object propertiesObj = schemaMap.get("properties");
                    Object requiredObj = schemaMap.get("required");

                    List<String> required = requiredObj instanceof List<?>
                            ? ((List<?>) requiredObj).stream().map(String::valueOf).toList()
                            : List.of();

                    if (propertiesObj instanceof Map<?, ?> properties && !properties.isEmpty()) {
                        for (Map.Entry<?, ?> entry : properties.entrySet()) {
                            String paramName = String.valueOf(entry.getKey());
                            String requiredMark = required.contains(paramName) ? "必填" : "選填";

                            String type = "unknown";
                            Object value = entry.getValue();

                            if (value instanceof Map<?, ?> paramSchema) {
                                Object typeObj = paramSchema.get("type");
                                Object anyOfObj = paramSchema.get("anyOf");

                                if (typeObj != null) {
                                    type = String.valueOf(typeObj);
                                } else if (anyOfObj instanceof List<?> anyOfList) {
                                    type = anyOfList.stream()
                                            .map(item -> {
                                                if (item instanceof Map<?, ?> itemMap) {
                                                    Object t = itemMap.get("type");
                                                    return t == null ? "unknown" : String.valueOf(t);
                                                }
                                                return "unknown";
                                            })
                                            .distinct()
                                            .reduce((a, b) -> a + " / " + b)
                                            .orElse("unknown");
                                }
                            }

                            sb.append("- `")
                                    .append(paramName)
                                    .append("` ")
                                    .append("(")
                                    .append(type)
                                    .append(") ")
                                    .append(requiredMark)
                                    .append("\n");
                        }
                    } else {
                        sb.append("- 無參數\n");
                    }

                } catch (Exception schemaParseError) {
                    sb.append("- 無法解析參數 schema\n");
                }

                sb.append("\n");
            }

            return sb.toString();

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

            return """
                    ## MCP Tool 執行結果

                    **Server:** `%s`
                    **Tool:** `%s`

                    ```text
                    %s
                    ```
                    """.formatted(server_name, tool_name, result.toString());

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
                return "MCP session not found: `" + server_name + "`";
            }

            try {
                client.closeGracefully();
            } catch (Exception ignore) {
            }

            return "MCP disconnected: `" + server_name + "`";

        } catch (Exception e) {
            return error("MCP disconnect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}