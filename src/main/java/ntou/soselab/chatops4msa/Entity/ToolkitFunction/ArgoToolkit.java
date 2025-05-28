package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Iterator;

@Component
public class ArgoToolkit extends ToolkitFunction {

    private final OkHttpClient client = getUnsafeClient();
    private final String baseUrl = "https://argo-server.argo.svc.cluster.local:2746/api/v1/workflows/";

    // 取得指定 namespace 下的 workflow 清單
    public String toolkitArgoWorkflows(String namespace) throws IOException {
        String url = baseUrl + namespace;
        StringBuilder result = new StringBuilder();

        Request request = new Request.Builder()
                .url(url)
                .method("GET", null)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String json = response.body().string();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(json);

                for (JsonNode item : root.get("items")) {
                    String name = item.at("/metadata/name").asText();
                    String phase = item.at("/status/phase").asText();
                    String time = item.at("/metadata/creationTimestamp").asText();
                    result.append(String.format("- %s | %s | %s%n", name, phase, time));
                }
            } else {
                result.append("Failed to get workflow list: ").append(response.code());
            }
        }

        return result.toString().replace("\n", "\\n");// 回傳給其他 toolkit 使用
    }

    public String toolkitArgoWorkflowDetail(String namespace, String workflow_name) throws IOException {
        String url = baseUrl + namespace + "/" + workflow_name;
        StringBuilder result = new StringBuilder();

        Request request = new Request.Builder()
                .url(url)
                .method("GET", null)
                .addHeader("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String json = response.body().string();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(json);

                // Metadata
                String name = root.at("/metadata/name").asText();
                String status = root.at("/status/phase").asText();
                String createdAt = root.at("/metadata/creationTimestamp").asText();
                String startedAt = root.at("/status/startedAt").asText();
                String finishedAt = root.at("/status/finishedAt").asText();
                String template = root.at("/spec/workflowTemplateRef/name").asText();

                result.append("Workflow Information\n");
                result.append(String.format("Name       : %s\n", name));
                result.append(String.format("Status     : %s\n", status));
                result.append(String.format("Created At : %s\n", createdAt));
                result.append(String.format("Started At : %s\n", startedAt));
                result.append(String.format("Finished At: %s\n", finishedAt));
                result.append(String.format("Template   : %s\n", template));

                // Parameters
                JsonNode params = root.at("/spec/arguments/parameters");
                if (params.isArray() && params.size() > 0) {
                    result.append("\nWorkflow Parameters:\n");
                    for (JsonNode param : params) {
                        String paramName = param.get("name").asText();
                        String paramValue = param.get("value").asText();
                        result.append(String.format(" - %s = %s\n", paramName, paramValue));
                    }
                }

                // Steps
                JsonNode nodes = root.at("/status/nodes");
                result.append("\nStep Details:\n");

                Iterator<String> fieldNames = nodes.fieldNames();
                while (fieldNames.hasNext()) {
                    String nodeId = fieldNames.next();
                    JsonNode node = nodes.get(nodeId);
                    if (node.get("type").asText().equals("Pod")) {
                        String stepName = node.get("displayName").asText();
                        String stepPhase = node.get("phase").asText();
                        String stepStart = node.get("startedAt").asText();
                        String stepFinish = node.get("finishedAt").asText();
                        int cpu = node.at("/resourcesDuration/cpu").asInt(0);
                        int memory = node.at("/resourcesDuration/memory").asInt(0);

                        result.append(String.format("Step Name   : %s\n", stepName));
                        result.append(String.format("  Status    : %s\n", stepPhase));
                        result.append(String.format("  Start     : %s\n", stepStart));
                        result.append(String.format("  Finish    : %s\n", stepFinish));
                        result.append(String.format("  CPU       : %d\n", cpu));
                        result.append(String.format("  Memory    : %d Mi\n", memory));

                        // Artifacts
                        JsonNode artifacts = node.at("/outputs/artifacts");
                        if (artifacts.isArray() && artifacts.size() > 0) {
                            result.append("  Artifacts :\n");
                            for (JsonNode artifact : artifacts) {
                                String key = artifact.at("/s3/key").asText();
                                result.append(String.format("    - %s\n", key));
                            }
                        }
                        result.append("\n");
                    }
                }
            } else {
                result.append("Failed to get workflow detail: ").append(response.code());
            }
        }

        return result.toString();
    }

    // 忽略 HTTPS 憑證用（對應 curl --insecure）
    private OkHttpClient getUnsafeClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
