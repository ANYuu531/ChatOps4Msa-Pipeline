# DepWeaver 門檻設計說明與實驗

> 回應 2026-09-14 老師反饋「門檻的設計要說明以及做實驗」。
> 範圍（作者決定）：**語意路由的三個門檻做完整實驗**；**檢索 top-k 做小型消融實驗**；其餘門檻與上限做成總表逐一說明。
> 本文所有實驗數字都**留白**，等機器 B 跑完再填（步驟見第 7 節）。沒有跑過的東西不寫數字。

---

## 1. 分類

DepWeaver 裡「寫死的數字」性質並不一樣，需要的論證也不同。先分三類：

| 類別 | 定義 | 需要的論證 |
|---|---|---|
| **(a) 決策門檻** | 數值一變，系統的**判斷**就跟著變：選哪個意圖、要不要呼叫 LLM、覆蓋率要不要警告、context 放哪幾段 | 要有**目標函數**和**實驗**；至少要說明兩個方向的錯分別會怎樣 |
| **(b) 資源／工程上限** | 為了 context 長度、成本、延遲、外部平台限制（Discord 2000 字、modal 最多 5 欄）而設的上限，平常碰不到，碰到時是截斷或降級 | 說明**估算依據**與**碰到時的降級行為**；除非會影響正確性，否則不必做實驗 |
| **(c) 慣例常數** | 直接採用文獻或標準的預設值 | 引出處；不改就不必做實驗，改了才要 |

同一個數字可以同時有兩種性質，例如 top-k 既是決策門檻（決定模型看到哪些證據），也是資源上限（context 長度），表中歸在主要那一類。

---

## 2. 總表

欄位說明：**太高／太低**是指這個數字定錯時會出什麼事；**決定方式**分成「實驗校準」「文獻慣例」「工程估算」「平台限制」；**實驗**欄寫要不要做，後面附理由。位置以 2026-09-14 的程式碼為準。

### 2.1 (a) 決策門檻

| 名稱 | 值 | 位置 | 作用 | 太高 | 太低 | 決定方式 | 實驗 |
|---|---|---|---|---|---|---|---|
| 路由門檻 T（threshold） | 0.58 | `SemanticRouter.java:151`、`application.properties:50`、`ReportQaService.java:111` | 最高分意圖的 cosine 至少要到這個值才可能「有把握」 | 有把握比例下降，多數問題交給 LLM planner（多一次小呼叫，答案仍有根據） | 意思不相干的問題也被有把握地路由 → 跳過 planner、查錯圖 → **漏答** | 實驗校準（舊：33 句；新：第 3 節） | **要**，第 3 節主實驗 |
| 路由領先差距 M（margin） | 0.04 | `SemanticRouter.java:152`、`application.properties:51`、`ReportQaService.java:112` | 最高分要領先第二名至少這麼多，才算有把握 | 兩個意圖分數接近但正確的問題被判沒把握 | 兩個意圖打平（0.652 vs 0.651）時硬選一個 → 有一半機率選錯 | 實驗校準 | **要**，與 T 一起掃描 |
| 路由高分豁免 H（high） | 0.85 | `SemanticRouter.java:154`、`application.properties:52`、`ReportQaService.java:113` | 最高分到這個值就不看 M | 等於停用豁免，例句原句等級的問題若第二名也很近（例如方向相反的 dependents-of）會被 M 擋掉 | 分數中上但兩意圖接近的問題跳過 M 的保護 → 有把握但錯 | 實驗校準（H∈{0.80…0.95, 停用}） | **要**，與 T、M 一起掃描 |
| 需節點意圖要有節點 | 0／1／2／1..8 個 | `SemanticRouter.java:282`、`:286`、`:290` | 需要節點的意圖若問句沒點名節點，視為沒把握 | —（布林規則） | — | 設計規則：沒有節點就產不出查詢 | 不另做；在掃描裡依「有沒有點名節點」分組觀察 |
| 否定詞／方向詞規則 | regex | `SemanticRouter.java:136`、`:140`、`:143` | 修正 embedding 看不出來的功能詞（沒觀測到→uncovered；誰依賴 X vs X 依賴誰） | — | — | 真環境錯誤驅動（2026-09-08 第一輪 12 題） | 不另做；掃描用的判定函式就是正式環境的 `decide()`，規則已包含在內 |
| 檢索 top-k | 8 | `application.properties:42`、`ReportQaService.java:110` | 放進 context 的段落數上限 | context 變長、雜訊段落稀釋關鍵段、成本增加 | 關鍵段落進不了 context → 模型說「報告沒有」或只憑圖回答 | 工程估算（未實驗） | **要**，第 4 節小型消融 |
| thin-evidence 警告 | `mentionedOnly > total + dbTotal` | `Graph/CoverageAnalyzer.java:124-126` | 未計分邊比計分邊多時，覆蓋率訊息加警告 | 該警告時不警告：抽取稀疏的專案覆蓋率虛高而沒提醒 | 抽取正常的專案也被警告，警告失去意義 | 規則式比較，無自由參數 | 暫不做，理由見第 5 節 |
| router-then-planner 開關 | `dependency.qa.query-planner=true` | `application.properties:46` | 路由沒把握時是否呼叫 planner | — | — | 功能開關 | 不需要 |

