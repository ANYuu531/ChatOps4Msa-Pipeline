package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Component;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.util.Config;

@Component
public class K8sToolkit extends ToolkitFunction {
    public String toolkitK8sResource() throws Exception {
        // 使用 ~/.kube/config 連線到本地 K8s cluster
        //ApiClient client = Config.defaultClient();
        // when code run in pod
        ApiClient client = Config.fromCluster();
        Configuration.setDefaultApiClient(client);

        CoreV1Api api = new CoreV1Api();

        // 取得所有 Node 資訊
        V1NodeList nodeList = api.listNode(null, null, null, null, null, null, null, null, null, null);

        //System.out.println("可用資源（Allocatable）:");

        BigDecimal totalCpu = BigDecimal.ZERO;
        BigDecimal totalMemory = BigDecimal.ZERO;

        for (V1Node node : nodeList.getItems()) {
            //String nodeName = node.getMetadata().getName();
            Map<String, Quantity> allocatable = node.getStatus().getAllocatable();

            Quantity cpuQuantity = allocatable.get("cpu");
            Quantity memoryQuantity = allocatable.get("memory");
            
            totalCpu = totalCpu.add(cpuQuantity.getNumber()); // 單位是 cores
            totalMemory = totalMemory.add(memoryQuantity.getNumber()); // 單位是 bytes (ex: 8Gi = 8589934592)
            //System.out.printf("Node: %s, CPU: %s cores, Memory: %s\n", nodeName, cpu, memory);
        }
        return String.format("CPU: %s cores, Memory: %s bytes", totalCpu.toPlainString(), totalMemory.toPlainString());
    }
}
