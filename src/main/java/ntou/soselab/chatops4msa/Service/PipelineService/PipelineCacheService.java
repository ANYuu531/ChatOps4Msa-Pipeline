package ntou.soselab.chatops4msa.Service.PipelineService;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PipelineCacheService {

    private final Map<String, String> userPipelineCache = new ConcurrentHashMap<>();

    /**
     * 暫存使用者的 Pipeline YAML
     */
    public void cache(String userId, String yaml) {
        userPipelineCache.put(userId, yaml);
    }

    /**
     * 取得指定使用者的 Pipeline YAML
     */
    public String get(String userId) {
        return userPipelineCache.get(userId);
    }

    /**
     * 清除指定使用者的暫存 YAML
     */
    public void clear(String userId) {
        userPipelineCache.remove(userId);
    }

    /**
     * 檢查使用者是否有暫存的 YAML
     */
    public boolean has(String userId) {
        return userPipelineCache.containsKey(userId);
    }
}