### 2.2 (b) 資源／工程上限

| 名稱 | 值 | 位置 | 作用 | 太高 | 太低 | 決定方式 | 實驗 |
|---|---|---|---|---|---|---|---|
| passage 字數預算 | 24000 字 | `ReportQaService.java:65` | 段落總字數上限（第一段一定保留） | context 過長、成本 | top-k 還沒到就被截，等於變相降低 k | 工程估算：8 段 × 最長 1800 字 ≈ 14k，留一倍餘裕給 evidence 段 | 不另做；第 4 節消融同時記錄平均 context 字數，可檢查預算是否常被碰到 |
| chunk 最大長度 | 1800 字 | `Qa/ReportChunker.java:24` | 超過就依空行→行→硬切 | 單段混入多個主題，BM25／embedding 都變鈍 | 一個 Candidate 區塊被切散，檢索只拿到一半 | 工程估算：報告 `### Candidate` 區塊多在此長度內，Discord 一則 2000 字 | 不做；改動會使所有 archive 重建，列為威脅效度 |
| 事實表最多幾個節點 | 4 | `Qa/GraphGrounding.java:46` | 問句點名超過 4 個節點時只給前 4 份事實表 | context 被事實表淹沒 | 多節點問題缺事實 | 工程估算：問句通常點名 1–2 個 | 不需要；碰到時仍有全圖摘要 |
| 傳遞閉包列出上限 | 40 | `Qa/GraphGrounding.java:48` | 閉包清單截斷，數量照寫 | train-ticket 大閉包佔滿 context | 答「影響誰」時列不全（總數仍正確） | 工程估算：train-ticket 53 節點下仍可讀 | 不需要 |
| planner 最多幾條查詢 | 3 | `Qa/GraphQueryPlanner.java:33` | LLM 計畫超過 3 條就截 | context 變成「關於一切的查詢」 | 複合問題少一塊 | 工程估算 | 不需要 |
| planner 提示字數 | 4000 字 | `Qa/GraphQueryPlanner.java:96` | 給 planner 看的段落摘錄上限（subgraph 功能新增） | planner 呼叫變貴 | 找不到描述流程的段落 | 工程估算 | 不需要 |
| planner 提示段落數 | 3 | `Qa/ReportQaService.java:67` | 同上，段落數 | 同上 | 同上 | 工程估算 | 不需要 |
| 每則回答最多幾張圖 | 2 | `Qa/ReportQaService.java:69` | 部分圖張數上限 | Discord 洗版 | 多流程問題少一張圖 | 工程估算：計畫最多 3 條查詢 | 不需要 |
| subgraph 種子上限 | 8 | `Qa/GraphQuery.java:30` | 一張部分圖最多幾個起點 | 部分圖退化成全圖 | 流程服務數多時漏節點 | 工程估算：「一個流程是幾個服務，不是整個系統」 | 不需要 |
| 部分圖節點上限 | 20 | `Graph/SubgraphExtractor.java:42` | 部分圖最多 20 節點 | 聊天訊息裡看不清（train-ticket 53 節點就是反例） | 流程被截 | 工程估算 | 不需要 |
| 部分圖種子間最長路徑 | 4 跳 | `Graph/SubgraphExtractor.java:44` | 種子相距超過 4 跳就不連 | 不相干的種子被長路徑硬連 | 真實流程被拆成兩塊 | 工程估算 | 不需要 |
| 對話歷史回傳輪數 | 6 輪 | `Qa/ReportQaService.java:63` | 餵回模型的 user+assistant 對數 | context 變長、舊話題干擾 | 追問接不上 | 工程估算 | 不需要 |
| 對話歷史保存則數 | 40 則 | `Qa/ReportQaService.java:64` | archive 內保留的訊息數 | archive 檔案變大 | 只影響保存，不影響回答（回答只用 6 輪） | 工程估算 | 不需要 |
| Discord 分段長度 | 1900 字 | `Qa/ReportQaService.java:61` | 回答超過就分則 | 超過 2000 字 Discord 拒收 | 分則過多 | **平台限制**（Discord 2000 字） | 不需要 |
| Q&A worker 數 | 2 | `Qa/ReportQaService.java:96` | 同時回答的 thread 數；同一 thread 依序 | OpenAI rate limit | 多 thread 排隊 | 工程估算：研究室規模 | 不需要 |
| archive 保存期限 | 7 天 | `application.properties:40`、`Qa/ReportArchiveStore.java:52` | 過期就回「請重跑分析」 | 磁碟累積 | 報告還在討論就不能問 | 工程估算：一週一次 meeting | 不需要 |
| archive 目錄掃描間隔 | 1 分鐘 | `Qa/ReportArchiveStore.java:40` | thread id 快取沒命中時最多每分鐘掃一次 | 新 thread 最多等 1 分鐘才認得 | 每則訊息都掃目錄 | 工程估算 | 不需要 |
| checkpoint 保存期限 | 24 小時 | `DependencyAnalysisStateStore.java:153` | Pause & supplement 後可 Resume 的時限 | 含 secret 的 checkpoint 留太久 | 補證據來不及 | 工程估算 | 不需要 |
| LLM HTTP timeout | 連線 15s／讀取 180s | `NLPService/LLMService.java:288-289` | 避免無聲卡死 | 卡住時使用者等太久 | 長報告生成被中斷 | 真環境事故驅動（bot 回 processing… 不動） | 不需要 |
| embedding HTTP timeout／批次 | 15s／60s；64 筆 | `NLPService/EmbeddingClient.java:67-68`、`:26` | 同上；每批送幾筆 | — | — | 工程估算 | 不需要；失敗會退回純 BM25 |
| 流量步驟上限 | 60 步 | `Traffic/TrafficRunner.java:56` | 一份 Postman collection 最多執行幾步 | 錯誤腳本跑太久 | 深呼叫鏈的旅程被截 → 覆蓋率變低 | 工程估算 | 不需要；截斷會在流量報告註明 |
| 單一請求 timeout | 20s | `Traffic/TrafficRunner.java:55` | 單步 HTTP 逾時 | 卡住的服務拖慢整輪 | 慢服務被當失敗 | 工程估算 | 不需要 |
| 回應內容節錄 | 400 字 | `Traffic/TrafficRunner.java:57` | 報告裡回應 body 的節錄長度 | 報告冗長 | 看不出錯誤原因 | 工程估算 | 不需要 |
| 遙測沉澱等待 | 20s | `Traffic/TrafficRunner.java:67` | 流量打完後等 Prometheus scrape 再查 | 每輪多等 | 最後幾個請求（通常是最深的邊）還沒進 Prometheus → 邊被漏掉 | **平台估算**：Istio Prometheus 預設 scrape 15s，取略大於一個週期 | 不需要；原理性下限（見程式碼註解） |
| Tier 3 每個表單最多問幾項 | 5 | `Entity/ToolkitFunction/DepstateToolkit.java:40` | 多出來的下一輪再問 | Discord 拒絕建立 modal | — | **平台限制**（Discord modal 最多 5 個輸入） | 不需要 |
| LLM 讀碼上限 | 40 檔、240KB、每批 24KB、每檔 400 行 | `CodeExtraction/LlmCodeExtractor.java:47-50` | 無 grammar 語言退回 LLM 讀碼時的上限 | 成本、context 超限 | 大 repo 漏讀 → 邊變少（thin-evidence 的主要來源） | 工程估算 | 暫不做；見威脅效度 |
| 範例請求蒐集上限 | 12 檔、每檔 8KB、總計 24KB | `CodeExtraction/ExampleRequestHarvester.java:59-61` | Tier 1 從 repo 撿範例請求的上限 | prompt 太長 | 少了範例、流量生成較難命中 | 工程估算 | 不需要 |
| tree-sitter 區段上限 | url 60、config 80、其他 200 | `CodeExtraction/TreeSitterExtractor.java:40-44` | 單一 repo 某類抽取結果的條數上限 | 報告被雜訊淹沒 | 真實呼叫點被截 | 工程估算 | 不需要 |
| 原始碼檔案大小上限 | 512KB | `CodeExtraction/SourceScanner.java:33` | 略過 minified／產生出來的檔案 | 解析巨型產生檔 | 真正的大原始檔被略過 | 工程估算 | 不需要 |
| manifest 大小上限 | 512KB | `CodeExtraction/StackDetector.java:43` | 同上 | — | — | 工程估算 | 不需要 |
| config 值長度 | 200 字 | `CodeExtraction/ConfigExtractor.java:24` | 截斷過長的設定值 | — | — | 工程估算 | 不需要 |
| clone timeout | 120s | `CodeExtraction/RepoWorkspace.java:23` | git clone 逾時 | — | train-ticket 這種大 repo clone 失敗 | 工程估算 | 不需要 |
| Graphviz／Prometheus／MCP timeout | 15s／15s／30s（MCP 重試 3 次、間隔 2s×次數） | `Graph/GraphvizRenderer.java:21`、`ToolkitFunction/PrometheusToolkit.java:38`、`ToolkitFunction/McpToolkit.java:79-97` | 外部程序或服務逾時 | — | — | 工程估算 | 不需要 |

