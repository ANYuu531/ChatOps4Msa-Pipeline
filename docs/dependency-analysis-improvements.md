# 微服務依賴分析：程式碼抽取與斷點續跑之改進

**日期**：2026-07-13
**範圍**：`get-dependency-analysis` 流程的程式碼抽取層與檢查點機制

---

## 摘要

本次針對依賴分析流程做了三項改進：

1. **以 tree-sitter 取代 JavaParser** 作為程式碼抽取引擎，使抽取規則從「硬編碼的 Java visitor」變成「可宣告的查詢檔」，並取得跨語言能力。
2. **建立三層抽取策略**：支援的架構走確定性的程式碼解析，不支援的架構改由 LLM 直接讀取原始碼（而非退回文件式分析）。
3. **檢查點改為分階段持久化**，使用者暫停補充證據後可從斷點續跑，不需重跑整條流程。

過程中另外發現並修復了一個既有的嚴重缺陷（見第 0 節），該缺陷使程式碼抽取功能在容器環境中實際上從未生效。

---

## 0. 前置發現：程式碼抽取在 Docker 中從未真正執行過

在開始改動前，檢視既有實作時發現 `CodeToolkit` 是以 `ProcessBuilder` 呼叫外部 `git` 執行 clone。而本專案的 base image 為 `eclipse-temurin:17-jdk`。實測：

```
$ docker run --rm eclipse-temurin:17-jdk sh -c 'which git'
GIT_NOT_FOUND
```

該映像檔並未內含 `git`。因此在容器中執行時，clone 必定失敗，抽取結果永遠是：

```
Collection status: FAILED
```

由於原實作採「fail soft」設計（失敗不拋例外，僅回傳說明字串以免中斷整條流程），這個錯誤不會出現在任何錯誤訊息中，而是**靜默地**讓分析退化為「僅依賴 DeepWiki 文件」。

換言之，先前報告中所謂「程式碼確定性抽取」的貢獻，在容器部署下實際為零。

**修復**：於 `Dockerfile` 安裝 `git`。此修復獨立於後續改動，且是後續所有改進能夠生效的前提。

> **方法論上的啟示**：fail-soft 設計若缺乏可觀測性，會把「功能失效」偽裝成「功能無所發現」。兩者在報告中看起來完全一樣。後續實作中，所有失敗路徑都改為在 Ledger 內明確記錄失敗原因與 clone 指令。

---

## 1. 以 tree-sitter 取代 JavaParser

### 1.1 選型調查

| 候選 | 結論 |
|---|---|
| `io.github.bonede:tree-sitter:0.25.3` | **採用** |
| `ch.usi.si.seart:java-tree-sitter:1.12.0` | 排除：僅提供 x86_64 原生庫（實測 `file` 確認 dylib 無 arm64 slice），在 Apple Silicon 與 ARM 容器上無法載入；且自 2024-02 未更新 |
| 官方 `io.github.treesitter:jtreesitter` | 排除：未發佈至 Maven Central；且基於 Foreign Function & Memory API，需 **JDK 22**（本專案為 Java 17） |

採用版本：

```xml
io.github.bonede:tree-sitter:0.25.3
io.github.bonede:tree-sitter-java:0.23.4
io.github.bonede:tree-sitter-python:0.23.4
```

實際解壓 JAR 驗證原生庫涵蓋範圍：

```
lib/aarch64-linux-gnu-tree-sitter.so     ← ARM 容器
lib/x86_64-linux-gnu-tree-sitter.so      ← x86 容器
lib/aarch64-macos-tree-sitter.dylib      ← 開發機
lib/x86_64-macos-tree-sitter.dylib
lib/x86_64-windows-tree-sitter.dll
```

**部署限制**：Linux 原生庫皆為 glibc 連結（`*-linux-gnu-*`），**不得**將 base image 改為 alpine/musl，否則會在執行期出現 `UnsatisfiedLinkError`。此限制已寫入 `Dockerfile` 註解。

### 1.2 為什麼換：優點與代價的誠實評估

**tree-sitter 的優點**

1. **多語言，單一引擎與單一查詢語言。** 這是決定性因素，且與第 2 節的需求直接相關 —— 若不換，「支援的架構」永遠等同於「Java」，其他語言只能全部交給 LLM。換用後，新增一個語言只需加入 grammar 與一個查詢檔。

2. **容錯解析。** JavaParser 遇到語法錯誤（或原始碼使用了比設定語言等級更新的語法，如 record、sealed class）即對整個檔案拋出例外。原實作在 `CodeToolkit` 中的處理是 `parseErrors++` 後 `continue`，亦即**整個檔案的依賴資訊全部丟失**。tree-sitter 一定會回傳語法樹，損壞區段成為 ERROR 節點，其餘部分照常查詢。

