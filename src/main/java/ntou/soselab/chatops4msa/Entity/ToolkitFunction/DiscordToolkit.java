package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.EmbedBuilder;
import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyAnalysisStateStore;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.List;

/**
 * For ease of invocation by the Capability Orchestrator,
 * the parameters are using snake case, similar to low-code.
 */
@Component
public class DiscordToolkit extends ToolkitFunction {
    private final JDAService jdaService;
    private MathToolkit mathToolkit; // Inject MathToolkit
    private final DependencyAnalysisStateStore stateStore;

    @Autowired
    public DiscordToolkit(JDAService jdaService, MathToolkit mathToolkit,
                          DependencyAnalysisStateStore stateStore) {
        this.jdaService = jdaService;
        this.mathToolkit = mathToolkit;
        this.stateStore = stateStore;
    }

    /**
     * general text message
     */
//    public void toolkitDiscordText(String text) throws InterruptedException {
//        //Thread.sleep(5000);
//        double lastResult = mathToolkit.getLastResult();
//        String messageWithResult = text + lastResult;
////        if(lastResult!=0.0){
////            jdaService.sendChatOpsChannelMessage(messageWithResult);
////        }
////        else{
////            jdaService.sendChatOpsChannelMessage(text);
////        }
//        jdaService.sendChatOpsChannelMessage(text);
//    }
    public void toolkitDiscordText(String text) throws IOException {
        if (text.length() <= 1000) {
            jdaService.sendChatOpsChannelMessage(text);
        } else {
            // Long text is delivered as a file. Name it after its own heading (and
            // the repo under analysis, when there is one) so a run's many outputs are
            // told apart — instead of every one landing as "pipeline.yaml".
            String filename = deriveFilename(text);
            InputStream input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            jdaService.sendChatOpsChannelFile(filename, input);
        }
    }

    /** e.g. "spring-petclinic-microservices-code-extraction-notes.md". */
    private String deriveFilename(String text) {
        String base = slugFromFirstLine(text);
        if (base.isEmpty()) base = "message";
        String repo = currentRepoSlug();
        String ext = looksLikeRawYaml(text) ? ".yaml" : ".md";
        return (repo.isEmpty() ? "" : repo + "-") + base + ext;
    }

    /** The first non-empty line, stripped of markdown/DEBUG noise, as a filename slug. */
    private static String slugFromFirstLine(String text) {
        for (String raw : text.split("\n", 16)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            line = line.replaceFirst("^#+\\s*", "");          // markdown heading
            line = line.replaceFirst("(?i)^DEBUG:\\s*", "");  // debug prefix
            line = line.replace("*", "");                     // bold markers
            String slug = line.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
            if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-$", "");
            if (!slug.isEmpty()) return slug;
        }
        return "";
    }

    /** A bare k8s manifest (not one wrapped in a markdown ```yaml block). */
    private static boolean looksLikeRawYaml(String text) {
        String head = text.stripLeading();
        return head.startsWith("apiVersion:") || head.startsWith("---\napiVersion:");
    }

    /** The short repo name of the current user's dependency-analysis run, or "". */
    private String currentRepoSlug() {
        try {
            String userId = UserContextHolder.getUserId();
            if (userId == null || userId.isBlank()) return "";
            DependencyAnalysisStateStore.State state = stateStore.get(userId);
            if (state == null || state.repoName == null || state.repoName.isBlank()) return "";
            String repo = state.repoName.trim();
            int slash = repo.lastIndexOf('/');
            if (slash >= 0 && slash < repo.length() - 1) repo = repo.substring(slash + 1);
            return repo.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]+", "-")
                    .replaceAll("(^-|-$)", "");
        } catch (Exception e) {
            return "";
        }
    }

    public String toolkitDiscordGet(String text) {
        int lastResult = (int) mathToolkit.getLastResult();
        //System.out.println(lastResult);
        DecimalFormat decimalFormat = new DecimalFormat("#.##");
        return decimalFormat.format(lastResult);
    }
    /**
     * blue info message
     */
    public void toolkitDiscordInfo(String text) {
        jdaService.sendChatOpsChannelInfoMessage(text);
    }

    /**
     * orange warning message
     */
    public void toolkitDiscordWarning(String text) {
        jdaService.sendChatOpsChannelWarningMessage(text);
    }

    /**
     * red error message
     */
    public void toolkitDiscordError(String text) {
        jdaService.sendChatOpsChannelErrorMessage(text);
    }

    /**
     * blocks message
     */
    public void toolkitDiscordBlocks(String text) {
        jdaService.sendChatOpsChannelBlocksMessage(text);
    }

    /**
     * JSON message
     */
    public void toolkitDiscordJson(String text) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String formattedJson = gson.toJson(JsonParser.parseString(text));
        jdaService.sendChatOpsChannelBlocksMessage(formattedJson);
    }

    /**
     * Embed message
     */
    public void toolkitDiscordEmbed(String title,
                                    String color,
                                    String field_json) throws ToolkitFunctionException {

        processToolkitDiscordEmbed(title, color, field_json, null, null);
    }

    /**
     * Embed message with thumbnail
     */
    public void toolkitDiscordEmbedThumbnail(String title,
                                             String color,
                                             String field_json,
                                             String thumbnail) throws ToolkitFunctionException {

        processToolkitDiscordEmbed(title, color, field_json, thumbnail, null);
    }

    /**
     * Embed message with image
     */
    public void toolkitDiscordEmbedImage(String title,
                                         String color,
                                         String field_json,
                                         String image) throws ToolkitFunctionException {

        processToolkitDiscordEmbed(title, color, field_json, null, image);
    }

    private void processToolkitDiscordEmbed(String title,
                                            String color,
                                            String field_json,
                                            String thumbnail,
                                            String image) throws ToolkitFunctionException {
        Color colorObj = parseColor(color);

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> map;
        try {
            map = objectMapper.readValue(field_json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new ToolkitFunctionException(e.getOriginalMessage());
        }

        EmbedBuilder eb = new EmbedBuilder().setTitle(title).setColor(colorObj);
        if (thumbnail != null) eb.setThumbnail(thumbnail);
        if (image != null) eb.setImage(image);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            eb.addField(entry.getKey(), entry.getValue(), false);
        }
        jdaService.sendChatOpsChannelEmbedMessage(eb.build());
    }

    private Color parseColor(String colorName) {
        Color colorObj = Color.GRAY;
        if ("green".equals(colorName)) colorObj = Color.GREEN;
        if ("orange".equals(colorName)) colorObj = Color.ORANGE;
        if ("red".equals(colorName)) colorObj = Color.RED;
        return colorObj;
    }
}