### 2.3 (c) 慣例常數

| 名稱 | 值 | 位置 | 作用 | 出處 | 實驗 |
|---|---|---|---|---|---|
| BM25 k1 | 1.2 | `Qa/ChunkRetriever.java:36` | 詞頻飽和速度 | Robertson & Zaragoza (2009) *The Probabilistic Relevance Framework: BM25 and Beyond*；Lucene／Elasticsearch 預設值 | 不做：語料只有一份報告（數十到一兩百段），調 k1/b 的差異會小於標註誤差 |
| BM25 b | 0.75 | `Qa/ChunkRetriever.java:37` | 文件長度正規化強度 | 同上 | 同上 |
| RRF k | 60 | `Qa/ChunkRetriever.java:38` | 融合時壓低排名差距 | Cormack, Clarke & Büttcher (2009) *Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods*（SIGIR）原文取 60 | 不另做 k 掃描；第 4 節比較「BM25 only／embedding only／RRF」三種模式，已回答「融合是否有幫助」 |
| DNS label／主機名長度 | 63／253 | `Graph/DocGraphMerger.java:269`、`:276` | 判斷字串像不像 k8s 服務名／外部主機 | RFC 1123 | 不需要 |

> RRF 的 k 雖然影響排序，但它不是「判斷」門檻：沒有一個 k 值會讓某段落「被拒絕」，只是改變兩個排名互相妥協的方式。所以歸在慣例常數。

