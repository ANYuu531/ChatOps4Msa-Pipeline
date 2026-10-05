# langchain4j 可行性 spike（2026-10-02，不合併）

worktree：`/Users/lianyu/Documents/GitHub/ChatOps4Msa-Pipeline/.claude/worktrees/agent-ad74444b17dfdf172`（基於 commit 0892236，未 commit）

## 1. 基線
- 工具鏈：`java -version` = Oracle GraalVM **21.0.6**；但 `mvn -v` 實際用 Homebrew **OpenJDK 26.0.2.1**（Maven 3.9.10）。沒有 `mvnw`。
- `mvn -q -DskipTests compile`：成功。
- `mvn -q test`：**62 執行、1 失敗、1 略過**。唯一失敗 = `McpToolkitCallToolTest.connect_then_call_execute_kubectl`（要活的 MCP server，環境因素，與本 spike 無關）。

## 2. 查到的版本（Maven Central，2026-09-28 發佈）
| 元件 | 版本 | 備註 |
|---|---|---|
| `langchain4j-bom` / `langchain4j` / `langchain4j-core` / `langchain4j-open-ai` | **1.20.2** | 穩定線，最低 JDK 17 |
| `langchain4j-agentic` | **1.20.2-beta30** | 仍是 beta（API 可能變）；bom 已管理此版本 |
| `langchain4j-*-spring-boot-starter` | 1.20.2-beta30 | pom 直接依賴 **spring-boot-starter 3.5.13**；官方文件：Boot 3 需 **3.5+**，Boot 4 用 `-spring-boot4-starter` |
| Spring Boot 3.x 最新 | 3.5.16 | （4.2.0-M2 為里程碑） |

不用 starter 的傳遞依賴（只看三個 plain 套件）：Jackson **2.22.1**、slf4j-api 2.0.18、opennlp-tools 2.5.11、mutiny-zero 1.3.1、jspecify 1.0.1、jtokkit 1.1.0、`langchain4j-http-client-jdk`（用 JDK HttpClient）。**沒有任何 Spring、okhttp 依賴** → Boot 3.5 的要求純粹來自 starter，plain 套件完全繞得開。

## 3. 路徑 A：plain langchain4j + Boot 3.0.6 —— **成功**
pom 差異（完整）：
```diff
+  <dependencyManagement> 加 dev.langchain4j:langchain4j-bom:1.20.2 (pom, import)
+  <dependencies> 加 dev.langchain4j:langchain4j、langchain4j-open-ai、langchain4j-agentic（版本由 bom 給）
-  maven-compiler-plugin <source>16</source><target>16</target>
+  maven-compiler-plugin <release>17</release>
```
新增檔案：`src/main/java/.../Service/NLPService/Langchain4jSpike.java`（由 `openai.api.url/key/model` 建 `OpenAiChatModel`，把 `/chat/completions` 尾巴剝成 baseUrl，建構期不碰網路）、`src/test/java/.../Langchain4jSpikeTest.java`（4 測試）、`Langchain4jJacksonCompatTest.java`（3 測試）。

結果：compile 成功；`Langchain4jSpikeTest` 4/4、`Langchain4jJacksonCompatTest` 3/3；全套 **66 執行、1 失敗（同一個 MCP 環境測試）、1 略過** → 零回歸。

衝突與處理：
- **class file 版本**：langchain4j 全部是 Java 17 bytecode（major 61），`target 16` 會編不過 → 改 `<release>17</release>`（本來 `java.version` 就寫 17，只是 compiler plugin 沒跟上）。這是唯一「非改不可」的項目。
- **Jackson**：langchain4j 1.20.2 宣告 Jackson 2.22.1；Boot 3.0.6 BOM 管 2.14.2、本專案直接釘 2.15.0 → Maven nearest-wins 解成 **2.15.0**。`dependency:tree -Dverbose` 顯示 langchain4j 的 2.22.x 全被 omitted。為了確認不是「編得過、跑不動」，`Langchain4jJacksonCompatTest` 強制走 `dev.langchain4j.internal.Json` 往返、`OpenAiChatModel.chat()` 與 `AiServices` 結構化輸出（record 回傳型別）的請求序列化，目標指向 `127.0.0.1:1`：三者都在序列化之後才拋 `ConnectException`，**沒有 LinkageError / NoSuchMethodError**。結論：目前用到的路徑在 2.15 上可跑，但這是「實測沒踩到」而非「官方支援」，之後若升 Jackson 應一併測。
- **slf4j**：Boot BOM 管成 2.0.7（langchain4j 要 2.0.18，API 相容），無事。
- **okhttp**：langchain4j 不用 okhttp（走 JDK HttpClient），我們 pom 裡三個 okhttp 的亂象不受影響。
- **convergence**：沒有新的版本分歧（新套件都是獨佔 groupId）。

