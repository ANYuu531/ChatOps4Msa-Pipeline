package ntou.soselab.chatops4msa.Service.DiscordService;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ntou.soselab.chatops4msa.Entity.NLP.IntentAndEntity;
import ntou.soselab.chatops4msa.Entity.ToolkitFunction.DepstateToolkit;
import ntou.soselab.chatops4msa.Exception.CapabilityRoleException;
import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.DependencyReportService;
import ntou.soselab.chatops4msa.Service.NLPService.DialogueTracker;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ButtonListener extends ListenerAdapter {
    private final DialogueTracker dialogueTracker;
    private final CapabilityOrchestrator orchestrator;
    private final JDAService jdaService;
    private final DependencyReportService dependencyReportService;

    @Lazy
    @Autowired
    public ButtonListener(DialogueTracker dialogueTracker,
                          CapabilityOrchestrator orchestrator,
                          JDAService jdaService,
                          DependencyReportService dependencyReportService) {

        this.dialogueTracker = dialogueTracker;
        this.orchestrator = orchestrator;
        this.jdaService = jdaService;
        this.dependencyReportService = dependencyReportService;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

        System.out.println(">>> trigger button interaction event");

        System.out.println("[TIME] " + new Date());
        event.deferReply().queue();
        User tester = event.getUser();
        String testerId = tester.getId();
        String testerName = tester.getName();
        String buttonId = event.getButton().getId();
        System.out.println("[DEBUG] " + testerName + " click " + buttonId);

        // disable the button
        List<MessageEmbed> originalEmbedList = event.getMessage().getEmbeds();
        Button disabledButton = event.getComponent().asDisabled();
        event.getMessage().editMessageEmbeds(originalEmbedList).setActionRow(disabledButton).queue();

        // dependency-analysis decision buttons (handled before the NLP intent flow)
        if (DepstateToolkit.CONTINUE_BUTTON_ID.equals(buttonId)) {
            try {
                UserContextHolder.setUserId(testerId);
                event.getHook().editOriginal("Generating report...").queue();
                dependencyReportService.generateAndPost(testerId);
            } catch (Exception e) {
                e.printStackTrace();
                jdaService.sendChatOpsChannelErrorMessage("[ERROR] failed to generate report: " + e.getLocalizedMessage());
            } finally {
                UserContextHolder.clear();
            }
            return;
        }
        if (DepstateToolkit.PAUSE_BUTTON_ID.equals(buttonId)) {
            event.getHook().editOriginal(
                    "Paused. Please supplement evidence first (re-run with a valid entry_url to auto-drive traffic, "
                            + "drive traffic manually then use supplement-dependency-traffic, or add source code that can be "
                            + "statically extracted), then re-run get-dependency-analysis.").queue();
            return;
        }

        // get the user roles
        List<String> roleNameList = new ArrayList<>();
        Member member = event.getMember();
        if (member != null) {
            for (Role role : member.getRoles()) {
                roleNameList.add(role.getName());
            }
        }
        System.out.println("[User Role] " + roleNameList);

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
}
