package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.stereotype.Component;

@Component
public class UserToolkit extends ToolkitFunction {

    /**
     * 取得目前執行指令的使用者 userId（UserContextHolder 設定）
     */
    public String toolkitUserContext() {
        System.out.println("[DEBUG] toolkitUserContext called");
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            System.out.println("[WARN] userId not found in context.");
            return null;
        }
        return userId;
    }
}