---

## 3. 路由門檻專章（主實驗）

### 3.1 判定式

`SemanticRouter.decide()`（`SemanticRouter.java:239` 起）在拿到每個意圖的分數（該意圖所有例句與問句 cosine 的最大值）之後：

1. 取最高分意圖 best 與第二名 second；
2. 套兩條規則（否定詞、方向詞），只改 best 的意圖名，不改分數；
3. **有把握** ⇔ `best ≥ H` 或（`best ≥ T` 且 `best − second ≥ M`）；
4. 需要節點的意圖若問句沒點名節點，改判沒把握；
5. 有把握 → 直接產生圖查詢、不呼叫 planner；沒把握 → LLM planner。

### 3.2 三個條件各防哪一種錯

| 條件 | 防的錯 | 例子 |
|---|---|---|
| `best ≥ T` | **沒有對應意圖的問題**被硬塞一個意圖。這類問題的最高分落在較低的一帶（舊資料 0.38–0.50），T 要切在它上方 | 「frontend 平均回應時間多少？」不該被路由成 dependencies-of |
| `best − second ≥ M` | **兩個意圖打平**時硬選。分數夠高但與第二名幾乎一樣，代表例句本身無法區分，這時交給看得到節點 id 的 planner 比較穩 | 2026-09-08 實測「前端依賴誰」最高分是反向的 dependents-of，靠 margin 擋下（後來加了方向詞規則） |
| `best ≥ H` 不看 M | **例句原句等級的問題被 M 誤擋**。問句幾乎就是某句例句時（0.97–1.00），第二名也可能很高（方向相反的姊妹意圖），但此時沒有歧義 | 「X 依賴誰？」 dependencies-of 1.00、dependents-of 0.86 |

### 3.3 兩個方向的錯代價不對稱

| 錯的方向 | 發生什麼 | 代價 |
|---|---|---|
| 太嚴（該有把握卻沒把握） | 交給 LLM planner：多一次小的 chat 呼叫；planner 看得到節點 id，仍會產生圖查詢；答案仍有根據 | 延遲與成本小幅增加 |
| 太鬆（有把握但錯） | 跳過 planner、執行**錯的**圖查詢；context 裡缺了問題真正需要的事實；模型被禁止編造，所以結果是「漏答」或答非所問 | 答案品質直接受損，而且使用者看不出是路由錯 |

因此目標不是「準確率最高」，而是：

> **目標函數：在「有把握但錯 = 0」的限制下，讓「有把握比例（coverage）」最大。**
> 另報「有把握但錯 ≤ 1 句」下的最佳組合，作為敏感度參考。

### 3.4 選定規則（寫在程式碼裡，不是事後挑）

`SemanticRouterThresholdSweepTest.select()`：

1. 過濾：有把握但錯 ≤ 上限（主結果上限 = 0）；
2. coverage 最大；
3. 平手依序選：有把握但錯較少 → **T 最高** → **M 最大** → **H 最高**。

平手偏保守的理由：102 句的樣本只能看出「有一段平台上的組合表現一樣好」，看不出平台邊緣在哪裡；選平台保守的那一端，換一批問句時比較不容易掉出去。這條規則有離線單元測試固定住（`selectionMaximisesCoverageUnderTheWrongBudgetAndBreaksTiesConservatively`）。

### 3.5 實驗設計

**資料集** `src/test/resources/qa/router-holdout.tsv`（欄位：project、question、expected_intents、note）：

