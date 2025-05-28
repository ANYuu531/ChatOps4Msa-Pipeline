package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class TrelloToolkit extends ToolkitFunction {

    public void toolkitTrelloCreateCard(String board_id, String list_name, String card_name, String card_desc) throws IOException {
        OkHttpClient client = new OkHttpClient().newBuilder().build();

        MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
        RequestBody body = RequestBody.create(mediaType, "boardId=" + board_id +
                "&listName=" + list_name +
                "&cardName=" + card_name +
                "&cardDesc=" + card_desc);

        Request request = new Request.Builder()
                .url("http://localhost:9000/api/createCard")
                .method("POST", body)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                System.out.println("Request was successful");
                System.out.println("Response body: " + response.body().string());
            } else {
                System.out.println("Request failed with code: " + response.code());
            }
        }
    }
}