3. **抽取規則成為資料而非程式碼。** 規則以 S-expression 查詢寫在 `src/main/resources/treesitter/*.scm`，新增框架慣例不需修改 Java 或重新編譯。這與本專案 low-code 的設計理念一致。

**JavaParser 較優之處（不應迴避）**

JavaParser 具備符號解析（symbol solver），可解析型別、import、繼承關係。tree-sitter 為純語法層工具，**永遠**不具備此能力。因此 `client.getForObject(...)` 中若 `client` 的型別宣告於父類別，tree-sitter 無法得知其為 `RestTemplate`。

**但關鍵在於：原實作並未使用 symbol solver。** 既有程式碼採用的是字串比對啟發式：

```java
// 原 CodeToolkit.java:213
boolean kafkaFile = cu.toString().contains("KafkaTemplate");
```

這在精度上已等同於 tree-sitter 層級的猜測。因此本次遷移在精度上幾乎無損失，卻換得跨語言能力與容錯性 —— 這是支持替換的核心論證。

（若未來需要對 Java 做常數摺疊，例如解析 `@KafkaListener(topics = PREFIX + "-orders")`，可再將 JavaParser 引入為 Java 專用的精修層。）

### 1.3 實作陷阱：述詞（predicate）不會被自動求值

這是本次實作中最具風險的技術細節，值得記錄。

tree-sitter 的查詢檔包含兩類語法：

```scheme
((method_invocation
   object: (identifier) @_recv
   name: (identifier) @_m
   arguments: (argument_list . (string_literal (string_fragment) @kafka-produce.topic)))
 (#match? @_recv "(?i)(kafkatemplate|producer)")     ← 述詞
 (#any-of? @_m "send" "sendDefault"))                 ← 述詞
```

- **結構比對**（上半部）由 tree-sitter 的 C 引擎執行。
- **述詞**（`#match?`、`#any-of?` 等文字層過濾）依 tree-sitter 的設計，**C 引擎僅記錄而不執行**，求值責任在 host binding。

部分 binding（如 py-tree-sitter、Neovim）會代為求值；**但 `io.github.bonede` 不會**。反編譯其 `TSQueryCursor.nextMatch()` 確認其直接呼叫 native 函式，中間無任何過濾邏輯。

**若未察覺此點的後果**：所有述詞被靜默忽略，上例實際生效的條件僅剩「任何 `x.y("字串")` 呼叫」，於是

```java
logger.send("this-is-not-a-topic");      → 誤判為 Kafka topic
metrics.record("user-clicked");          → 誤判為 Kafka topic
```

且**不會產生任何錯誤**，只會產出一份看似豐富、實則充滿虛假依賴邊的 Ledger，並據此生成報告。這是最惡劣的失效模式：不是崩潰，而是產生看似合理的錯誤結論。

**解法**：於 `TreeSitterQueryEngine` 自行實作述詞求值。bonede 有暴露述詞的原始資料（`TSQuery.getPredicateForPattern()`，回傳以 `Done` 分隔的扁平 step 陣列），僅是不代為執行。實作內容：

1. 將扁平 step 陣列還原為「運算子 + 引數」結構；
2. 實作 `#eq?` `#not-eq?` `#match?` `#not-match?` `#any-of?` `#not-any-of?`，將 capture 替換為節點的實際原始碼文字後比對；
3. 於每個 match 產出前過濾，未通過者丟棄。

**安全性決策**：遇到無法辨識的述詞時，回傳 `false`（丟棄該 match）而非 `true`（放行）。若查詢檔中出現拼字錯誤（如誤寫 `#matches?`），「放行」會使該 pattern 失去防護並開始產生假邊；「丟棄」則僅是該 pattern 無輸出，且會在 Ledger 的 warnings 區塊留下記錄。**寧可漏抓，不可錯抓。**

---

## 2. 三層抽取策略

### 2.1 架構偵測改為確定性

原流程以 LLM 詢問 DeepWiki：「這是不是 Java Spring Boot 專案？回答 YES 或 NO」，再依答案分支。這耗費一次 LLM 呼叫與一次網路往返，去猜測一件讀取 build manifest 即可確知的事實。

改為 clone 後直接讀取 manifest（`pom.xml`、`build.gradle`、`requirements.txt`、`pyproject.toml`、`go.mod`、`package.json` …）並輔以原始碼副檔名計數，產出偵測結果。

**重要設計調整**：偵測器回傳的是**語言清單**而非單一語言。微服務專案本質上常為多語言（Istio 的 bookinfo 即為 Python + Java + Ruby + Node 四種語言共存於單一 repo）。單一語言的假設會系統性地遺漏大部分服務。

### 2.2 三層路由

