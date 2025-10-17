package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class RestapiToolkit extends ToolkitFunction {

    /**
     * GET method
     */
    public String toolkitRestapiGet(String url) {
        RestTemplate restTemplate = new RestTemplate();
        url = url.replaceAll("\"", "");
        ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        return responseEntity.getBody();
    }

    /**
     * POST method (auto-detects JSON or multipart)
     * @param url target API endpoint
     * @param headersMap optional headers (e.g., Content-Type)
     * @param body JSON body (optional)
     * @param formData form-data key/value pairs (optional)
     * @param authorization Authorization header (optional)
     */
    public String toolkitRestapiPost(String url,
                                     Map<String, String> headersMap,
                                     String body,
                                     Map<String, Object> formData,
                                     String authorization) {

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();

        if (headersMap != null) {
            headersMap.forEach(headers::set);
        }

        if (authorization != null && !authorization.isEmpty()) {
            headers.set("Authorization", authorization);
        }

        try {
            ResponseEntity<String> response;

            if (formData != null && !formData.isEmpty()) {
                // multipart/form-data 模式
                MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
                formData.forEach(multipartBody::add);

                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(multipartBody, headers);
                response = restTemplate.postForEntity(url, requestEntity, String.class);

            } else if (body != null && !body.isEmpty()) {
                // JSON 模式
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);
                response = restTemplate.postForEntity(url, requestEntity, String.class);

            } else {
                // 無 body 的 POST
                HttpEntity<String> requestEntity = new HttpEntity<>(headers);
                response = restTemplate.postForEntity(url, requestEntity, String.class);
            }

            return response.getBody();

        } catch (Exception e) {
            e.printStackTrace();
            return "Error in toolkitRestapiPost: " + e.getMessage();
        }
    }
}