| | 內容 |
|---|---|
| 總數 | 102 句；中文 51、英文 51 |
| 專案 | bank-of-anthos 34、train-ticket 34、sock-shop 34 |
| 每個意圖 | 14 個既有意圖各 6 句（中英各 3、三個專案各 2） |
| `none` | 10 句，圖回答不了（延遲、CPU、授權、翻譯、閒聊…）；**只要不是有把握的路由就算對** |
| `subgraph` | 8 句（另一人新增的意圖）；其中 3 句只講流程、不點名節點，依設計永遠交給 planner |
| 新句子保證 | `RouterHoldoutDatasetTest`（離線）檢查：與 `SemanticRouter.INTENTS` 所有例句、舊 `SemanticRouterCalibrationTest` 的 33 句，**原文與遮名後兩種形式**都不完全重複（正規化大小寫、空白、標點後比對），也不幾乎一樣（字元 bigram Jaccard < 0.8）；每個意圖 ≥ 5 句；需節點的意圖句子確實點名了節點 |

`subgraph` 意圖若之後被移除或改名，該列會自動視為「目前沒有對應意圖」並依 `none` 的方式計分，summary 會標出。

**節點集與遮名**：每句用所屬專案的節點集建圖，走正式環境同一條路：`GraphGrounding.mentionedNodes` → `maskMentions` → 遮名後的句子 embed → `SemanticRouter.scores`。train-ticket 的節點集取自 `docs/train-ticket-greenfield-graph.mmd`（排除抽取殘留的 `null`），**刻意保留** `ts-common`、`bin` 這類雜訊節點，因為正式環境的圖也有。

**與正式環境一致的保證**：每句只算一次分數，再用 `SemanticRouter.decide()` 在網格上重播；程式在重播前先斷言「目前門檻下 `decide()` 的意圖與判定」和正式入口 `routeQuestion()` 完全一樣，不一樣就整個實驗失敗。

**embedding 快取**：`target/qa-calibration/embeddings-cache.json`，key = sha256(模型名 + 文字)，例句、原句、遮名句都存。重跑掃描不打 API；換模型 key 就不同，不會誤用舊向量。

**網格**：T ∈ [0.40, 0.85] 步進 0.01（46 值）× M ∈ [0, 0.12] 步進 0.01（13 值）× H ∈ {0.80, 0.85, 0.90, 0.95, 停用(1.01)} = 2990 組。

**指標**（每組、每個分組各算一份）：

| 指標 | 定義 |
|---|---|
| coverage（有把握比例） | 有把握的句數 ÷ 全部句數 |
| 有把握但錯 | 有把握且 best 不在 expected 內（`none` 列：只要有把握就算錯）；報句數與比例 |
| 有把握時的精準度 | 有把握且對 ÷ 有把握 |
| top-1 準確率 | 規則修正後的 best 在 expected 內 ÷ 有對應意圖的句數（與門檻無關） |
| useful coverage | 有把握且對 ÷ 有對應意圖的句數 |

**分組**：全部／依專案（3）／依語言（2）／依有沒有點名節點（2）。

**分數帶分布**（min／p10／中位數／p90／max）：例句原句（自己對自己，應接近 1.00，作為上界參考）、例句 leave-one-out（離自己最近的**其他**例句，衡量例句之間的區分度）、hold-out 中 top-1 對的、top-1 錯的、`none`。

**leave-one-project-out**：用兩個專案的句子以同一條選定規則選門檻，在第三個專案上驗證 coverage 與有把握但錯。回答「門檻是不是只對某個專案的命名方式有效」。

**產出**：`target/qa-calibration/sweep.csv`（每組 × 每分組）、`per-question.csv`（每句的遮名結果、best/second 與分數、目前門檻下的判定）、`summary.md`。

### 3.6 結果（待機器 B 實測後填入）

**目前門檻 T=0.58／M=0.04／H=0.85**

| 分組 | n | coverage | 有把握但錯 | 精準度 | top-1 |
|---|---|---|---|---|---|
| 全部 | 102 | ＿ | ＿ | ＿ | ＿ |
| bank-of-anthos | 34 | ＿ | ＿ | ＿ | ＿ |
| train-ticket | 34 | ＿ | ＿ | ＿ | ＿ |
| sock-shop | 34 | ＿ | ＿ | ＿ | ＿ |
| 中文／英文 | 51／51 | ＿／＿ | ＿／＿ | ＿／＿ | ＿／＿ |
| 有點名節點／沒有 | ＿／＿ | ＿／＿ | ＿／＿ | ＿／＿ | ＿／＿ |

**選定組合**

| 限制 | 選出的 T／M／H | coverage | 有把握但錯 | 精準度 |
|---|---|---|---|---|
| 有把握但錯 = 0 | ＿／＿／＿ | ＿ | 0 | ＿ |
| 有把握但錯 ≤ 1 | ＿／＿／＿ | ＿ | ＿ | ＿ |

**分數帶**

| 帶 | n | min | p10 | 中位數 | p90 | max |
|---|---|---|---|---|---|---|
| 例句原句 | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| 例句 leave-one-out | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| 換句話說、top-1 對 | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| 換句話說、top-1 錯 | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| 無對應意圖（none） | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |

**leave-one-project-out**

