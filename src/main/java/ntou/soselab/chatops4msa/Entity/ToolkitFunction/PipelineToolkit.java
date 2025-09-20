package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import ntou.soselab.chatops4msa.Service.PipelineService.PipelineCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PipelineToolkit extends ToolkitFunction {

    private final PipelineCacheService pipelineCacheService;

    @Autowired
    public PipelineToolkit(PipelineCacheService pipelineCacheService) {
        this.pipelineCacheService = pipelineCacheService;
    }

    /**
     * 將 Pipeline YAML 暫存起來（使用 userId 作為 key）
     */
    public void toolkitPipelineCache(String userId, String pipelineYaml) {
        System.out.println("[DEBUG] toolkitPipelineCache called");
        System.out.println("[DEBUG] userId = " + userId);
        System.out.println("[DEBUG] pipelineYaml = " + (pipelineYaml == null ? "null" : "length = " + pipelineYaml.length()));

        if (userId == null || userId.isEmpty()) {
            System.out.println("[DEBUG] userId is null or empty, skipping.");
            return;
        }
        if (pipelineYaml == null || pipelineYaml.isEmpty()) {
            System.out.println("[DEBUG] pipelineYaml is null or empty, skipping.");
            return;
        }

        pipelineCacheService.cache(userId, pipelineYaml);
        System.out.println("[DEBUG] Pipeline cached for user: " + userId);
        System.out.println(pipelineYaml);
    }
}