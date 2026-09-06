package ntou.soselab.chatops4msa.Service.DiscordService;

import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import ntou.soselab.chatops4msa.Exception.DiscordIdException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
                      ButtonListener buttonListener,
                      ModalListener modalListener) {

        final String APP_TOKEN = env.getProperty("discord.application.token");
        try {
            this.jda = JDABuilder.createDefault(APP_TOKEN)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(slashCommandListener)
                    .addEventListeners(messageListener)
                    .addEventListeners(buttonListener)
                    .addEventListeners(modalListener)
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

    public void sendChatOpsChannelMessageWithButtons(String message, List<Button> buttons) {
        chatOpsChannel.sendMessage(message).setActionRow(buttons).queue();
    }

    /**
     * Posts a message and opens a public thread under it, for the report Q&amp;A.
     *
     * Synchronous ({@code complete()}) because the thread id is needed to register the
     * thread before anyone can type in it. Needs the bot's "Create Public Threads" and
     * "Send Messages in Threads" permissions.
     *
     * @return the thread id, or {@code null} when the message or the thread could not be
     *         created (missing permission, channel type without threads)
     */
    public String sendChatOpsChannelMessageAndOpenThread(String message, String threadName) {
        try {
            Message posted = chatOpsChannel.sendMessage(message).complete();
            ThreadChannel thread = posted.createThreadChannel(threadName).complete();
            return thread.getId();
        } catch (Exception e) {
            System.out.println("[WARNING] could not open a thread: " + e.getMessage());
            return null;
        }
    }

    /** Posts into a thread by id; silently ignores an unknown or archived thread. */
    public void sendThreadMessage(String threadId, String message) {
        if (threadId == null || message == null || message.isBlank()) return;
        ThreadChannel thread = jda.getThreadChannelById(threadId);
        if (thread == null) {
            System.out.println("[WARNING] thread not found: " + threadId);
            return;
        }
        thread.sendMessage(message).queue();
    }

    public void sendChatOpsChannelFile(String filename, InputStream inputStream) {
        TextChannel channel = jda.getTextChannelById(CHATOPS_CHANNEL_ID);
        if (channel != null) {
            channel.sendFiles(FileUpload.fromData(inputStream, filename)).queue();
        }
    }
}
