# 報告產生後的對話問答（Report Q&A）設計與實作

> 回應老師 2026/09 的反饋：**報告產生後，讓使用者可以對話詢問細節（可用 RAG）**。
> 程式碼在 `Service/DependencyAnalysis/Qa/`，prompt 在 `prompts/report_qa.txt`。

## 1. 要解的問題

DepWeaver 產出一份長報告（十一節）、一張圖、一段覆蓋率。讀的人接著會問：
「ledgerwriter 什麼時候會叫 balancereader？」「哪些邊沒被流量跑到？」「改 userservice 會影響誰？」
「這個 8187 是什麼意思？」——這些答案**都在證據裡**，但散在報告、圖、ledger 三處。

現況有三個阻礙：

| 阻礙 | 說明 |
|---|---|
| checkpoint 產完報告就刪 | `generateAndPost` 最後 `stateStore.remove(userId)`，事後沒有東西可查 |
| 對話入口是 intent 分類 | @bot → 分類 capability → Perform 按鈕。一個動作按一次合理，一個問題按一次不合理 |
| 沒有檢索基礎 | repo 內只有 `toolkitLlmTableSearch` 的關鍵字比對，沒有 embedding／向量 |

## 2. 方案：GraphRAG——確定性檢索、LLM 只講人話

沿用整條線的原則（報告第 5 節、覆蓋率分母都是這樣改過來的）：**能用程式算的絕不問模型**。
RAG 在這裡分兩層，模型只拿到兩層的產出，負責措辭。

```
使用者在 thread 打一句話
        │
        ├─ ① Graph grounding（程式碼，權威）
        │     問句 → 偵測到的節點 → 事實表：進出邊 / provenance / confidence /
        │     observed+count / deployed / tier / 傳遞閉包（影響範圍、啟動前置）/
        │     兩節點間直接邊或最短路徑；全圖摘要含 tier 推出的啟動順序
        │
        ├─ ② Passage retrieval（程式碼 + 可選 embedding）
        │     報告 + 證據 notes 依標題切 chunk → BM25 ∪ cosine → RRF 融合 → top-k
        │
        └─ ③ LLM（report_qa.txt）
              只准用 ①②，權威序 ① > 覆蓋率 > ②；沒有就說沒有；回問句的語言
```

### 2.1 知識庫：`ReportArchive`

報告貼出的當下、checkpoint 刪掉之前，另存一份 archive（`./dep-reports/<user>-<ms>.json`，預設 7 天 TTL）：

- 報告全文（含程式碼產生的第 5 節）
- `DependencyGraph.toJson()`——為此補了 `DependencyGraph.fromJson()`，圖能從自己的 JSON 回來
- 覆蓋率訊息（`coverageMessage` 從原本只貼不回傳改成回傳字串）
- 人讀得懂的證據 stage：merged notes、code extraction、k8s notes、Istio HTTP/egress ledger、traffic run report、health check
  （raw Prometheus JSON 不存——圖已經是它的結構化結果；`user_values` 之類的祕密也不存）
- chunk 清單（含 embedding 向量，有的話）
- 對話歷史

### 2.2 入口：報告底下自動開 Discord thread

報告、圖、覆蓋率貼完後，bot 再貼一則「💬 Ask DepWeaver about this report」並在它底下開一條 **public thread**，
thread 名稱 `Ask DepWeaver · <repo>`。`MessageListener` 收到 guild 內任何 thread 的訊息時，先問
`ReportQaService.isQaThread(threadId)`；是的話直接交給 Q&A，**不用 @bot、不走 intent 分類**。

為什麼是 thread 而不是加一個 `dependency-query` capability：
- 問題天然黏在它所屬的報告底下；一個 user 前後跑兩個專案，兩條 thread 各自對應各自的 archive。
- 多輪追問有自然邊界（歷史存在 archive 裡，餵回最近 6 輪）。
- 不用按鈕。

thread 開頭會貼幾個**用真實節點名**組出來的範例問題（挑 degree 最大的 service）。

### 2.3 檢索

**切 chunk（`ReportChunker`）**：依 Markdown 標題切，`#`/`##` 是 section、`###` 是 sub，每個 chunk 帶
標題路徑（例：`report › 4. Synchronous Dependency Candidates › Candidate: frontend -> userservice`）。
超過 1800 字的段落再依空行→行→硬切。確定性、零依賴。

**排序（`ChunkRetriever`）**：
- **BM25**：永遠有。tokenizer 針對這份語料做兩件事——連字號 id 整個 + 拆開都索引
  （`accounts-db` → `accounts-db, accounts, db`）；CJK 用單字 + bigram，所以**中文問句也能命中英文報告**。
- **Embedding**：`LLMService.embed()` 打 OpenAI embeddings（`text-embedding-3-small`，URL 從 chat URL
  推導或 `openai.api.embedding-url` 指定）。archive 建立時整批算一次存起來；提問時只 embed 問句。
  任何失敗都退回純 BM25，是降級不是錯誤。
- **融合**：Reciprocal Rank Fusion（k=60），不需要校準 BM25 分數與 cosine；沒向量時等於純 BM25。
- 預設 top-k 8、passages 總量 24k 字。

### 2.4 Grounding（`GraphGrounding`）

- **偵測節點**：id 本身、分隔符變空白、分隔符移除、去 `ts-` 前綴／`-service` 後綴的核心名。
  邊界只看 ASCII 字母數字，所以 `請問frontend依賴誰` 抓得到 `frontend`，`frontends` 不算。