| 驗證專案 | 用另外兩個選出的 T／M／H | 驗證 coverage | 驗證有把握但錯 |
|---|---|---|---|
| bank-of-anthos | ＿ | ＿ | ＿ |
| train-ticket | ＿ | ＿ | ＿ |
| sock-shop | ＿ | ＿ | ＿ |

**解讀（填數字後再寫）**：＿

### 3.7 為什麼舊的校準不夠

- 33 句、只有 BoA 節點集、只在單一門檻上印結果，沒有掃描，看不出門檻附近的敏感度。
- 其中 5 句後來被加進例句（污染），真正的 hold-out 只有 28 句（`docs/report-qa-design.md` 第 2.4b 節）。
- 門檻歷史：0.55／0.03／0.72（未校準）→ 0.60／0.05／0.85（2026-09-08 第一輪 12 題真環境）→ 0.58／0.04／0.85（33 句校準，「最低正確分數 0.541」）。每次都是人看數字決定，沒有寫下的選定規則。

---

## 4. 檢索 top-k 小型消融

### 4.1 問題

`top-k=8` 是工程估算。k 太小，關鍵段落進不了 context；k 太大，雜訊段落稀釋關鍵段、context 變長。另外也順便回答：BM25 與 embedding 融合（RRF）是否真的比單用其中一種好。

### 4.2 設計

- **語料**：機器 B `./dep-reports/` 裡一份真實 archive（`ReportArchive.fromJson` 格式），用 `-Dqa.archive=` 指定。真實報告的標題結構才有意義，玩具語料不行。
- **標註**：`src/test/resources/qa/retrieval-labels.tsv`，每列 `question ⇥ 相關段落 heading 路徑子字串（| 分隔）`；段落的「source › title」包含任一子字串（不分大小寫）就算相關。
  **目前 15 列是範例**，子字串是依 `prompts/dependency_analysis.txt` 的章節格式猜的，**不是已驗證的標註**。第一次跑會輸出 `target/qa-calibration/archive-headings.txt`，要照實際 heading 修正後再跑一次；對不到任何段落的標註列會在 md 中列出並排除。
- **變因**：k ∈ {2, 4, 6, 8, 10, 12, 16} × 模式 {BM25 only, embedding only, RRF}。三種模式都經過正式環境同一段「排序後挑選」邏輯（到 k 為止、超過 24000 字預算就跳過、第一段必留），差別只在排序；這一點有離線測試比對 `ChunkRetriever.retrieve` 的輸出。
- **指標**：Recall@k（相關段落被取回的比例）、MRR@k（第一個相關段落排名的倒數；k 內沒有就 0）、Hit@k、平均 context 字數。
- **前提**：embedding only 與 RRF 需要 archive 本身有段落向量，而且問句要用**同一個 embedding 模型**；否則只跑 BM25 並在 md 註明。

### 4.3 選 k 的準則（跑之前先寫下）

取 **RRF 模式下 Recall@k 達到最高值的 95% 時最小的 k**；若該 k 與 8 的 Recall 差距小於一題（15 題標註時約 0.07），維持 8，不為了雜訊調整。

### 4.4 結果（待機器 B 實測後填入）

| 模式 | k=2 | 4 | 6 | 8 | 10 | 12 | 16 |
|---|---|---|---|---|---|---|---|
| BM25 Recall@k | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| embedding Recall@k | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| RRF Recall@k | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| RRF MRR@k | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |
| RRF 平均 context 字數 | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ | ＿ |

- archive：＿（repo、mode、chunk 數）
- 有效標註題數：＿／15
- 結論：＿

---

## 5. 規則式門檻：thin-evidence

### 5.1 規則

`CoverageAnalyzer.Report.isThinlyEvidenced()`：`mentionedOnly > total + dbTotal`，即「沒有使用證據、被排除在分母外的邊」比「計分的業務邊 + 資料層邊」還多時，覆蓋率訊息加警告（`DependencyReportService.java:462`、`DepstateToolkit.java:342`）。它**只加警告，不改分數**。

### 5.2 為什麼有這條規則

排除 `inferred`（點線、只被提到）是為了不讓文件幻覺稀釋覆蓋率：BoA 某次 run 的 DeepWiki 多講了 3 條 `userservice → ledger service` 與 4 條指向泛稱 `postgresql` 的邊，系統與流量都沒變，覆蓋率卻從 7/7 掉到 7/10（出處：`CoverageAnalyzer.java` 類別註解與 `isEvidenced()` 註解；8/25 反饋紀錄）。
但排除會帶來反方向的風險：如果抽取層幾乎沒抓到東西、文件層卻講很多，分母只剩幾條，跑出 100% 也只涵蓋系統一小角（`docs/meeting-script-2026-09-03.md` P18）。所以規定「可以排除，但不准靜默」，並在未計分的邊佔多數時警告。

### 5.3 為什麼選這個比較式

