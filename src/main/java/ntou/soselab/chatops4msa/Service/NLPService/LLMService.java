package ntou.soselab.chatops4msa.Service.NLPService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.gson.JsonParseException;
import ntou.soselab.chatops4msa.Service.LowCodeService.CapabilityConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class LLMService {

    private final String OPENAI_API_URL;
    private final String OPENAI_API_KEY;
    private final String OPENAI_API_MODEL;
    private final String EMBEDDING_MODEL;
    private final String EMBEDDING_URL;

    private final String PROMPT_INJECTION_DETECTION_FILE;
    private final String END_OF_TOPIC_FILE;
    private final String INTENT_CLASSIFICATION_AND_ENTITY_EXTRACTION_FILE;
    private final String QUERYING_MISSING_PARAMETERS_FILE;

    private final CapabilityGenerator capabilityGenerator;
    private final CapabilityConfigLoader configLoader;

    @Autowired
    public LLMService(Environment env, CapabilityGenerator capabilityGenerator, CapabilityConfigLoader configLoader) {
        this.OPENAI_API_URL = env.getProperty("openai.api.url");
        this.OPENAI_API_KEY = env.getProperty("openai.api.key");
        this.OPENAI_API_MODEL = env.getProperty("openai.api.model");
        this.EMBEDDING_MODEL = env.getProperty("openai.api.embedding-model", "text-embedding-3-small");
        String embeddingUrl = env.getProperty("openai.api.embedding-url");
        if (embeddingUrl == null || embeddingUrl.isBlank()) {
            embeddingUrl = OPENAI_API_URL == null ? "" : OPENAI_API_URL.replace("/chat/completions", "/embeddings");
        }
        this.EMBEDDING_URL = embeddingUrl;

        this.PROMPT_INJECTION_DETECTION_FILE = loadSystemPrompt(env.getProperty("prompts.prompt_injection_detection.file"));
        this.END_OF_TOPIC_FILE = loadSystemPrompt(env.getProperty("prompts.end_of_topic.file"));
        String intentClassificationAndEntityExtractionFile = loadSystemPrompt(env.getProperty("prompts.intent_classification_and_entity_extraction.file"));
        String capabilityListString = capabilityGenerator.getCapabilityListString();
        this.INTENT_CLASSIFICATION_AND_ENTITY_EXTRACTION_FILE = intentClassificationAndEntityExtractionFile.replace("<CAPABILITY_LIST>", capabilityListString);
//        System.err.println(INTENT_CLASSIFICATION_AND_ENTITY_EXTRACTION_FILE);
        this.QUERYING_MISSING_PARAMETERS_FILE = loadSystemPrompt(env.getProperty("prompts.querying_missing_parameters.file"));

        this.capabilityGenerator = capabilityGenerator;
        this.configLoader = configLoader;
    }

    public boolean isPromptInjection(String userPrompt) {
        System.out.println();
        System.out.println("[DEBUG] trigger isPromptInjection()");
        System.out.println("[User Prompt] " + userPrompt);

        String completion = inference(PROMPT_INJECTION_DETECTION_FILE, userPrompt);

        boolean isPromptInjection = completion.contains("true");
        System.out.println("[Is Prompt Injection?] " + isPromptInjection);
        return isPromptInjection;
    }

    public boolean isEndOfTopic(String userPrompt) {
        System.out.println();
        System.out.println("[DEBUG] trigger isEndOfTopic()");
        System.out.println("[User Prompt] " + userPrompt);

        String completion = inference(END_OF_TOPIC_FILE, userPrompt);

        boolean isEndOfTopic = completion.contains("true");
        System.out.println("[Is End Of Topic?] " + isEndOfTopic);
        return isEndOfTopic;
    }

    /**
     * @param userPrompt like "I would like to make a reservation at Noblesse Seafood Restaurant - Ocean University Branch and also book a flight at 10 a.m."
     * @return like {"restaurant_ordering": {"name_of_restaurant": "Noblesse Seafood Restaurant - Ocean University Branch"}, "flight_ticket_booking": {"time": "10 a.m."}}
     * @throws JsonProcessingException Before LLM -> yaml to json string exception
     * @throws JsonParseException      After LLM -> json string to JSONObject exception
     */
    public JSONObject classifyIntentAndExtractEntity(String previousIntentAndEntities, String userPrompt) throws JsonProcessingException, JsonParseException {
        System.out.println();
        System.out.println("[DEBUG] trigger classifyIntentAndExtractEntity()");
        System.out.println("[User Prompt] " + userPrompt);

        StringBuilder sb = new StringBuilder();
        for (String serviceName : configLoader.getAllServiceNameList()) {
            sb.append(serviceName).append(", ");
        }
        String systemPrompt = INTENT_CLASSIFICATION_AND_ENTITY_EXTRACTION_FILE.replace("<SERVICE_LIST>", sb.toString());

        String completion = inference(systemPrompt, previousIntentAndEntities, userPrompt);

        System.out.println("[Completion String] " + completion);

        int startIndex = completion.indexOf("{");
        int endIndex = completion.lastIndexOf("}");
        completion = completion.substring(startIndex, endIndex + 1);

        JSONObject completionJSON = new JSONObject(completion);
        System.out.println("[Completion JSON] " + completionJSON);
        return completionJSON;
    }

    /**
     * @throws JSONException Before LLM -> yaml to JSONObject exception
     */
    public String queryMissingParameter(String intentName, Map<String, String> providedEntities) throws JSONException {
        System.out.println();
        System.out.println("[DEBUG] trigger queryMissingParameter()");

        String systemPrompt = QUERYING_MISSING_PARAMETERS_FILE.replace("<INTENT_NAME>", intentName);

        StringBuilder providedEntityDescription = new StringBuilder();
        StringBuilder missingEntityDescription = new StringBuilder();
        List<String> entityNameList = capabilityGenerator.getParameterList(intentName);
        for (String entityName : entityNameList) {
            String providedEntityValue = providedEntities.get(entityName);
            if (providedEntityValue != null) {
                providedEntityDescription.append("\"").append(entityName).append("\" as ");
                providedEntityDescription.append("\"").append(providedEntityValue).append("\", ");
            } else {
                missingEntityDescription.append("\"").append(entityName).append("\", ");
            }
        }

        systemPrompt = systemPrompt.replace("<PROVIDED_ENTITY_DESCRIPTION>", providedEntityDescription.toString());
        systemPrompt = systemPrompt.replace("<MISSING_ENTITY_DESCRIPTION>", missingEntityDescription.toString());
        System.out.println("[System Prompt] " + systemPrompt);

        return inference(systemPrompt);
    }

    private String inference(String systemPrompt, String userPrompt) {
        JSONArray allMessages = new JSONArray();
        allMessages = putSystemMessage(allMessages, systemPrompt);
        allMessages = putUserMessage(allMessages, userPrompt);
        return callAPI(allMessages);
    }

    private String inference(String systemPrompt, String previousIntentAndEntities, String userPrompt) {
        JSONArray allMessages = new JSONArray();
        allMessages = putSystemMessage(allMessages, systemPrompt);
        allMessages = putUserMessage(allMessages, previousIntentAndEntities);
        allMessages = putUserMessage(allMessages, userPrompt);
        return callAPI(allMessages);
    }

    private String inference(String systemPrompt) {
        JSONArray allMessages = new JSONArray();
        allMessages = putSystemMessage(allMessages, systemPrompt);
        return callAPI(allMessages);
    }

    private JSONArray putSystemMessage(JSONArray allMessages, String systemPrompt) {
        JSONObject systemMessage = new JSONObject();
        try {
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return allMessages.put(systemMessage);
    }

    private JSONArray putUserMessage(JSONArray allMessages, String userPrompt) {
        JSONObject userMessage = new JSONObject();
        try {
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return allMessages.put(userMessage);
    }

    private String callAPI(JSONArray promptMessages) {
        RestTemplate restTemplate = newRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + OPENAI_API_KEY);

        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("model", OPENAI_API_MODEL);
            requestBody.put("temperature", 0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        try {
            requestBody.put("messages", promptMessages);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody.toString(), headers);

        ResponseEntity<String> responseEntity = restTemplate.exchange(OPENAI_API_URL, HttpMethod.POST, requestEntity, String.class);
        String completionString;
        try {
            JSONObject completionJSON = new JSONObject(responseEntity.getBody());
            int usedTokens = completionJSON.getJSONObject("usage").getInt("total_tokens");
            System.out.println("[Used Token] " + usedTokens);
            completionString = completionJSON
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return completionString;
    }
    public String callAPIFromOutside(JSONArray promptMessages) {
        RestTemplate restTemplate = newRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + OPENAI_API_KEY);

        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("model", OPENAI_API_MODEL);
            requestBody.put("temperature", 0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        try {
            requestBody.put("messages", promptMessages);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody.toString(), headers);

        ResponseEntity<String> responseEntity = restTemplate.exchange(OPENAI_API_URL, HttpMethod.POST, requestEntity, String.class);
        String completionString;
        try {
            JSONObject completionJSON = new JSONObject(responseEntity.getBody());
            int usedTokens = completionJSON.getJSONObject("usage").getInt("total_tokens");
            System.out.println("[Used Token] " + usedTokens);
            completionString = completionJSON
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return completionString;
    }
    /**
     * Embeds texts with the OpenAI embeddings endpoint, for the report Q&amp;A's semantic
     * retrieval.
     *
     * The endpoint is derived from the chat URL ({@code .../chat/completions} →
     * {@code .../embeddings}) unless {@code openai.api.embedding-url} is set, so a
     * proxy or compatible server that serves both keeps working with one setting.
     *
     * @return one vector per input, in order; {@code null} on any failure (no key, network,
     *         an endpoint that does not serve embeddings) — the caller then retrieves
     *         lexically, which is a degradation rather than an error.
     */
    public List<double[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) return null;
        try {
            List<double[]> out = new java.util.ArrayList<>();
            for (int from = 0; from < texts.size(); from += EMBED_BATCH) {
                List<String> batch = texts.subList(from, Math.min(texts.size(), from + EMBED_BATCH));
                List<double[]> vectors = embedBatch(batch);
                if (vectors == null) return null;
                out.addAll(vectors);
            }
            return out;
        } catch (Exception e) {
            System.out.println("[WARNING] embeddings call failed: " + e.getMessage());
            return null;
        }
    }

    private static final int EMBED_BATCH = 64;

    private List<double[]> embedBatch(List<String> batch) {
        RestTemplate restTemplate = newRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + OPENAI_API_KEY);

        JSONArray input = new JSONArray();
        for (String t : batch) input.put(t == null || t.isBlank() ? " " : t);
        JSONObject body = new JSONObject()
                .put("model", EMBEDDING_MODEL)
                .put("input", input);

        ResponseEntity<String> response = restTemplate.exchange(
                EMBEDDING_URL, HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
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
        List<double[]> out = new java.util.ArrayList<>();
        for (double[] v : vectors) {
            if (v == null) return null;
            out.add(v);
        }
        if (json.has("usage")) {
            System.out.println("[Used Token] embeddings " + json.getJSONObject("usage").optInt("total_tokens", 0));
        }
        return out;
    }

    /**
     * A client with timeouts. Without them a stalled OpenAI connection held the caller
     * forever: on the deployment machine the bot said "processing..." and never came
     * back, with nothing in the log. The read timeout is generous because the
     * dependency report is one long completion; the connect timeout is not.
     */
    private static RestTemplate newRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(180_000);
        return new RestTemplate(factory);
    }

    private String loadSystemPrompt(String promptFile) {
        ClassPathResource resource = new ClassPathResource(promptFile);
        byte[] bytes;
        try {
            bytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
