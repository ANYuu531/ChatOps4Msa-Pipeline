package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.json.JSONArray;
import org.springframework.util.FileCopyUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LlmToolkit is responsible for invoking LLM with optional prompt templates
 * and retaining short-term dialogue history per user for contextual reasoning.
 */
@Component
public class LlmToolkit {

    private final LLMService llmService;
    private final int MAX_HISTORY_SIZE = 10; // Maximum rounds (each round = user + assistant)
    private final long EXPIRE_MILLIS = 30 * 60 * 1000; // 10 minutes

    private static class UserChatRecord {
        List<JSONObject> messages = new ArrayList<>();
        long lastAccess = System.currentTimeMillis();
    }

    private final Map<String, UserChatRecord> chatHistoryMap = new ConcurrentHashMap<>();

    @Autowired
    public LlmToolkit(LLMService llmService) {
        this.llmService = llmService;
    }

    public String toolkitLlmCall(String prompt, String prompt_template) {
        try {
            String userId = UserContextHolder.getUserId();
            if (userId == null || userId.isBlank()) {
                throw new RuntimeException("User ID not found in context.");
            }

            cleanUpExpiredHistory();
            UserChatRecord chatRecord = chatHistoryMap.getOrDefault(userId, new UserChatRecord());

            String fullPrompt = prompt;
            if (prompt_template != null && !prompt_template.isBlank() && !prompt_template.equalsIgnoreCase("none")) {
                String promptFilePath = "prompts/" + prompt_template + ".txt";
                ClassPathResource resource = new ClassPathResource(promptFilePath);
                if (resource.exists()) {
                    String template = loadSystemPrompt(promptFilePath);
                    fullPrompt = template + "\n" + prompt;
                    System.out.println("[INFO] Loaded template: " + promptFilePath);
                } else {
                    System.out.println("[WARN] Template not found: " + promptFilePath);
                }
            }

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", fullPrompt);
            chatRecord.messages.add(userMessage);

            // Estimate token length and trim if needed
            while (estimateTotalTokens(chatRecord.messages) > 12000) {
                chatRecord.messages.remove(0);
            }

            JSONArray messageArray = new JSONArray(chatRecord.messages);
            //System.out.println(fullPrompt);
            String response = llmService.callAPIFromOutside(messageArray);

            JSONObject assistantMessage = new JSONObject();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response);
            chatRecord.messages.add(assistantMessage);

            chatRecord.lastAccess = System.currentTimeMillis();
            chatHistoryMap.put(userId, chatRecord);

            return response;

        } catch (IOException | JSONException e) {
            throw new RuntimeException("Toolkit LLM error occurred during prompt generation.", e);
        }
    }
    public String  toolkitLlmTableSearch(String query_text, String table_name) {
        try {
            // Load .txt YAML file from classpath (in prompts/)
            String filePath = "prompts/" + table_name + ".txt";
            ClassPathResource resource = new ClassPathResource(filePath);
            if (!resource.exists()) {
                throw new RuntimeException("RAG file not found: " + filePath);
            }

            byte[] bytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
            String yamlContent = new String(bytes, StandardCharsets.UTF_8);

            // Parse YAML list
            List<Map<String, Object>> table = parseYamlList(yamlContent);
            String query = query_text.toLowerCase();

            // Score each entry based on alias match
            List<MatchResult> scored = new ArrayList<>();
            for (Map<String, Object> entry : table) {
                List<String> aliases = (List<String>) entry.get("aliases");
                for (String alias : aliases) {
                    int score = keywordMatchScore(alias, query);
                    if (score > 0) {
                        scored.add(new MatchResult(entry, score));
                        break; // one alias match is enough
                    }
                }
            }

            // Sort by score and return top N
            scored.sort((a, b) -> Integer.compare(b.score, a.score));
            int maxReturn = Math.min(3, scored.size());

            List<String> results = new ArrayList<>();
            for (int i = 0; i < maxReturn; i++) {
                Map<String, Object> item = scored.get(i).entry;
                results.add(String.format(
                        "名稱：%s\n定義：%s\n範例：%s",
                        item.get("key"),
                        item.get("zh_definition"),
                        item.get("zh_example")
                        
                ));
            }

            if (results.isEmpty()) {
                results.add("⚠️ 無法根據關鍵詞找到對應的指標定義。請確認提問內容是否明確。");
            }

            return String.join("\n\n", results);

        } catch (Exception e) {
            throw new RuntimeException("Error in toolkitLlmTableSearch", e);
        }
    }
    private static class MatchResult {
        Map<String, Object> entry;
        int score;
        public MatchResult(Map<String, Object> entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }
    private List<Map<String, Object>> parseYamlList(String yamlContent) {
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        return yaml.load(yamlContent);
    }
    private int keywordMatchScore(String keyword, String sentence) {
        keyword = keyword.toLowerCase();
        sentence = sentence.toLowerCase();
        if (sentence.contains(keyword)) return keyword.length(); // exact match preferred
        // partial match scoring
        int commonLength = longestCommonSubstring(keyword, sentence).length();
        return commonLength >= 2 ? commonLength : 0;
    }

    private String longestCommonSubstring(String s1, String s2) {
        int max = 0, end = 0;
        int[][] dp = new int[s1.length()][s2.length()];
        for (int i = 0; i < s1.length(); i++) {
            for (int j = 0; j < s2.length(); j++) {
                if (s1.charAt(i) == s2.charAt(j)) {
                    dp[i][j] = (i == 0 || j == 0) ? 1 : dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > max) {
                        max = dp[i][j];
                        end = i;
                    }
                }
            }
        }
        return s1.substring(end - max + 1, end + 1);
    }


    private String loadSystemPrompt(String promptFilePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(promptFilePath);
        byte[] bytes = FileCopyUtils.copyToByteArray(resource.getInputStream());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void cleanUpExpiredHistory() {
        long now = System.currentTimeMillis();
        chatHistoryMap.entrySet().removeIf(entry -> now - entry.getValue().lastAccess > EXPIRE_MILLIS);
    }

    private int estimateToken(String text) {
        return text.length() / 4; // rough estimation
    }

    private int estimateTotalTokens(List<JSONObject> messages) {
        int total = 0;
        for (JSONObject msg : messages) {
            total += estimateToken(msg.optString("content", ""));
        }
        return total;
    }
}