## 4. 路徑 B（升 Boot 3.5.x）：**沒做**，A 已成功。
若將來要用 starter（自動組 bean、`langchain4j.open-ai.*` properties），成本估計：Boot 3.0.6 → 3.5.16 跨五個 minor；Jackson 會到 2.19、Spring 6.2、WebFlux/MCP SDK 0.12.1、JDA 5.0.1 多半相容但要重驗；加上 Boot 3.1 起 `spring-context` 直接釘 6.0.10 的那條會衝突要拔掉。starter 給的只是 bean 自動組裝，而 `Langchain4jSpike` 已示範手動組裝只要十幾行，**沒有理由為了 starter 升 Boot**。

## 5. 與我們「中途問人」需求的對應（HumanInTheLoop 阻塞 vs 檢查點）
現況：run 跑在 `DependencyAnalysisRunner` 的**單執行緒** executor → 貼 Discord 按鈕/modal → 答案寫進每使用者 JSON 檢查點（`dependency.state.ttl-hours`，預設 24h）→ 使用者的回答觸發**一個新的背景 run**（resume）。

langchain4j-agentic 1.20.2-beta30 的 HITL 其實有**三種**回應形態（已從 jar 內確認類別存在，也都寫進測試跑過）：
1. **`responseProvider` 直接回傳值**（文件的 stdin 範例）：**阻塞**。套到我們這裡＝那一條 `dependency-analysis` 執行緒會卡在 Discord modal 上，期間所有人的分析都排隊；等 24h 更不可能。**不可用**。
2. **`PendingResponse`**：也是阻塞（在 `CompletableFuture` 上等另一條執行緒 `complete()`），只是把阻塞換個地方，執行緒仍被占住；且行程重啟即丟失。**不可用**。
3. **`SuspendedResponse`**：responseProvider 回傳 `new SuspendedResponse<>("id")` → 框架把 AgenticScope（含 planner 游標）寫進 `AgenticScopeStore` 檢查點、**釋放執行緒**，呼叫端拿到 `ResultWithAgenticScope.suspended()==true`（或 `AgenticSystemSuspendedException`）。之後在任何執行緒、甚至行程重啟後，`workflow.getAgenticScope(runId).completePendingResponse(id, answer)` 再**重新呼叫同一個 agent 方法**，planner 從檢查點續跑。`async(true)` 另有「其他不需要答案的 agent 先跑」的平行形態，但它不是續跑機制，續跑靠的就是 `SuspendedResponse`。

對應關係：第 3 種就是我們現在手刻的「檢查點 + 使用者回答觸發新 run」，**一比一對得上**——Discord 按鈕 handler 做 `completePendingResponse` 後把「重新呼叫 agent 方法」丟回 `DependencyAnalysisRunner.run(...)`，執行緒模型不用改。差異：(a) 檢查點內容變成 langchain4j 的 `AgenticScope` 序列化（可自寫 `AgenticScopeStore` 存進現有 JSON 檔，TTL 仍由我們管；要先 `AgenticScopeSerializer.allowDeserializationPackagePrefix(...)` 註冊自家型別）；(b) 需要 `@MemoryId`（我們用 userId）才會啟用持久 scope；(c) 分析流程得改寫成 agentic sequence 的子 agent（目前是 low-code capability 呼叫鏈，不是 langchain4j agent）。`Langchain4jSpikeTest` 的第 2、3 個測試分別驗了「同物件續跑」與「經 scope registry 重新呼叫續跑」兩條路。

## 6. 建議
**暫不採用整套 agentic；可以先只採 `AiServices` 結構化輸出，HITL 留到流程重構時再評估。** 理由：
- 技術上可行且便宜：plain 套件在 Boot 3.0.6 上零衝突，唯一硬改是 compiler release 17；不需升 Boot。
- `SuspendedResponse` 在語意上與我們的檢查點設計同構，所以「換用它」的收益是少維護一套 resume 邏輯，代價是分析流程要改寫成 agent 子步驟、檢查點格式改成框架的，且 `langchain4j-agentic` 還在 **beta**（API 近 20 個 beta 都在動）。對論文時程而言，現有機制已在 BoA 真環境驗證過，重寫風險大於收益。
- `AiServices` 結構化輸出（record 回傳、JSON schema 自動產生）屬於穩定 1.20.2 線，能取代 `LLMService` 裡手刻的 RestTemplate + JSON 解析，低風險、可逐步替換，而且實測在 Jackson 2.15 下可序列化。
- 若之後要採 HITL：用 `SuspendedResponse` 形態，自寫 `AgenticScopeStore` 包現有 JSON 檢查點，不要用阻塞形態。
