package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

/**
 * The chat call an experiment needs, without a Spring context: the same endpoint, model
 * and {@code temperature=0} as {@link ntou.soselab.chatops4msa.Service.NLPService.LLMService
 * #callAPIFromOutside}, read from {@code application.properties}.
 *
 * Not a test (the name matches no surefire pattern).
 */
final class ChatCompletions {

    private final String url;
    private final String key;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private int calls;
    private int promptTokens;
    private int completionTokens;

    private ChatCompletions(String url, String key, String model) {
        this.url = url;
        this.key = key;
        this.model = model;
    }

    /** null when there is no key: the caller says so and skips rather than failing. */
    static ChatCompletions fromOrNull(Properties p) {
        String key = p.getProperty("openai.api.key", "");
        if (key.isBlank()) return null;
        return new ChatCompletions(p.getProperty("openai.api.url", "https://api.openai.com/v1/chat/completions"),
                key, p.getProperty("openai.api.model", "gpt-3.5-turbo"));
    }

    String model() {
        return model;
    }

    int calls() {
        return calls;
    }

    int promptTokens() {
        return promptTokens;
    }

    int completionTokens() {
        return completionTokens;
    }

    String ask(String system, String user) throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject().put("model", model).put("temperature", 0).put("messages", messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("chat " + response.statusCode() + ": " + response.body());
        JSONObject json = new JSONObject(response.body());
        calls++;
        if (json.has("usage")) {
            promptTokens += json.getJSONObject("usage").optInt("prompt_tokens");
            completionTokens += json.getJSONObject("usage").optInt("completion_tokens");
        }
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }
}
