package ntou.soselab.chatops4msa.Service.NLPService;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAI embeddings endpoint, on its own so it can be used without the rest of
 * {@link LLMService} — by the report Q&amp;A at runtime, and by the semantic router's
 * calibration run, which needs nothing but a key.
 *
 * {@link #embed(List)} returns {@code null} on any failure rather than throwing: every
 * caller has a lexical fallback, and a missing vector is a degradation, not an error.
 */
public class EmbeddingClient {

    private static final int BATCH = 64;

    private final String url;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(String url, String apiKey, String model) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Derives the embeddings URL from a chat-completions URL of the same server. */
    public static String deriveUrl(String chatUrl) {
        return chatUrl == null ? "" : chatUrl.replace("/chat/completions", "/embeddings");
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && url != null && !url.isBlank();
    }

    /** One vector per input, in order; {@code null} on any failure. */
    public List<double[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (!isConfigured()) return null;
        try {
            List<double[]> out = new ArrayList<>();
            for (int from = 0; from < texts.size(); from += BATCH) {
                List<double[]> vectors = embedBatch(texts.subList(from, Math.min(texts.size(), from + BATCH)));
                if (vectors == null) return null;
                out.addAll(vectors);
            }
            return out;
        } catch (Exception e) {
            System.out.println("[WARNING] embeddings call failed: " + e.getMessage());
            return null;
        }
    }

    private List<double[]> embedBatch(List<String> batch) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(60_000);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        JSONArray input = new JSONArray();
        for (String t : batch) input.put(t == null || t.isBlank() ? " " : t);
        JSONObject body = new JSONObject().put("model", model).put("input", input);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
        JSONObject json = new JSONObject(response.getBody());
        JSONArray data = json.optJSONArray("data");
        if (data == null || data.length() != batch.size()) return null;

        double[][] vectors = new double[batch.size()][];
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            int index = item.optInt("index", i);
            JSONArray values = item.getJSONArray("embedding");
            double[] v = new double[values.length()];
            for (int k = 0; k < values.length(); k++) v[k] = values.getDouble(k);
            if (index >= 0 && index < vectors.length) vectors[index] = v;
        }
        List<double[]> out = new ArrayList<>();
        for (double[] v : vectors) {
            if (v == null) return null;
            out.add(v);
        }
        if (json.has("usage")) {
            System.out.println("[Used Token] embeddings " + json.getJSONObject("usage").optInt("total_tokens", 0));
        }
        return out;
    }
}
