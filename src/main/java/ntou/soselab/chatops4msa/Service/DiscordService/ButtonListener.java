package ntou.soselab.chatops4msa.Service.DiscordService;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import ntou.soselab.chatops4msa.Entity.NLP.IntentAndEntity;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.TimeToolkit;
import ntou.soselab.chatops4msa.Exception.CapabilityRoleException;
import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.NLPService.DialogueTracker;
import ntou.soselab.chatops4msa.Service.PipelineService.PipelineCacheService;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;

@Service
public class ButtonListener extends ListenerAdapter {
    private final DialogueTracker dialogueTracker;
    private final CapabilityOrchestrator orchestrator;
    private final JDAService jdaService;

    @Lazy
    @Autowired
    public ButtonListener(DialogueTracker dialogueTracker,
                          CapabilityOrchestrator orchestrator,
                          JDAService jdaService) {

        this.dialogueTracker = dialogueTracker;
        this.orchestrator = orchestrator;
        this.jdaService = jdaService;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

        System.out.println(">>> trigger button interaction event");

        System.out.println("[TIME] " + new Date());
        User tester = event.getUser();
        String testerId = tester.getId();
        String testerName = tester.getName();
        String buttonId = event.getButton().getId();
        System.out.println("[DEBUG] " + testerName + " click " + buttonId);

        // disable the button
        List<MessageEmbed> originalEmbedList = event.getMessage().getEmbeds();
        Button disabledButton = event.getComponent().asDisabled();
        event.getMessage().editMessageEmbeds(originalEmbedList).setActionRow(disabledButton).queue();

        // get the user roles
        List<String> roleNameList = new ArrayList<>();
        Member member = event.getMember();
        if (member != null) {
            for (Role role : member.getRoles()) {
                roleNameList.add(role.getName());
            }
        }
        System.out.println("[User Role] " + roleNameList);

        // pipeline file按鈕邏輯
        switch (buttonId) {
            case "save_pipeline_button":
                handleSavePipeline(testerId, event);
                return;
            default:
                break;
        }

        // clear the temporary data
        List<IntentAndEntity> performedCapabilityList = dialogueTracker.removeAllPerformableIntentAndEntity(testerId);

        // perform the capability
        List<String> intentNameList = new ArrayList<>();
        try {
            for (IntentAndEntity intentAndEntity : performedCapabilityList) {
                String intentName = intentAndEntity.getIntentName();
                Map<String, String> entityMap = intentAndEntity.getEntities();
                if ("Perform".equals(buttonId)) {
                    orchestrator.performTheCapability(intentName, entityMap, roleNameList);
                }
                intentNameList.add(intentName);
            }

        } catch (CapabilityRoleException e) {
            e.printStackTrace();
            String warningMessage = "[WARNING] " + e.getLocalizedMessage();
            System.out.println(warningMessage);
            jdaService.sendChatOpsChannelWarningMessage(warningMessage);

        } catch (ToolkitFunctionException e) {
            e.printStackTrace();
            String errorMessage = "[ERROR] " + e.getLocalizedMessage();
            System.out.println(errorMessage);
            jdaService.sendChatOpsChannelErrorMessage(errorMessage);
        }

        // generate the question for next capability (top of the stack)
        if (dialogueTracker.isWaitingTester(testerId)) {
            dialogueTracker.removeWaitingTesterList(testerId);
            String question = dialogueTracker.generateQuestionString(testerId);
            //event.reply("got it\n" + buttonId + " `" + intentNameList + "`\n\n" + question).queue();
            event.getHook().editOriginal("got it\n" + buttonId + " `" + intentNameList + "`\n\n" + question).queue();

            System.out.println("[DEBUG] generate question to " + testerName);
        }

        System.out.println("<<< end of current button interaction event");
        System.out.println();
    }

    @Autowired private PipelineCacheService pipelineCacheService;
    @Autowired private TimeToolkit timeToolkit;

    /**
     * 儲存 YAML
     */
    private void handleSavePipeline(String userId, ButtonInteractionEvent event) {
        if (!pipelineCacheService.has(userId)) {
            event.reply("沒有找到暫存的 pipeline，請先使用 `/get-pipeline` 產生一個。")
                .setEphemeral(true)
                .queue(); 
            return;
        }

        Modal modal = Modal.create("save_pipeline_modal", "Save Pipeline")
            .addActionRow(
                TextInput.create("filename", "Please set the name of the file.", TextInputStyle.SHORT)
                    .setPlaceholder("e.g. frontend-deploy.yml")
                    .setRequired(true) 
                    .build()
            )
            .build();

        event.replyModal(modal).queue(); 
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals("save_pipeline_modal")) return;

        String userId = event.getUser().getId();
        String fileName = event.getValue("filename").getAsString();  // 使用者輸入的名稱

        if (!pipelineCacheService.has(userId)) {
            event.reply("找不到暫存的 pipeline，請先使用 `/get-pipeline`。").setEphemeral(true).queue();
            return;
        }

        String pipelineYaml = pipelineCacheService.get(userId);

        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = "http://haystack-service:8000/add_pipeline/";

            Map<String, String> payload = new HashMap<>();
            payload.put("content", pipelineYaml);
            payload.put("repo_url", fileName);  

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                event.reply("Pipeline 已以檔名 `" + fileName + "` 儲存成功！").setEphemeral(true).queue();
            } else {
                event.reply("儲存失敗 (Haystack 回傳 " + response.getStatusCode() + ")").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            e.printStackTrace();
            event.reply("儲存失敗：" + e.getMessage()).setEphemeral(true).queue();
        }
    }
}