- **沒有自由參數**：`>` 的意思就是「多數」。任何其他形式（例如未計分比例 > 30%、或絕對條數 > 5）都要多一個數字，而那個數字需要有「抽取稀疏」的正例才能校準。
- **兩邊用同一個單位（邊數）**：不必把邊數換算成服務數或程式碼行數。
- **只是警告**：定錯的代價是一行訊息多了或少了，不會改變覆蓋率，也不會改變任何流量或部署決策，所以採用最簡單、可解釋的形式。

已知不精確之處：`mentionedOnly` 算的是**所有** inferred 邊（包含 async、external），而分母只含業務同步邊與資料層邊，兩邊的母體不完全相同；在 inferred 邊大多指向外部主機的專案可能提早警告。

### 5.4 現有真環境數據能說明什麼

| 案例 | 計分（業務 + 資料層） | 未計分 | 是否警告 | 出處 |
|---|---|---|---|---|
| BoA runtime（DeepWiki 幻覺那次） | 以最終分母 7 + 5 = 12 估算 | 7（3 + 4） | 否（7 < 12），符合預期：那次的分母本身是實的 | `CoverageAnalyzer.java` 註解；`teacher-feedback-2026-08-25` 紀錄（7/7、5/5）。**注意**：12 取自最終成績，不是同一次 run 的完整計數 |
| train-ticket greenfield | 51 條皆為 `documented`（虛線） | 0 | 否 | `docs/meeting-script-2026-09-03.md` P10「全部停在 documented」 |
| petclinic runtime | 4 | 未紀錄 | 未紀錄 | `petclinic-final-run-report-2026-07-29` 只記 4/4 |

**尚無實驗，理由**：目前三個真環境案例都是抽取正常的專案（Java／Python，有 tree-sitter grammar），**沒有任何一個應該被警告的正例**，無法估計漏報率，也就無法比較不同比較式。要驗證需要一個沒有 grammar、主要靠 LLM 讀碼或文件的專案（例如 Go 或 Node 的微服務範例），這列為後續工作。

---

## 6. 威脅效度

**建構效度**
- 例句與 hold-out 都是同一個人（作者）寫的。寫法習慣會同時出現在兩邊，分數可能比真實使用者的問法高。緩解：hold-out 用程式檢查不與例句重複或幾乎一樣；三個專案、中英各半；但無法消除作者本身的偏差。**更強的做法是收集真環境 thread 的真實問句作為第二份 hold-out**（log 裡已有 `graph query plan (router …)`）。
- `none` 類別是作者想像出來的「圖回答不了的問題」，真實使用者的離題問法分布未知。
- 「有把握但錯」只看意圖對不對，沒有看最後回答對不對；有些意圖錯了但查詢結果剛好涵蓋答案（例如 impact-of 與 dependents-of 有部分重疊），本實驗會算成錯，偏保守。

**內部效度**
- 門檻選定規則在看到結果前就寫進程式碼，避免事後挑數字；但 hold-out 句子是在已知目前例句的情況下寫的，無法完全排除「刻意避開難句」或「刻意寫難句」。
- 例句之後若因實驗結果修改，這份 hold-out 就被看過了，下一輪要換新句子（上一輪 5 句污染就是這樣發生的）。

**外部效度**
- **embedding 模型**：分數分布取決於 `text-embedding-3-small`；換模型（或 OpenAI 更新同名模型）要整個重跑。快取 key 含模型名，重跑時會自動重新 embed。
- **樣本數**：102 句、每個意圖 6 句；「有把握但錯 = 0」在 102 句上成立，依 rule of three，真實錯誤率的 95% 信賴上界約為 3／有把握句數，不代表母體為零。
- **專案**：三個專案都是英文命名的微服務範例；中文服務名、縮寫名（`acct-svc`）沒涵蓋。
- **節點命名與遮名互相影響**：train-ticket 的 `ts-order-service` 會把英文句子裡的 order 也遮成 X；sock-shop 的 `user` 同理。hold-out 刻意保留少數這種句子，但比例是作者決定的。
- **意圖集合會變**：`subgraph` 正在加入，每多一個意圖，既有意圖的第二名分數可能上升，M 的最佳值會變；**每次新增或修改例句都要重跑本實驗**。
- **top-k 消融**只用一份 archive、15 題、作者標註；結果只能說明這份報告的結構，不能推廣到所有專案。

---

## 7. 機器 B 執行步驟

前提：機器 B 的 `src/main/resources/application.properties` 有可用額度的 `openai.api.key`，`openai.api.embedding-model` 與產生 archive 時相同。

### 7.1 路由門檻掃描

```bash
cd ~/ChatOps4Msa-Pipeline && git pull

docker run --rm -v "$PWD":/build -w /build maven:3.9-eclipse-temurin-17 \
  mvn -q -Dqa.calibrate=true -Dtest=SemanticRouterThresholdSweepTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

cat target/qa-calibration/summary.md
```

