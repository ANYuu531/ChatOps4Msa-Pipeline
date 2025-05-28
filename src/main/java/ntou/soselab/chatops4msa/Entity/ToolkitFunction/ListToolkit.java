package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ntou.soselab.chatops4msa.Entity.CapabilityConfig.DevOpsTool.LowCode.InvokedFunction;
import ntou.soselab.chatops4msa.Exception.ToolkitFunctionException;
import ntou.soselab.chatops4msa.Service.CapabilityOrchestrator.CapabilityOrchestrator;
import ntou.soselab.chatops4msa.Service.DiscordService.JDAService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * For ease of invocation by the CapabilityOrchestrator,
 * the parameters are using snake case, similar to low-code.
 */
@Component
public class ListToolkit extends ToolkitFunction {
    private final CapabilityOrchestrator orchestrator;
    private final JDAService jdaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ListToolkit(CapabilityOrchestrator orchestrator, JDAService jdaService) {
        this.orchestrator = orchestrator;
        this.jdaService = jdaService;
    }

    /**
     * from ["content"] to "content"
     */
    public String toolkitListToString(String list) throws ToolkitFunctionException {
        List<String> listObj;
        try {
            listObj = objectMapper.readValue(list, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new ToolkitFunctionException(e.getOriginalMessage());
        }
        if (listObj.size() == 1) return list.replaceAll("\\[\"", "").replaceAll("\"]", "");
        return list;
    }

    /**
     * [] is an empty list
     */
    public String toolkitListIsEmpty(String list) {
        if ("[]".equals(list)) return "true";
        return "false";
    }

    /**
     * @param list  like ["https:", "", "github", "com", "sheng-kai-wang", "ChatOps4Msa-Sample-Bookinfo", "git"]
     * @param index like 5
     * @return like "ChatOps4Msa-Sample-Bookinfo"
     */
    public String toolkitListGet(String list, String index) throws ToolkitFunctionException {
        String[] array;
        try {
            array = objectMapper.readValue(list, String[].class);
            if(index.equals("first")){
                return array[0];
            }
            else if(index.equals("last")){
                return array[array.length-1];
            }
            else {
                int i = Integer.parseInt(index);
                return array[i];
            }
        } catch (JsonProcessingException e) {
            throw new ToolkitFunctionException(e.getOriginalMessage());
        }
    }

    /**
     * execute the todo_function synchronously
     *
     * @param list             like ["service_1", "service_2", "service_3"]
     * @param element_name     like "service_name"
     * @param todoList         is a list of InvokedFunction
     * @param localVariableMap come from declaredFunction
     */
    public void toolkitListForeach(String list,
                                   String element_name,
                                   List<InvokedFunction> todoList,
                                   Map<String, String> localVariableMap) throws ToolkitFunctionException {
        List<Object> listObj;
        try {
            listObj = objectMapper.readValue(list, new TypeReference<List<Object>>() {
            });

            // temporary storage of local variable with the same name
            String localVariableTemp = localVariableMap.get(element_name);

            for (int i = 0; i < listObj.size(); i++) {
                // put the element from foreach list
                String json = objectMapper.writeValueAsString(listObj.get(i));
                // from "content" to content
                if (json.startsWith("\"")) json = json.replaceAll("\"", "");
                localVariableMap.put(element_name, json);
                // put the index into local variable
                localVariableMap.put("i", String.valueOf(i));
                // invoke all the todo_function
                String returnSignal = orchestrator.invokeSpecialParameter(todoList, localVariableMap);
                if (returnSignal != null) break;
            }

            // restore the local variable
            localVariableMap.put(element_name, localVariableTemp);

        } catch (JsonProcessingException e) {
            throw new ToolkitFunctionException(e.getOriginalMessage());
        }
    }

    /**
     * execute the todo_function asynchronously
     *
     * @param list             like ["service_1", "service_2", "service_3"]
     * @param element_name     like "service_name"
     * @param todoList         is a list of InvokedFunction
     * @param localVariableMap come from declaredFunction
     */
    public void toolkitListAsync(String list,
                                 String element_name,
                                 List<InvokedFunction> todoList,
                                 Map<String, String> localVariableMap) throws ToolkitFunctionException {

        // there are 4 microservices for Bookinfo (4 threads)
        ExecutorService executorService = Executors.newFixedThreadPool(4);

        List<String> listObj;
        try {
            listObj = objectMapper.readValue(list, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new ToolkitFunctionException(e.getOriginalMessage());
        }

        // temporary storage of local variable with the same name
        String localVariableTemp = localVariableMap.get(element_name);

        for (int i = 0; i < listObj.size(); i++) {
            String element = listObj.get(i);
            int finalIndex = i;
            executorService.submit(() -> {
                // put the element from async list
                localVariableMap.put(element_name, element);
                // invoke all the todo_function
                try {
                    // put the index into local variable
                    localVariableMap.put("i", String.valueOf(finalIndex));
                    // invoke the todo_function
                    InvokedFunction function = todoList.get(finalIndex);
                    List<InvokedFunction> functionList = new ArrayList<>();
                    functionList.add(function);
                    orchestrator.invokeSpecialParameter(functionList, localVariableMap);
                } catch (ToolkitFunctionException e) {
                    jdaService.sendChatOpsChannelErrorMessage("[ERROR] " + e.getLocalizedMessage() + " (" + element + ")");
                }
            });
        }
        executorService.shutdown();

        // restore the local variable
        localVariableMap.put(element_name, localVariableTemp);
    }

    public void toolkitListParallelExecute(List<InvokedFunction> tasksList, Map<String, String> localVariableMap) throws ToolkitFunctionException {
//        for (InvokedFunction task : tasksList) {
//            try {
//                System.out.println("Executing task1: " + task.getName());//
//                orchestrator.invokeSpecialParameter(tasksList, localVariableMap);
//
//            } catch (Exception e) {
//                System.err.println("Error executing task: " + task.getName());
//                e.printStackTrace();
//            }
//        }
        ExecutorService executorService = Executors.newFixedThreadPool(tasksList.size());
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

        try {
            // 創建thread任務列表
            List<Callable<Void>> callables = tasksList.stream().map(task -> (Callable<Void>) () -> {
                try {
                    String threadName = Thread.currentThread().getName();
                    //int sleepTime = (int) (Math.random() * 10) + 1;
                    System.out.println("Executing task: " + task.getName() + " in thread: " + threadName + " at " + dtf.format(LocalDateTime.now()));
                    //System.out.println("Task: " + task.getName() + " will sleep for " + sleepTime + " seconds.");
                    //Thread.sleep(sleepTime * 1000);
                    // 使用 orchestrator 處理單個 task
                    orchestrator.invokeSpecialParameter(Collections.singletonList(task), localVariableMap);
                    System.out.println("Finished task: " + task.getName() + " in thread: " + threadName + " at " + dtf.format(LocalDateTime.now()));
                } catch (Exception e) {
                    System.err.println("Error executing task: " + task.getName());
                    e.printStackTrace();
                }
                return null;
            }).collect(Collectors.toList());
            // 並行處理
            List<Future<Void>> futures = executorService.invokeAll(callables);
            // 等待所有任務完成
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolkitFunctionException(e.getMessage());
                } catch (ExecutionException e) {
                    throw new ToolkitFunctionException(e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolkitFunctionException(e.getMessage());
        } finally {
            executorService.shutdown();
        }
    }
}