- **事實表**每個節點一份（最多 4 個）：kind、deployed、image/replicas、tier；outgoing／incoming 每條邊一行
  `a -> b [type]; confidence=…; provenance=…; runtime observed: YES (n requests | n TCP connections — a connection count, not requests) | no; evidence: …`；
  兩個方向的傳遞閉包（「它壞了誰受影響」「它要跑起來誰得先在」）。
- **兩節點關係**：直接邊，否則 BFS 最短路徑，否則明講兩邊都不可達。
- **全圖摘要**：節點數依 kind、邊數依三層信心、未部署清單、零邊節點清單、tier 列表，以及
  **由 tier 反推的啟動／部署順序**（最深層先）。這一條就是 8/14 講的 `deploy-order()`。

### 2.5 Prompt 的約束（`report_qa.txt`）

只准用 CONTEXT；權威序 graph facts > coverage > passages（passages 是 LLM 寫的報告文字，會飄）；
不准發明服務／邊／數字／檔名；三層信心不准升級；DB 數字是連線數不是流量；greenfield 沒有 runtime 事實；
沒有就說「報告沒有」並指出什麼證據才會有；用問句的語言回答；不用表格（Discord 不渲染）。

## 3. 改到的既有程式

| 檔案 | 改動 |
|---|---|
| `DependencyReportService` | 注入 `ReportQaService`；`postRuntimeGraph` 回傳覆蓋率字串；刪 checkpoint 前呼叫 `openQaThread` |
| `DependencyGraph` | 新增 `fromJson()` |
| `LLMService` | 新增 `embed(List<String>)`，`EMBEDDING_MODEL/URL` 設定 |
| `JDAService` | 新增 `sendChatOpsChannelMessageAndOpenThread`、`sendThreadMessage` |
| `MessageListener` | thread 訊息先判 `isQaThread` → 走 Q&A，否則原流程 |
| `application*.properties` | `dependency.qa.dir/ttl-days/embeddings/top-k`、`openai.api.embedding-model` |
| `docker-compose.yaml` | 掛 `./dep-reports:/app/dep-reports` |

## 4. 測試（33 條，全過；全套 151 run，僅 `McpToolkitCallToolTest` 因需 docker 內 `k8s-mcp-server` 而失敗，與此無關）

- `ReportChunkerTest`：標題路徑、重設 sub、超長切分不漏行、無標題前言
- `ChunkRetrieverTest`：連字號 id 整體+拆開、CJK bigram、中文問句命中英文段、無命中回空、RRF 融合、預算
- `GraphGroundingTest`：各種拼法的節點偵測、事實表內容（連線數措辭）、未觀測邊、部署狀態、關係／最短路徑、全圖摘要與啟動順序、有環不迴圈
- `ReportArchiveStoreTest`：JSON 往返（含向量、圖）、重啟後靠 thread 找回、TTL 過期刪檔、索引更新
- `ReportQaContextTest`：context 權威順序、命中報告與 notes 的對應段、greenfield 措辭、Discord 2000 字切分、範例問題

## 5. 部署與驗證步驤

1. Discord bot 權限要多兩項：**Create Public Threads**、**Send Messages in Threads**。
2. 機器 B：`git pull && docker compose build --no-cache chatops4msa && docker compose up -d --force-recreate chatops4msa`
   （compose 已加 `dep-reports` volume）。
3. 跑一次分析 → Generate report → 應看到報告、圖、覆蓋率之後多一則「💬 Ask DepWeaver…」且下面掛著 thread，
   thread 內有範例問題。
4. 在 thread 內問（中英皆可），例如：
   - `ledgerwriter 什麼時候會呼叫 balancereader？證據是什麼？`
   - `Which edges were declared but never observed at runtime?`
   - `改 userservice 會影響誰？`
   - `部署順序建議？`
   - 故意問不存在的服務（`paymentservice 依賴誰？`）→ 應回「圖上沒有這個節點」並列出相近 id，不編造。
5. log 會印 `[DEBUG] report Q&A from <user> on <repo>: <question>` 與 embeddings 的 `[Used Token]`；
   若 embeddings 打不到會印 `retrieval is lexical only`，功能仍可用。

## 6. 已知界線與下一步

- **這一版是「一次 LLM 呼叫」的 GraphRAG**：grounding 靠節點偵測 + 事實表把大部分依賴問題的答案直接鋪出來。
  8/14 設計裡「NL → 圖查詢 DSL → 執行器」那個**多一步 LLM 產結構化查詢**的版本還沒做；目前 impact／startup-needs／
  deploy-order 都是「事實表順帶附上」而非「按問題選算子」。若問題沒點名任何節點（例如「哪些服務只被文件提到？」），
  只能靠摘要與 passages 回答。下一步：加一個小型查詢層（LLM 產 `{op, args}` → 確定性執行）。
- 節點偵測是字串比對，同義詞（「前端」→ frontend）抓不到；prompt 要求模型遇到未命中就列相近 id。
- archive 不存 raw Prometheus JSON 與 Tier 3 使用者填的值；問「某條邊的 Prometheus 原始資料」答不出，這是刻意的。
- thread 訊息不經 prompt-injection 檢查（原主頻道有）；Q&A 的 system prompt 已把範圍鎖在報告，且它不會觸發任何動作。