- 第一次會 embed 約 150 句例句 + 102 句原句 + 遮名句（幾百個短字串，費用很低）；之後重跑全部命中快取，`embedding cache: … 0 remote calls`。
- 可加 `-v "$HOME/.m2":/root/.m2` 避免每次重新下載相依套件。
- **不要 `mvn clean`**，會把 `target/qa-calibration/` 的快取一起刪掉；要保留可先 `cp target/qa-calibration/embeddings-cache.json ~/`。
- 容器以 root 寫 `target/`，之後在主機上跑 maven 若遇到權限錯誤：`sudo chown -R $USER target`。
- 若重播與正式入口不一致，測試會失敗並印出是哪一句——這代表 `decide()` 與 `routeQuestion()` 的行為分岔，要先修程式，不能填數字。

### 7.2 top-k 消融

```bash
ls -t dep-reports/*.json | head -3      # 挑一份 runtime 模式、有 embedding 的 archive

docker run --rm -v "$PWD":/build -w /build maven:3.9-eclipse-temurin-17 \
  mvn -q -Dqa.archive=dep-reports/<檔名>.json -Dtest=ChunkRetrieverTopKAblationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

cat target/qa-calibration/archive-headings.txt   # 對照實際 heading
```

1. 第一次跑完，照 `archive-headings.txt` 修正 `src/test/resources/qa/retrieval-labels.tsv` 的子字串（`topk-ablation.md` 最後會列出對不到任何段落的標註），把第一行註解改成「已依 archive <檔名> 標註」。
2. 再跑一次同樣指令（問句向量已快取），看 `target/qa-calibration/topk-ablation.md`。

### 7.3 把數字填回本文件

| summary 位置 | 填到 |
|---|---|
| `summary.md` 的 `## Current thresholds` 表 | 3.6「目前門檻」表 |
| `## Selected: confident-wrong = 0` 與 `≤ 1` 的粗體組合及其 `all` 列 | 3.6「選定組合」表 |
| `## Score bands` 表 | 3.6「分數帶」表 |
| `## Leave-one-project-out` 表 | 3.6「leave-one-project-out」表 |
| `## Confident-wrong rows at the current thresholds` | 3.6「解讀」：逐句說明是例句問題、規則問題還是遮名問題 |
| `topk-ablation.md` 表 | 4.4 |

同時更新 `docs/meeting-script-2026-09-10.md` P7 與 `docs/report-qa-design.md` 第 2.4b 節裡「0.97–1.00／0.64–0.73／0.38–0.50」那組舊數字，並註明來源改為本實驗。

### 7.4 改門檻

1. 依 3.4 的規則選出的組合寫進 `src/main/resources/application.properties`（機器 B 的那份，以及 `application-template.properties` 的預設值）：
   ```properties
   dependency.qa.router.threshold=<T>
   dependency.qa.router.margin=<M>
   dependency.qa.router.high=<H>      # 選到「停用」時填 1.01
   ```
   `SemanticRouter.java` 的 `DEFAULT_*` 與上方的校準註解、`ReportQaService` 的 `@Value` 預設值一併更新，避免設定檔缺值時退回舊數字。
2. **要重編**：`application.properties` 是在 image build 時打包進 jar 的（`Dockerfile` 的 `COPY src`，compose 沒有掛載它），改完要
   `docker compose build chatops4msa && docker compose up -d --no-deps chatops4msa`（不要用 `--build`，會連 k8s-mcp-server 一起重編）。
   （`report-qa-design.md` 第 5 節第 6 點寫「不用重編」，在目前的 compose 設定下不成立。）
3. 若 top-k 要改：`dependency.qa.top-k=<k>`，同樣要重編。
4. 重跑 `SemanticRouterCalibrationTest`（舊 33 句）確認沒有新的有把握但錯，作為回歸檢查。

---

## 附錄：本次新增／修改的程式

| 檔案 | 內容 |
|---|---|
| `Qa/SemanticRouter.java` | `route()` 改為呼叫新的 `static decide(scores, question, mentioned, T, M, H)`；原本 `route()` 裡「拿到分數之後」的邏輯原封不動移進 `decide()`。公開簽章不變 |
| `test/.../Qa/CalibrationSupport.java` | 讀 hold-out／標註 TSV、三個專案節點集、embedding 快取、CSV 工具 |
| `test/.../Qa/RouterHoldoutDatasetTest.java` | 離線：資料集格式、分布、與例句及舊 33 句不重複 |
| `test/.../Qa/SemanticRouterThresholdSweepTest.java` | `-Dqa.calibrate=true` 才跑的掃描；離線測試固定指標定義與選定規則 |
| `test/.../Qa/ChunkRetrieverTopKAblationTest.java` | `-Dqa.archive=` 才跑的消融；離線測試確認挑選邏輯與 `retrieve()` 一致、指標計算 |
| `test/resources/qa/router-holdout.tsv` | 102 句 hold-out |
| `test/resources/qa/retrieval-labels.tsv` | 15 列**範例**標註（待依實際 archive 修正） |
