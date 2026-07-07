package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
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
    // remember each server's connection params so a dead session can be re-established
    private final Map<String, String[]> endpoints = new ConcurrentHashMap<>();

    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private String error(String msg) {
        return generateFunctionErrorMessage() + " " + msg;
    }

    private String fullError(String prefix, Exception e) {
        StringBuilder sb = new StringBuilder();

        sb.append(error(prefix))
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

    private String prettyJson(String json) {
        try {
            Object obj = OM.readValue(json, Object.class);
            return OM.writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private boolean isFullUrl(String value) {
        return value != null &&
                (value.startsWith("http://") || value.startsWith("https://"));
    }

    /**
     * Build + initialize an MCP client, retrying because these servers
     * (e.g. k8s-mcp-server) periodically exit/restart.
     */
    private McpSyncClient establishClient(String base_url, String endpoint) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            McpSyncClient client = null;
            try {
                var transport = HttpClientStreamableHttpTransport
                        .builder(base_url)
                        .endpoint(endpoint)
                        .build();
                client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(30))
                        .build();
                client.initialize();
                return client;
            } catch (Exception e) {
                lastError = e;
                if (client != null) {
                    try { client.close(); } catch (Exception ignored) {}
                }
                if (attempt < 3) {
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw lastError != null ? lastError : new RuntimeException("MCP connect failed");
    }

    /**
     * Re-establish a dead session (the server crashed/restarted mid-use) using the
     * stored connection params. Returns the new client, or null if it can't reconnect.
     */
    private McpSyncClient reconnect(String server_name) {
        String[] params = endpoints.get(server_name);
        if (params == null) return null;
        McpSyncClient old = sessions.remove(server_name);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
        try {
            McpSyncClient client = establishClient(params[0], params[1]);
            sessions.put(server_name, client);
            return client;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] normalizeMcpUrl(String baseUrl, String endpoint) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = System.getenv().getOrDefault(
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

        /*
         * Case 1:
         * baseUrl = https://mcp.deepwiki.com
         * endpoint = https://mcp.deepwiki.com/mcp
         */
        if (isFullUrl(endpoint)) {
            URI uri = URI.create(endpoint);

            String fixedBaseUrl = uri.getScheme() + "://" + uri.getHost();

            if (uri.getPort() != -1) {
                fixedBaseUrl += ":" + uri.getPort();
            }

            baseUrl = fixedBaseUrl;

            String path = uri.getPath();
            endpoint = (path == null || path.isBlank() || path.equals("/"))
                    ? "/mcp"
                    : path;
        }

        /*
         * Case 2:
         * baseUrl = https://mcp.deepwiki.com/mcp
         * endpoint = /mcp
         */
        if (isFullUrl(baseUrl)) {
            URI uri = URI.create(baseUrl);

            String path = uri.getPath();

            if (path != null && !path.isBlank() && !path.equals("/")) {
                endpoint = path;

                String fixedBaseUrl = uri.getScheme() + "://" + uri.getHost();

                if (uri.getPort() != -1) {
                    fixedBaseUrl += ":" + uri.getPort();
                }

                baseUrl = fixedBaseUrl;
            }
        }

        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        return new String[] { baseUrl, endpoint };
    }

    private String extractCallToolResultText(McpSchema.CallToolResult result) {
        if (result == null) {
            return "";
        }

        Object structured = result.structuredContent();

        if (structured instanceof Map<?, ?>) {
            Map<?, ?> structuredContent = (Map<?, ?>) structured;
            Object value = structuredContent.get("result");

            if (value != null) {
                return String.valueOf(value).trim();
            }
        }

        if (result.content() != null && !result.content().isEmpty()) {
            StringBuilder sb = new StringBuilder();

            for (Object content : result.content()) {
                if (content instanceof McpSchema.TextContent) {
                    McpSchema.TextContent textContent =
                            (McpSchema.TextContent) content;

                    sb.append(textContent.text()).append("\n");
                } else if (content != null) {
                    sb.append(content).append("\n");
                }
            }

            return sb.toString().trim();
        }

        return result.toString();
    }

    private String getTypeFromSchema(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return "unknown";
        }

        Map<?, ?> paramSchema = (Map<?, ?>) value;

        Object typeObj = paramSchema.get("type");
        Object anyOfObj = paramSchema.get("anyOf");

        if (typeObj != null) {
            return String.valueOf(typeObj);
        }

        if (anyOfObj instanceof List<?>) {
            List<?> anyOfList = (List<?>) anyOfObj;
            StringBuilder sb = new StringBuilder();

            for (Object item : anyOfList) {
                String type = "unknown";

                if (item instanceof Map<?, ?>) {
                    Map<?, ?> itemMap = (Map<?, ?>) item;
                    Object t = itemMap.get("type");

                    if (t != null) {
                        type = String.valueOf(t);
                    }
                }

                if (sb.indexOf(type) == -1) {
                    if (sb.length() > 0) {
                        sb.append(" / ");
                    }

                    sb.append(type);
                }
            }

            return sb.length() == 0 ? "unknown" : sb.toString();
        }

        return "unknown";
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
                        MCP session already connected

                        Server:
                        `%s`
                        """.formatted(server_name);
            }

            String[] normalized = normalizeMcpUrl(base_url, endpoint);
            base_url = normalized[0];
            endpoint = normalized[1];

            try {
                McpSyncClient client = establishClient(base_url, endpoint);
                sessions.put(server_name, client);
                endpoints.put(server_name, new String[]{base_url, endpoint});
            } catch (Exception e) {
                return fullError("MCP connect failed after 3 attempts: ", e);
            }

            return """
                    MCP connected

                    Server:
                    `%s`

                    Endpoint:
                    `%s%s`
                    """.formatted(server_name, base_url, endpoint);

        } catch (Exception e) {
            return fullError("MCP connect failed: ", e);
        }
    }

    public String toolkitMcpListSessions() {
        try {
            if (sessions.isEmpty()) {
                return "No MCP sessions connected.";
            }

            StringBuilder sb = new StringBuilder();

            sb.append("MCP connected sessions\n\n");

            for (String name : sessions.keySet()) {
                sb.append("- `").append(name).append("`\n");
            }

            return sb.toString();

        } catch (Exception e) {
            return fullError("MCP list sessions failed: ", e);
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
                return """
                        No tools available

                        Server:
                        `%s`
                        """.formatted(server_name);
            }

            StringBuilder sb = new StringBuilder();

            sb.append("MCP available tools\n\n");
            sb.append("Server:\n");
            sb.append("`").append(server_name).append("`\n\n");
            sb.append("Tools:\n");
            sb.append(tools.size()).append("\n\n");
            sb.append("---\n\n");

            for (int i = 0; i < tools.size(); i++) {
                McpSchema.Tool tool = tools.get(i);

                sb.append(i + 1)
                        .append(". `")
                        .append(tool.name())
                        .append("`\n\n");

                if (tool.description() != null && !tool.description().isBlank()) {
                    sb.append(tool.description()).append("\n\n");
                }

                sb.append("Parameters:\n");

                try {
                    Map<String, Object> schemaMap =
                            OM.convertValue(tool.inputSchema(), MAP_TYPE);

                    Object propertiesObj = schemaMap.get("properties");
                    Object requiredObj = schemaMap.get("required");

                    List<String> required;

                    if (requiredObj instanceof List<?>) {
                        required = ((List<?>) requiredObj)
                                .stream()
                                .map(String::valueOf)
                                .toList();
                    } else {
                        required = List.of();
                    }

                    if (propertiesObj instanceof Map<?, ?>) {
                        Map<?, ?> properties = (Map<?, ?>) propertiesObj;

                        if (properties.isEmpty()) {
                            sb.append("- none\n");
                        } else {
                            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                                String paramName = String.valueOf(entry.getKey());

                                String requiredMark =
                                        required.contains(paramName)
                                                ? "required"
                                                : "optional";

                                String type = getTypeFromSchema(entry.getValue());

                                sb.append("- `")
                                        .append(paramName)
                                        .append("` ")
                                        .append("(")
                                        .append(type)
                                        .append(") ")
                                        .append(requiredMark)
                                        .append("\n");
                            }
                        }

                    } else {
                        sb.append("- none\n");
                    }

                } catch (Exception ignored) {
                    sb.append("- unable to parse input schema\n");
                }

                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            return fullError("MCP list tools failed: ", e);
        }
    }

    public String toolkitMcpCallTool(
            String server_name,
            String tool_name,
            String tool_arguments
    ) {
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
                // session was never established or dropped; try to (re)connect
                client = reconnect(server_name);
                if (client == null) return error("MCP session not connected: " + server_name);
            }

            Map<String, Object> args = OM.readValue(tool_arguments, MAP_TYPE);

            McpSchema.CallToolRequest req =
                    new McpSchema.CallToolRequest(tool_name, args);

            Thread.interrupted(); // clear any stale interrupt from MCP session init
            McpSchema.CallToolResult result;
            try {
                result = client.callTool(req);
            } catch (Exception callError) {
                // the server likely crashed/restarted mid-session; reconnect once and retry
                McpSyncClient fresh = reconnect(server_name);
                if (fresh == null) {
                    return fullError("MCP call tool failed (session terminated, reconnect failed): ", callError);
                }
                Thread.interrupted();
                result = fresh.callTool(req);
            }

            String resultText = extractCallToolResultText(result);

            return """
                    MCP tool result

                    Server:
                    `%s`

                    Tool:
                    `%s`

                    Arguments:
                    ```json
                    %s
                    ```

                    Result:
                    ```text
                    %s
                    ```
                    """.formatted(
                    server_name,
                    tool_name,
                    prettyJson(tool_arguments),
                    resultText
            );

        } catch (Exception e) {
            return fullError("MCP call tool failed: ", e);
        }
    }

    public String toolkitMcpDisconnect(String server_name) {
        try {
            if (server_name == null || server_name.isBlank()) {
                return error("server_name is required");
            }

            McpSyncClient client = sessions.remove(server_name);

            if (client == null) {
                return """
                        MCP session not found

                        Server:
                        `%s`
                        """.formatted(server_name);
            }

            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }

            return """
                    MCP disconnected

                    Server:
                    `%s`
                    """.formatted(server_name);

        } catch (Exception e) {
            return fullError("MCP disconnect failed: ", e);
        }
    }
}