| 層級 | 條件 | 抽取方式 |
|---|---|---|
| **Tier 1** | 有 grammar，且有該框架的查詢包 | tree-sitter + 框架查詢包（如 `java-spring.scm`） |
| **Tier 2** | 有 grammar，但框架無法辨識 | tree-sitter + 通用查詢包（HTTP client 呼叫、URL 字面值、環境變數） |
| **Tier 3** | 無 grammar | **LLM 直接讀取原始碼** |

Tier 3 的關鍵設計在於：LLM 讀的是 **clone 下來的原始碼**，不是 DeepWiki 的文件摘要。文件會遺漏、會過期、會與實作不符；原始碼是 ground truth。

為控制成本與品質：

- **確定性 grep 預篩**：僅挑選含依賴訊號（http、kafka、amqp、grpc、getenv…）的檔案，依訊號密度排序，並設上限（40 檔 / 240KB）。
- **分批 map-reduce**，每批約 24KB。
- 要求嚴格 JSON 輸出並解析回 `EdgeLedger`；解析失敗降級為 warning，不會讓自由文字洩漏進 Ledger。
- 使用 `LLMService.callAPIFromOutside()`（無狀態）而非 `toolkitLlmCall()` —— 後者會將每次呼叫累積進使用者的對話歷史，在 map-reduce 下會撐爆 context。

### 2.3 統一輸出契約

三層皆產出同一份 `EdgeLedger`（含 `file:line` 證據與信心標記）。下游的 merge / health check / report prompt **完全不需修改**，也無從得知是哪一層執行的。多語言 repo 會同時使用多層，最終合併為單一 Ledger。

---

## 3. 斷點續跑

### 3.1 原有問題

- 檢查點為 in-memory `ConcurrentHashMap`，存放**整包**證據；重啟即失效。
- 「Pause & supplement」按鈕僅印出一句「請重跑 `get-dependency-analysis`」，代表 DeepWiki 五問、repo clone、K8s 查詢全部重來。
- **另一個實質缺陷**：`supplement-dependency-traffic` 重新查詢了 Prometheus，但**未將結果寫回 state store**。亦即使用者辛苦補充的流量資料，根本不會進入最終報告。

### 3.2 設計

將 state 由單一物件改為**分階段**（stage）結構，並持久化至磁碟（每位使用者一份 JSON，含 TTL 24 小時）：

```
docs (DeepWiki×5) ─┐
                   ├─→ merged_notes ─→ health ─→ report
code (程式碼抽取)  ─┘                    ↑
k8s ──────────────────────────────────────┤
traffic (Prometheus) ─────────────────────┘   ← 補流量時，只有這條重跑
```

各階段完成即落盤。續跑時僅重跑失效階段與其下游。

**在補充流量的情境下（最常見的暫停原因），DeepWiki 與 repo clone —— 即整條流程中最慢、最昂貴的兩個部分 —— 完全不會再執行。**

### 3.3 使用者流程

「Pause」按鈕現在保留檢查點，並附上一顆 **Resume** 按鈕。使用者去驅動流量後回來點擊 Resume，系統只重新查詢 Prometheus 與重跑完整性檢查，再次呈現檢查點決策。

實作上，Resume 按鈕透過 `CapabilityOrchestrator.performTheCapability()` 觸發 low-code 的 `resume-dependency-analysis` capability —— 如此 Prometheus 的連線設定仍留在 YAML 的 property 中，不需下放至 Java 層，維持 low-code 的設計一致性。

同時修復 3.1 所述的寫回缺陷：`resume-dependency-analysis` 會將新的流量資料寫回檢查點。

---

## 4. 驗證

| 驗證項目 | 方法 | 結果 |
|---|---|---|
| 原生庫可從 Spring Boot fat jar 載入 | 打包後實際啟動 | ✅ `[INFO] tree-sitter ready`，應用程式成功啟動 |
| 查詢檔語法正確 | `@PostConstruct` 於啟動時編譯所有查詢包 | ✅ 全數編譯通過（設計為啟動即失敗，而非執行到一半才失敗） |
| **述詞確實生效** | 樣本中植入誘餌 `logger.send("this-is-not-a-topic")`、`someRandomObject.getForObject(...)` | ✅ 誘餌**未**被抽出（若述詞未求值則必然會被誤抓） |
| **容錯解析** | 樣本中植入含語法錯誤的檔案，但保留一個合法呼叫 | ✅ 該呼叫仍被成功抽出（JavaParser 會丟棄整個檔案） |
| 多語言偵測 | 真實 repo | ✅ spring-petclinic 被正確判為 `java/spring` (Tier 1) + `javascript` (Tier 3) |
| 真實 repo 抽取 | spring-petclinic-microservices（62 個 Java 檔） | ✅ 抽出 12 條依賴邊，皆附 `file:line` 證據 |
| Capability 設定載入 | 應用程式啟動 | ✅ 無設定錯誤 |

