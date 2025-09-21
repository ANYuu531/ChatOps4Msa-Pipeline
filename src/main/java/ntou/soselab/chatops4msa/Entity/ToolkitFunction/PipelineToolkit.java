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
    public void toolkitPipelineCache(String user_id, String pipeline_yaml) {
        System.out.println("[DEBUG] toolkitPipelineCache called");
        System.out.println("[DEBUG] userId = " + user_id);
        System.out.println("[DEBUG] pipelineYaml = " + (pipeline_yaml == null ? "null" : "length = " + pipeline_yaml.length()));

        if (user_id == null || user_id.isEmpty()) {
            System.out.println("[DEBUG] userId is null or empty, skipping.");
            return;
        }
        if (pipeline_yaml == null || pipeline_yaml.isEmpty()) {
            System.out.println("[DEBUG] pipelineYaml is null or empty, skipping.");
            return;
        }

        pipelineCacheService.cache(user_id, pipeline_yaml);
        System.out.println("[DEBUG] Pipeline cached for user: " + user_id);
        System.out.println(pipeline_yaml);
    }
}