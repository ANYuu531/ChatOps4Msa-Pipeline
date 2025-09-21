package ntou.soselab.chatops4msa.Service.DiscordService;

import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import ntou.soselab.chatops4msa.Exception.DiscordIdException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;


@Service
public class JDAService {

    private final JDA jda;
    private final String GUILD_ID;
    private final String CHATOPS_CHANNEL_ID;
    private TextChannel chatOpsChannel;

    @Autowired
    public JDAService(Environment env,
                      SlashCommandListener slashCommandListener,
                      MessageListener messageListener,
                      ButtonListener buttonListener) {

        final String APP_TOKEN = env.getProperty("discord.application.token");
        try {
            this.jda = JDABuilder.createDefault(APP_TOKEN)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(slashCommandListener)
                    .addEventListeners(messageListener)
                    .addEventListeners(buttonListener)
                    .build()
                    .awaitReady();

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.GUILD_ID = env.getProperty("discord.guild.id");
        this.CHATOPS_CHANNEL_ID = env.getProperty("discord.channel.chatops.id");

        System.out.println();
        System.out.println("[DEBUG] JDA START!");
        System.out.println();
    }

    @PostConstruct
    private void loadChatOpsChannel() {
        try {
            Guild guild = jda.getGuildById(GUILD_ID);
            if (guild == null) {
                System.out.println("[ERROR] the guild ID is incorrect");
                throw new DiscordIdException("the guild ID is incorrect");
            }

            TextChannel channel = guild.getTextChannelById(CHATOPS_CHANNEL_ID);
            if (channel == null) {
                System.out.println("[ERROR] the chatops channel ID is incorrect");
                throw new DiscordIdException("the chatops channel ID is incorrect");
            }

            this.chatOpsChannel = channel;

        } catch (DiscordIdException e) {
            throw new RuntimeException(e);
        }
    }

    public JDA getJDA() {
        return this.jda;
    }

    public void sendChatOpsChannelInfoMessage(String message) {
        sendChatOpsChannelMessage("```yaml\n" + message + "```");
    }

    public void sendChatOpsChannelWarningMessage(String message) {
        sendChatOpsChannelMessage("```prolog\n" + message + "```");
    }

    public void sendChatOpsChannelErrorMessage(String message) {
        sendChatOpsChannelMessage("```ml\n" + message + "```");
    }

    public void sendChatOpsChannelBlocksMessage(String message) {
        sendChatOpsChannelMessage("```\n" + message + "```");
    }

    public void sendChatOpsChannelMessage(String message) {
        chatOpsChannel.sendMessage(message).queue();
    }

    public void sendChatOpsChannelEmbedMessage(MessageEmbed embedMessage) {
        chatOpsChannel.sendMessageEmbeds(embedMessage).queue();
    }

    public void sendChatOpsChannelFile(String filename, InputStream inputStream) {
        TextChannel channel = jda.getTextChannelById(CHATOPS_CHANNEL_ID);
        if (channel != null) {
            channel.sendFiles(FileUpload.fromData(inputStream, filename)).queue();
        }
    }


    public void sendChatOpsChannelButtons(List<Map<String, String>> buttons) {
        List<Button> buttonList = buttons.stream().map(button -> {
            String id = button.get("id");
            String label = button.get("label");
            String style = button.getOrDefault("style", "primary").toLowerCase();
            return switch (style) {
                case "primary" -> Button.primary(id, label);
                case "secondary" -> Button.secondary(id, label);
                case "success" -> Button.success(id, label);
                case "danger" -> Button.danger(id, label);
                case "link" -> Button.link(id, label); 
                default -> Button.primary(id, label); 
            };
        }).collect(Collectors.toList());

        chatOpsChannel.sendMessage("請選擇操作：")
                .setActionRow(buttonList)
                .queue();
    }
}