### 4.1 一個值得記錄的 recall 觀察

在 petclinic 上初次執行僅抽出 7 條邊。檢視後發現 `VisitsServiceClient` 使用了如下慣例：

```java
private String hostname = "http://visits-service/";
...
.uri(hostname + "pets/visits?petId={petId}", ...)
```

第一個參數是 `binary_expression`（字串串接）而非字串常量，因此未被 HTTP client pattern 捕捉。這正是 1.2 節所述「無型別解析」限制的具體體現。

**但需誠實指出：原有的 JavaParser 實作同樣抽不到此案例**（其 `firstStringArg(call, 0)` 亦要求第一個參數為 `StringLiteralExpr`）。因此這不是遷移造成的退步。

**改進**：新增一條針對字串串接的 pattern，捕捉 path 部分，並標記為 `Medium (path only; host comes from a variable or property)` —— 明確表達「知道有一個外呼、知道路徑，但無法從此運算式確知目標主機」，而不謊稱知道。host 本身則由通用查詢包以 URL 字面值形式另行捕捉（同一檔案內），交由下游 LLM 關聯。

改進後 recall 由 7 條提升至 12 條。

### 4.2 未完成的驗證

**Tier 3（LLM 抽取）尚未對真實 API 端到端驗證。** 已驗證的僅為其確定性部分：候選檔篩選、批次切分、JSON 解析、失敗降級。實際對 OpenAI API 的呼叫需要金鑰，尚未執行。此路徑需在一個不支援的架構（如 Go 或 Node 專案）上實測後方可確認。

---

## 5. 已知限制

1. **無型別解析。** tree-sitter 為純語法工具。若 HTTP client 的型別宣告於父類別、或目標主機經由變數間接傳入，會降低 recall。已在 Ledger 的 Notes 區塊明確聲明此限制，避免報告過度宣稱。

2. **glibc 綁定。** base image 不得改為 alpine/musl。

3. **執行緒安全。** `TSParser` 與 `TSQueryCursor` 非執行緒安全，實作中以 `ThreadLocal` 持有 parser、每次查詢建立新 cursor；僅編譯後的 `TSQuery` 與 `TSLanguage` 為不可變且共享。

4. **Tier 3 成本。** 雖有預篩與上限，但一個大型 Node/Go 專案仍會產生數次 LLM 呼叫。

---

## 6. 後續工作

- 對 Tier 3 進行真實端到端驗證（建議以 Go 或 Node 微服務專案為標的）。
- 依實測需求擴充查詢包（Go、Node、C# 的 grammar 皆已存在於同一 groupId，加入成本低）。
- 若 Java 的抽取精度成為瓶頸，可將 JavaParser 重新引入為 Java 專用的精修層（利用其 symbol solver 做型別解析與常數摺疊），與 tree-sitter 的廣度互補。
- 容器化部署時，需將 `./dep-state` 掛載為 volume，檢查點方能跨重啟保留。

---

## 附錄：主要檔案異動

**新增**

```
Service/DependencyAnalysis/CodeExtraction/
  ├─ CodeExtractionService.java    三層路由與合併
  ├─ StackDetector.java            manifest 架構偵測（多語言）
  ├─ TreeSitterExtractor.java      Tier 1/2；含啟動冒煙測試
  ├─ TreeSitterQueryEngine.java    查詢執行 + 述詞求值（見 1.3）
  ├─ LlmCodeExtractor.java         Tier 3
  ├─ ConfigExtractor.java          設定檔中的服務位址
  ├─ EdgeLedger.java               三層共用的輸出契約
  ├─ RepoWorkspace.java            共用的 shallow clone
  └─ SourceScanner.java            共用的檔案掃描與忽略規則

resources/treesitter/{java-spring, java-generic, python-web, python-generic}.scm
resources/prompts/code_extraction_llm.txt
```

**修改**

```
Dockerfile                        安裝 git（見第 0 節）；標註 glibc 限制
pom.xml                           移除 javaparser，加入 tree-sitter
CodeToolkit.java                  改為委派；toolkit-code-extract-java → toolkit-code-extract
DependencyAnalysisStateStore.java 分階段 + 落盤 + TTL
DepstateToolkit.java              start / put / get / checkpoint
ButtonListener.java               Resume 按鈕
DependencyReportService.java      改讀分階段檢查點
capability/devops-tool/dependency.yml
                                  移除 DeepWiki YES/NO 偵測；分階段落盤；
                                  以 resume-dependency-analysis 取代
                                  supplement-dependency-traffic（並修復未寫回的缺陷）
```
