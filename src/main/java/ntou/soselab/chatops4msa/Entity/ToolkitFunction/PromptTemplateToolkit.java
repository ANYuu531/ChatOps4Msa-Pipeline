package ntou.soselab.chatops4msa.Entity.ToolkitFunction;  
  
import org.springframework.core.io.ClassPathResource;  
import org.springframework.stereotype.Component;  
import org.springframework.util.FileCopyUtils;  
  
import java.io.IOException;  
import java.nio.charset.StandardCharsets;  
import java.util.Map;  
import java.util.regex.Matcher;  
import java.util.regex.Pattern;  

@Component  
public class PromptTemplateToolkit extends ToolkitFunction {  
  
    public String toolkitPromptTemplate(String template, Map<String, String> localVariableMap) {  
        try {  
            String filePath = "prompts/" + template + ".txt";  
            ClassPathResource resource = new ClassPathResource(filePath);  
            if (!resource.exists()) {  
                return "[ERROR] Prompt template not found: " + filePath;  
            }  
  
            byte[] bytes = FileCopyUtils.copyToByteArray(resource.getInputStream());  
            String content = new String(bytes, StandardCharsets.UTF_8);  
  
            // Replace ${variable} placeholders with values from localVariableMap  
            Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");  
            Matcher matcher = pattern.matcher(content);  
            StringBuffer sb = new StringBuffer();  
            while (matcher.find()) {  
                String varName = matcher.group(1).trim();  
                String value = localVariableMap.getOrDefault(varName, "");  
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));  
            }  
            matcher.appendTail(sb);  
  
            System.out.println("[INFO] Loaded prompt template: " + filePath);  
            return sb.toString();  
  
        } catch (IOException e) {  
            throw new RuntimeException("PromptToolkit error: failed to load template: " + template, e);  
        }  
    }  
}