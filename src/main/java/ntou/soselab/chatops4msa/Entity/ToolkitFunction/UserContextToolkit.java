package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.DiscordService.UserContextHolder;
import org.springframework.stereotype.Component;

@Component
public class UserContextToolkit extends ToolkitFunction {

    /**
     * 取得目前執行指令的使用者 userId（UserContextHolder 設定）
     */
    public String toolkitUserContext() {
        return UserContextHolder.getUserId();
    }
}