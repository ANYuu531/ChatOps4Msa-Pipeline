# DepWeaver 門檻設計說明與實驗

> 回應 2026-09-14 老師反饋「門檻的設計要說明以及做實驗」。
> 範圍（作者決定）：**語意路由的三個門檻做完整實驗**；**檢索 top-k 做消融實驗**；其餘門檻與上限做成總表逐一說明。
>
> **2026-09-18 增補**，回應同一次會議的後四點反饋：
> - 第 2 點「4 跳、20 個節點要有原因」→ 第 8 節（實驗已在本機跑完，有數字；跳數的依據是**外部 22 個真實系統**的實測深度，不只自家兩張圖）
> - 第 3 點「用純 AI 測試看看，比較結果」→ 第 9 節（程式已寫好，待機器 B 跑）
> - 第 4 點「參數設定要畫成曲線圖」→ 第 10 節（產生器已寫好，子圖三張已產出）
> - 第 5 點「top-k 要繼續測試更多的」→ 第 4.3 節換掉選 k 規則、網格擴到語料全長；**2026-09-19 機器 B 已重跑**，曲線在 k=28 出現平台，依規則仍選 16（第 4.4 節）
> - 第 1 點「搜尋證據分級相關的論文」→ 另一份文件 `docs/evidence-grading-related-work.md`
>
> 沒有跑過的東西不寫數字；每一節都標明數字是本機跑的、機器 B 跑的，還是待跑。

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
| 檢索 top-k | **16** | `application.properties` 的 `dependency.qa.top-k`、`ReportQaService.java:110` | 放進 context 的段落數上限 | context 變長、雜訊段落稀釋關鍵段（Precision@k 掉到 0.09）、擠掉事實表 | 關鍵段落進不了 context → 模型說「報告沒有」或只憑圖回答 | 消融實驗，網格到語料全長（第 4.4 節，2026-09-19 第三輪） | 已做，第 4 節 |
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
| 部分圖節點上限 | 20 | `Graph/SubgraphExtractor.java:43` | 部分圖最多 20 節點 | 聊天訊息裡看不清（train-ticket 53 節點就是反例） | 流程被截 | **文獻上限（Ghoniem 2005：超過 20 個頂點，節點連結圖在多數任務輸給矩陣）＋ 真圖量測** | **已做，第 8 節**（原本寫「工程估算／不需要」，2026-09-18 補） |
| 部分圖種子間最長路徑 | 4 跳 | `Graph/SubgraphExtractor.java:45` | 種子相距超過 4 跳就不連 | 不相干的種子被長路徑硬連 | 真實流程被拆成兩塊 | **22 個真實系統的實測最大直徑（3）+1**，並驗證放寬不影響圖的大小 | **已做，第 8 節**（同上） |
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

### 3.6 結果（2026-09-14 機器 B，`text-embedding-3-small`，15 個意圖含 `subgraph`）

**舊門檻 T=0.58／M=0.04／H=0.85**

| 分組 | n | coverage | 有把握但錯 | 精準度 | top-1 |
|---|---|---|---|---|---|
| 全部 | 102 | 46.1% | **10（9.8%）** | 78.7% | 73.9% |
| bank-of-anthos | 34 | 44.1% | 2（5.9%） | 86.7% | 73.3% |
| train-ticket | 34 | 44.1% | 5（14.7%） | 66.7% | 64.5% |
| sock-shop | 34 | 50.0% | 3（8.8%） | 82.4% | 83.9% |
| 中文／英文 | 51／51 | 51.0%／41.2% | 6／4 | 76.9%／81.0% | 73.9%／73.9% |
| 有點名節點／沒有 | 43／59 | 41.9%／49.2% | 4／6 | 77.8%／79.3% | 71.1%／75.9% |

**選定組合**（3.4 的規則，程式選出，不是人挑）

| 限制 | 選出的 T／M／H | coverage | 有把握但錯 | 精準度 | useful coverage |
|---|---|---|---|---|---|
| 有把握但錯 = 0 | **0.51／0.10／0.80** | 30.4% | 0 | 100.0% | 33.7% |
| 有把握但錯 ≤ 1 | 0.51／0.08／0.80 | 38.2% | 1（1.0%） | 97.4% | 41.3% |

選定組合在各分組都是 0 錯；coverage：BoA 35.3%、train-ticket 26.5%、sock-shop 29.4%，中文 29.4%、英文 31.4%，有點名節點 23.3%、沒有 35.6%。

**分數帶**（遮名後的最高意圖分數）

| 帶 | n | min | p10 | 中位數 | p90 | max |
|---|---|---|---|---|---|---|
| 例句原句 | 151 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| 例句 leave-one-out（最近的**其他**例句） | 151 | 0.335 | 0.513 | 0.667 | 0.836 | 0.937 |
| hold-out、top-1 對 | 68 | 0.466 | 0.505 | 0.641 | 0.786 | 0.911 |
| hold-out、top-1 錯 | 24 | 0.458 | 0.499 | 0.592 | 0.696 | 0.784 |
| 無對應意圖（none） | 10 | 0.281 | 0.281 | 0.372 | 0.507 | 0.631 |

例句 leave-one-out：最近的其他例句屬於同一意圖的只有 97／151。

**leave-one-project-out**

| 驗證專案 | 用另外兩個選出的 T／M／H | 訓練 coverage | 驗證 coverage | 驗證有把握但錯 | 驗證精準度 |
|---|---|---|---|---|---|
| bank-of-anthos | 0.51／0.10／0.80 | 27.9% | 35.3% | 0 | 100.0% |
| train-ticket | 0.51／0.08／停用 | 38.2% | 35.3% | 1 | 91.7% |
| sock-shop | 0.55／0.10／0.80 | 30.9% | 26.5% | 0 | 100.0% |

**舊門檻下有把握但錯的 10 句**

| 問句 | 應為 | 選成（分數，第二名） |
|---|---|---|
| what are the outgoing calls of ts-travel-service? | dependencies-of | dependents-of 0.78（dependencies-of 0.74） |
| ts-basic-service 一旦無法回應，會拖垮哪些服務？ | impact-of | dependencies-of 0.62（startup-needs 0.57） |
| 有哪些依賴關係目前還缺流量佐證？ | uncovered | mentioned-only 0.62（uncovered 0.53） |
| 列出實際跑流量時真的有發生的呼叫 | observed-edges | uncovered 0.64（observed-edges 0.56） |
| 有哪些依賴是經過實測確認存在的？ | observed-edges | mentioned-only 0.65（externals 0.60） |
| 圖上有、但叢集裡根本沒跑起來的是哪些？ | undeployed | uncovered 0.58（undeployed 0.54） |
| 誰把訊息丟進 rabbitmq、誰從裡面拿？ | async | dependents-of 0.60（async 0.54） |
| show me the dotted, unconfirmed edges | mentioned-only | observed-edges 0.59（mentioned-only 0.52） |
| are any dependencies based on docs alone rather than code? | mentioned-only | externals 0.58（db-users 0.52） |
| what programming language is ledgerwriter written in? | none | dependents-of 0.63（dependencies-of 0.58） |

**解讀**

1. **舊的「33/33、0 錯」是高估。** 換成三個專案、全新的 102 句，舊門檻有把握但錯 9.8%，train-ticket 更到 14.7%。原因是舊校準樣本小、只有 BoA，而且句子是在看過例句後寫的。
2. **「有把握」要看領先幅度，不是看絕對分數。** top-1 對與錯的分數帶幾乎重疊：中位數 0.641 對 0.592，錯的最高 0.784 已經高於對的 p90。所以單靠 T 分不開；程式選出的組合反而把 T **降**到 0.51、把 M **拉高**到 0.10，同時 H 降到 0.80，剛好讓 0.78 那句要看 margin（0.04，不夠）。十句錯的第二名都落後不到 0.10。
3. **代價是多交給 LLM planner。** coverage 46.1% → 30.4%，約七成問題要多一次 planner 小呼叫。依 3.3 的不對稱代價，這是該付的：多一次小呼叫只是變慢，有把握地選錯是漏答。
4. **跨專案大致穩定。** leave-one-project-out 三次有兩次驗證專案 0 錯；train-ticket 當驗證集時錯 1 句（34 句中）。可預期換一批問句時，有把握但錯約在 1–3% 的量級，而不是 0。
5. **天花板在例句，不在門檻。** top-1 只有 73.9%，而且和門檻無關。錯誤集中在兩類：證據等級意圖彼此混淆（observed／uncovered／mentioned-only／undeployed 六句），以及方向（dependents-of／dependencies-of 兩句）。例句 leave-one-out 只有 97／151 最近鄰同意圖，也說明卡片之間重疊。要提高 coverage，得改例句或合併易混淆的卡片；**但這批 102 句已經看過結果，改完例句必須換一批新的 hold-out 驗證，不能用這批報成績。**
6. **選定是在同一批資料上做的（in-sample）。** leave-one-project-out 是對這點的部分補救；第 6 節的威脅效度仍然適用。

**採用**：`application-template.properties` 與程式預設值改為 T=0.51／M=0.10／H=0.80（2026-09-14）。

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

### 4.3 選 k 的準則

**第一版（2026-09-14，已作廢）**：取 RRF 模式下 Recall@k 達到最高值的 95% 時最小的 k；若與 8 的差距小於一題（約 0.07），維持 8。

**為什麼作廢**：這條規則在網格上限就是「最高值」時才有意義。網格拉到語料全長（BoA archive 80 段）之後，k = 80 的 Recall 必然是 1.0，「最高值的 95%」就退化成「幾乎讀完整份報告」。2026-09-14 那一輪選出 k=16 正是因為 16 是網格上限而不是曲線的性質——第 4.4 節第 5 點自己也寫了「k=16 是網格上限，Recall 仍在上升」。

**第二版（2026-09-18，寫在重跑之前，跑完也作廢）**：

1. 只考慮**平均 context 字數 ≤ 段落預算 75%（18 000 字）**的 k；超過就等於把預算花在檢索，事實表與對話歷史會被擠掉。
2. 在這些 k 裡，取**每多一段的邊際 Recall 增益首次低於 0.01 的前一個 k**（曲線的膝點）。

**為什麼也作廢**：真實曲線是顛簸的。2026-09-19 的實測裡，RRF 的每段邊際增益是 0.0333（2→4）、**0.0056（4→6）**、0.0247（6→8）、0.0111、0.0375、0.0261……規則在第一個坑就停住，選出 **k=4**（Recall 0.544），而 k=16 是 0.807。15 題的樣本下，逐點導數的雜訊大於訊號。

**第三版（2026-09-19，現行）**——`ChunkRetrieverTopKAblationTest.selectK()`：

1. **可負擔集合**：平均 context ≤ 段落預算的 75%（18 000 字）。（這一條是第二版就寫下的，沒有改。）
2. **平台值**＝可負擔集合裡最高的 Recall。
3. 取 **Recall ≥ 平台值 95%** 的**最小** k。Recall 對 k 單調不遞減，所以這條規則不受曲線顛簸影響；而「平台值」取自可負擔集合，也避免了第一版「整份報告塞進去」的退化。

**這一版是看過曲線之後才寫的**，文件裡必須這樣講。緩解理由：它沒有任何在這 15 題上被微調的自由參數（95% 與 75% 都沿用前兩版），但真正的檢驗是**換一份別的專案的 archive 重跑一次**——列為待辦。

網格同時擴大為 **k ∈ {2, 4, 6, 8, 10, 12, 16, 20, 24, 28, 32, 40, 48, 64, 80}**（超過語料段數的值自動捨去，並補上「全部段落」那一點），另外加記 **Precision@k** 與**實際選進的段數**（預算開始咬住時，選進的段數會小於 k）。

### 4.4 結果（2026-09-19 機器 B，第三輪：網格到語料全長）

- archive：`dep-reports/824657585331372043-1788872505120.json`（`GoogleCloudPlatform/bank-of-anthos`，greenfield，80 段，有段落向量），與第二輪同一份，標註也同一份（15 題全部對得到段落）
- 網格 k ∈ {2…80}，80 = 整份報告的段數

**RRF（正式環境用的排序）**

| k | Recall@k | 每段邊際增益 | Precision@k | 實際選進段數 | 平均 context 字數 |
|---|---|---|---|---|---|
| 2 | 0.478 | — | 0.467 | 2.0 | 1 399 |
| 4 | 0.544 | 0.0333 | 0.300 | 4.0 | 2 955 |
| 6 | 0.556 | 0.0056 | 0.211 | 6.0 | 4 692 |
| 8 | 0.605 | 0.0247 | 0.192 | 8.0 | 6 447 |
| 10 | 0.627 | 0.0111 | 0.160 | 10.0 | 8 563 |
| 12 | 0.702 | 0.0375 | 0.156 | 12.0 | 10 549 |
| **16** | **0.807** | 0.0261 | 0.138 | 16.0 | 14 421（60% 預算） |
| 20 | 0.818 | 0.0028 | 0.113 | 20.0 | 18 707（78%） |
| 24 | 0.884 | 0.0165 | 0.108 | 23.7 | 20 936（87%） |
| 28 | 0.917 | 0.0083 | 0.101 | 26.5 | 22 539（94%） |
| 32–80 | 0.917 | 0 | 0.096→0.088 | 28.6→33.2 | 23 741→26 231 |

**三種排序的平台值**

| 模式 | 最高 Recall（平台） | 在 k= | k=16 時 | MRR 飽和於 | Hit@k 最高 |
|---|---|---|---|---|---|
| BM25 | 0.589 | 24 | 0.529 | 0.368 | 0.667 |
| embedding | 0.895 | 28 | 0.780 | 0.806 | 1.000（k≥20） |
| **RRF（正式）** | **0.917** | 28 | **0.807** | 0.757 | 1.000（k≥28） |

**套用 4.3 第三版規則**：可負擔集合是 context ≤ 18 000 字者，即 k ≤ 16（k=20 的 18 707 字已超出）；其中最高 Recall = 0.807（k=16），95% = 0.767；達到它的最小 k = **16**。**維持現行的 16，不必改。**

曲線圖：`docs/charts/topk-recall.svg`、`docs/charts/topk-cost.svg`。

**解讀**

1. **曲線真的有平台，而且不在網格邊緣。** 第二輪停在 k=16、Recall 還在爬，當時無法判斷最佳值；這一輪看到 **RRF 的 Recall 在 k=28 達到 0.917 之後完全不動**（32、40、48、64、80 都是 0.917）。剩下的 0.083 是 heading 標註抓不到的段落（見第 6 點），不是 k 不夠。
2. **真正的限制是預算不是 k。** k≥24 之後「實際選進的段數」開始小於 k（k=28 只進得去 26.5 段、k=80 也只有 33.2 段），因為 24 000 字的段落預算先滿了。所以「k 開到最大」在正式環境等同「把段落預算用光」，事實表、證據表與對話歷史會被擠掉。
3. **k=16 買到平台值的 88%，只花 60% 預算。** 要再多 0.11 的 Recall（k=28 的 0.917）得花到 94% 的預算。依 4.3 的可負擔門檻（75%），16 就是答案；這也剛好是 9/14 那一輪選的值，**所以這次不改設定**。
4. **k 影響的是「湊齊」，不是「找到」。** MRR 在 k=8 就飽和（0.755），Hit@k 在 8 已經 0.933——第一個相關段落幾乎都在前 8 名。k 往上加的是 Recall，也就是需要多段證據的問題（Collection Status 有 4 段、資料庫證據分散在第 5 節與 persistence 段）能拿到完整的一組。
5. **雜訊確實會被稀釋。** Precision@k 從 k=2 的 0.467 一路掉到 k=16 的 0.138、k=80 的 0.088。這是不追求 Recall 極大值的另一個理由：多塞的段落大多與題目無關。
6. **RRF 現在明確贏過單一排序，但差距不大。** 平台值 RRF 0.917 > embedding 0.895 > BM25 0.589；k=16 時 0.807 對 0.780 對 0.529。BM25 單用明顯不夠（中文問句對英文報告對不上），但它在 embedding 打不到時是唯一的退路，且對服務 id、`file:line` 這種精確字串的價值這組題目測不太出來，**維持混合**。
7. **標註的界線**：`docs+code notes` 的 11 段沒有標題、無法用 heading 標註，答案在那裡的問題會被低估——RRF 停在 0.917 而不是 1.000，很可能就是這個原因而不是檢索失敗。這份 archive 是 greenfield，沒有流量與 k8s 階段。
8. **只有一份 archive、15 題、單一專案、作者標註。** 第三版選 k 規則又是看過這條曲線才定的，所以**換一份別的專案的 archive 重跑**是這一節最該補的驗證（例如 train-ticket 或 sock-shop 的 runtime archive）。

> **歷史**：第一輪（2026-09-14，範例標註）15 題只有 11 題對得到段落、`code extraction` 一個子字串命中 16 段，數字不可用，標註檔隨後依 `archive-headings.txt` 重寫。第二輪（同日，網格只到 16）選出 16，但那是網格上限而非曲線性質；第三輪就是為了回答「16 到底是不是最佳值」而做的。

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

### 7.5 純 AI 對照組（第 9 節）

```bash
docker run --rm -v "$PWD":/build -w /build maven:3.9-eclipse-temurin-17 \
  mvn -q -Dqa.archive=dep-reports/<檔名>.json -Dqa.baseline=true \
  -Dtest=PureLlmBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test

cat target/qa-calibration/pure-llm-baseline.md
```

每題 3 次 chat 呼叫（報告全文那一組 prompt 最長），10 題約 30 次。跑完把總表填進 9.4，逐題答案留在 md 裡當附錄。

### 7.6 畫曲線（第 10 節）

```bash
python3 docs/charts/plot_calibration.py          # 讀 target/qa-calibration/*.csv，寫 docs/charts/*.svg
```

只用標準函式庫，機器 B 不必裝 matplotlib。哪些 CSV 不在就跳過哪幾張圖。

---

## 8. 部分圖的兩個上限：20 節點與 4 跳

> 回應 2026-09-14 反饋第 2 點「4 跳、20 個節點要有原因」。原本這兩個數字在第 2.2 節被歸為「工程估算、不必做實驗」，這一節把它改成有依據的決定。
> 實驗：`SubgraphLimitsExperimentTest`（離線、不需要 API 與叢集，本機即可跑）。資料分兩批：
> - **自家兩張圖**：`docs/diagrams/fig3a-boa-layered.dot`（BoA 真實執行期）、`docs/train-ticket-greenfield-graph.mmd`（train-ticket 靜態），搭配 `src/test/resources/qa/flows.tsv` 的 10 條有文件依據的流程。
> - **外部 20 個真實系統**：MicroDepGraph 資料集（Rahman, Panichella & Taibi, SattoSE 2019；LGPL-3.0，已收進 `src/test/resources/graphs/microdepgraph/`，出處與注意事項見該目錄的 `SOURCE.md`）。**加這批的原因**：只用自家兩張圖時，兩張的直徑都是 3，任何 ≥3 的跳數上限切出來都一樣，數字無法被驗證（2026-09-18 第一版就卡在這裡）。

### 8.1 兩個上限管的是不同的事

| 上限 | 決定什麼 | 兩個方向的錯 |
|---|---|---|
| 最長路徑 4 跳 | 問句點名的兩個服務**要不要被畫成同一條流程** | 太大：不相干的服務被一條長路徑硬連，圖上出現一條其實沒人走的「流程」；太小：真實流程被拆成兩塊 |
| 節點上限 20 | 那條流程**畫多少**出來 | 太大：聊天訊息裡的圖看不清；太小：流程被截，讀者以為就這幾個服務 |

### 8.2 選定規則（跑之前先寫進程式）

- **跳數（外部語料，主要依據）**：取「p90 子圖仍在 20 節點可讀性天花板內」的**最大** h。理由：每多允許一跳，就多連得到一些問句可能點名的服務對，而長一點的連接路徑唯一的代價是圖上多幾個節點——那正好是 20 節點上限在管的事。
- **跳數（自家兩張圖，輔助）**：以 Youden's J（同流程節點對在 h 跳內連得到的比例 − 跨流程節點對被連起來的比例）最大者為準，平手取較小的 h。
- **節點**：取「讓 ≥90% 的子圖不被截斷」的最小上限，且**不得超過 20**——因為 Ghoniem, Fekete & Castagliola（*Information Visualization* 4(2), 2005）的受控實驗結論是「超過二十個頂點，矩陣式呈現在多數任務上勝過節點連結圖；只有路徑尋找（path finding）始終站在節點連結圖這邊」。部分圖要讀的正是路徑，所以形式選節點連結圖是對的，但 20 是這個形式的可讀性天花板。

### 8.3 結果 A：22 個真實系統的依賴圖有多深（外部語料）

22 張圖 ＝ MicroDepGraph 的 20 個開源微服務系統（5–25 個服務）＋ 我們的兩張。全部 568 組「有向可達的服務對」。

| | 結果 |
|---|---|
| 直徑（每張圖最長的最短路徑） | **中位數 2，最大 3**——22 張圖沒有一張超過 3 |
| 1 跳連得到 | 76.8% 的可達服務對 |
| 2 跳 | 95.6% |
| **3 跳** | **100.0%** |
| 4 跳以上 | 100.0%（沒有任何新增） |

**放寬跳數對圖的大小完全沒有影響**：對每一組兩個服務的問句、每一個跳數上限切出子圖，中位數 6 個節點、p90 **12** 個節點、最大 16 個節點，從 1 跳到 12 跳**數字完全一樣**。原因是子圖的大小由「種子的一跳鄰居」決定，不是由連接路徑決定。

因此事先寫好的規則（p90 仍 ≤20 的最大 h）在這批語料上選出的是 **12**，也就是「跳數上限在可讀性上完全不是限制因素」。

曲線圖：`docs/charts/corpus-diameters.svg`（22 張圖的直徑分布）、`corpus-reach-vs-hops.svg`（跳數對連得到的比例）、`corpus-slice-vs-hops.svg`（跳數對圖的大小）。

**4 跳的理由（現在有實證）**：

1. 量了 22 個真實系統，最深的直徑是 **3**。上限設在**實測最大深度 +1**，等於保證這條規則不會是「切斷真實流程」的原因。
2. 放寬的代價實測為 **0**：p90 子圖大小在 1–12 跳之間完全不變，所以沒有必要為了圖的可讀性把它壓低到 3 或 2。
3. 不設上限也不行：Alibaba 20 000 個微服務、100 億筆呼叫追蹤的研究（Luo et al., SoCC 2021）顯示**呼叫深度在 3 附近達到高峰，但有超過 4% 的呼叫圖深度超過 10**。真實大型系統有更深的尾巴；若沒有上限，那條尾巴會把兩個其實不相干的服務用一條 10 跳的路徑接成「一條流程」。4 跳＝涵蓋常見情況、拒絕長尾。
4. 這條規則可以被重算：換一個更深的系統，就照 8.2 的規則重跑 `SubgraphLimitsExperimentTest` 得到新的上限。數字不是拍腦袋的，是「實測最大深度 + 1，且驗證放寬不影響可讀性」這個程序的產物。

### 8.4 結果 B：自家兩張圖與 10 條文件流程

**整張圖裡兩個服務有多遠**

| 專案 | 節點 | 邊 | 有向可達的節點對 | ≤1 跳 | ≤2 | ≤3 | ≤4 | 最長 |
|---|---|---|---|---|---|---|---|---|
| bank-of-anthos（runtime） | 10 | 12 | 21 | 57% | 90% | 100% | 100% | 3 |
| train-ticket（static） | 53 | 51 | 118 | 43% | 81% | 100% | 100% | 3 |

**跳數上限**（10 條流程、338 組同流程節點對、826 組跨流程節點對）

| 跳數 | 同流程連得到 | 跨流程也被連 | J |
|---|---|---|---|
| 1 | 18.3% | 1.5% | 0.169 |
| 2 | 27.2% | 6.9% | **0.203** |
| 3 | 27.8% | 8.7% | 0.191 |
| 4 | 27.8% | 8.7% | 0.191 |
| ≥5 | 27.8% | 8.7% | 0.191 |

曲線圖：`docs/charts/subgraph-hops.svg`。

**節點上限**（242 組起點集合，先不設上限看子圖的自然大小）

| 問句點名幾個服務 | 子圖中位數 | p90 | 最大 |
|---|---|---|---|
| 1 | 5 | 9 | 9 |
| 2 | 7 | 10 | 11 |
| 3 | 9 | 12 | 13 |
| 4 | 11 | 14 | 14 |
| 6 | 15 | 18 | 19 |
| 8（`GraphQuery` 的種子上限） | 20 | 21 | 21 |

| 上限 | 完整畫出的子圖 | 被截 |
|---|---|---|
| 8 | 64% | 87/242 |
| 10 | 80% | 49/242 |
| 12 | 87% | 32/242 |
| 15 | 95% | 11/242 |
| **20** | **100%** | **1/242** |
| 25 以上 | 100% | 0/242 |

曲線圖：`docs/charts/subgraph-nodes.svg`、`docs/charts/subgraph-size-vs-seeds.svg`。

### 8.5 解讀（含對自己不利的部分）

1. **4 跳＝實測最大深度 + 1，而且放寬零代價。** 依據在 8.3：22 個真實系統的直徑最大就是 3；圖的大小對跳數完全不敏感（p90 一路都是 12 個節點）。所以 4 不是隨手取的，而是「沒有任何真實系統會被它切斷，且再放寬也不會讓圖變難讀」。上限仍要存在，是為了 Alibaba 追蹤裡那 4% 深度超過 10 的長尾。
2. **自家兩張圖上，J 規則選出的是 2 跳。** 2 跳到 3 跳只多連 2 組同流程節點對（92→94），卻多連 15 組跨流程節點對（57→72）。**但這個懲罰項要小心解讀**：跨流程被連起來的路徑在圖上是真的存在的邊，只是「被畫成一條流程」這件事會誤導。這條輔助規則與 8.3 的主要依據結論相反，兩個都照登：主要依據用外部 22 個系統（樣本大、與我們的工具無關），輔助規則只有 10 條流程、且流程成員是作者標註的。
3. **20 節點是有依據的，而且剛好咬住。** 問句點名 8 個服務時，子圖自然大小達到 21 個節點——242 組裡唯一被截的那一組。也就是說 20 不是隨手取的整數：它同時是文獻給的可讀性天花板，又正好只截掉分布的極端一點。
4. **規則選出的節點上限是 15（95% 不被截），比 20 緊。** 之所以仍採 20：這個上限的用途是**安全閥**，不是最佳化目標。在可讀性天花板以下，多畫一個節點沒有壞處，少畫一個就少一個事實；15 會讓 5% 的子圖無謂被截。這是「規則寫在前面、但結果顯示規則優化錯了東西」的誠實版本——規則的答案照登，採用的理由另外講。
5. **微服務依賴圖比想像中平。** 22 張圖裡有 9 張直徑只有 1、11 張是 2、2 張是 3。原因是這類系統多半是「閘道 ＋ 一層業務服務 ＋ 共用基礎設施」的星狀結構，不是長鏈。這件事本身值得寫進論文：**部分圖要解的問題其實是「寬」（一個服務的鄰居太多）而不是「深」**，所以節點上限才是那個會真正生效的參數（8.4 顯示它真的咬到了一次），跳數上限則是防長尾用的。

### 8.6 威脅效度（本節專屬）

- **外部語料的邊不是呼叫**：MicroDepGraph 的邊來自 Docker Compose `depends_on` 與內部 API 呼叫，是「宣告／部署」關係，而且把 kafka、mysql、consul 這類基礎設施也當成一般節點（DepWeaver 的 `GraphNormalizer` 會把其中幾類視為平台基礎設施排除）。因此那 22 張圖量到的深度，是「業務服務之間距離」的上界估計，而且完全沒有 runtime 資訊。資料集本身也是 2021 年擷取的快照。
- 更深的真實系統只有**二手數字**（Alibaba SoCC 2021 的追蹤研究），我們沒有自己跑過那份 25 GB 的追蹤資料。要自己驗證，最省的做法是在機器 A／B 上把 train-ticket 真的部署起來、用 runtime 層產生一張有呼叫鏈的圖再重跑本實驗。
- 流程成員是我照兩個專案的官方文件（BoA README 的服務表與流程說明、train-ticket README 的情境清單）對照圖上節點列的，**作者標註**；train-ticket 的流程成員有一部分是從該流程進入點的宣告呼叫推出來的，和被評估的圖同源。
- 起點集合是程式列舉出來的（單點、前六個成員的兩兩組合、3／4／6／8 個成員的滑動視窗），不是真實使用者問句；真實問句多半只點名 1–2 個，所以「8 個種子」那一列是壓力測試而不是典型情況。
- 可讀性只引文獻，**沒有做我們自己的使用者實驗**；20 是別人受控實驗的閾值，不是對 DepWeaver 圖的實測。

---

## 9. 純 AI 對照組

> 回應 2026-09-14 反饋第 3 點「用純 AI 測試看看，比較結果」。分兩層：**問答層**（本節，已實作，待機器 B 跑）與**抽圖層**（第 9.5 節，設計已定，尚未實作）。

### 9.1 問答層：三組比較

同一批問題、同一個模型、`temperature=0`：

| 組別 | 模型看得到什麼 | 代表什麼 |
|---|---|---|
| **report-only** | 報告全文（最多 60 000 字），沒有檢索、沒有路由、沒有圖 | 「直接把報告丟給 LLM 問」 |
| **rag** | 正式環境檢索器取回的段落，僅此而已 | 一般 RAG 基線 |
| **depweaver** | 正式流程：語意路由（沒把握才叫 planner）→ 圖查詢 → 確定性結果 ＋ 同一批段落 | 現行系統 |

### 9.2 怎麼評分（不靠人判斷、不靠 LLM 當裁判）

`src/test/resources/qa/answer-labels.tsv` 的每一題都是**答案為一組節點**的問題（誰呼叫 X、X 掛掉影響誰、哪些服務用資料庫、哪些邊沒有 runtime 證據……），標準答案就是圖查詢引擎對該題標註查詢的輸出。評分程式從回答文字裡抓出圖上存在的節點 id，算 precision／recall／F1，另外數「像服務名但圖上沒有」的名字當作**編造率**（近似值：抓 `a-b` 形式的小寫詞，會誤抓英文連字詞，因此只當比例看，不當成謊言計數）。

### 9.3 這個比較內建的偏袒，要寫在結果旁邊

depweaver 組拿到的正是「標準答案那條查詢」的執行結果，所以它在這批題目上本來就該贏。這個實驗量的是：**在圖能一槌定音的問題上，沒有接地的兩組差多遠、以及它們會講出什麼圖上不存在的東西**。它不能證明 depweaver 在「報告怎麼寫的」這類純文字題上也比較好——那類題目 depweaver 沒有優勢，而且目前沒有標註。

### 9.4 結果

待機器 B 跑（步驟 7.5）。曲線圖：`docs/charts/baseline-arms.svg`。

### 9.5 抽圖層（第二輪，尚未實作）

「純 AI 抽依賴圖」對照 DepWeaver 的抽取管線：同一個 repo，A 組把原始碼（或檔案清單＋摘錄）餵給 LLM，直接要它輸出服務與邊；B 組跑現行的 tree-sitter ＋ 設定解析 ＋ 文件層。以 BoA（真實架構已知、runtime 驗證過 7/7 業務邊 + 5/5 資料層邊）與 train-ticket 官方架構文件為 ground truth，比邊的 precision／recall。要先定兩件事：①ground truth 的邊清單要逐條寫下來並標出處；②A 組的預算（讀幾個檔、幾 KB）必須說明，否則比較不公平。這一輪的成本明顯較高（大量長 prompt），排在問答層之後。

---

## 10. 參數圖表（曲線）

> 回應 2026-09-14 反饋第 4 點「參數設定要畫成曲線圖，表格不容易讀」。

`docs/charts/plot_calibration.py`（只用 Python 標準函式庫，輸出 SVG，直接貼進投影片與論文）讀 `target/qa-calibration/` 的 CSV，畫：

| 圖 | 來源 | 看什麼 |
|---|---|---|
| `router-coverage-vs-t.svg` | `sweep.csv` | 有把握比例對 T，每條線一個 M（H 固定） |
| `router-wrong-vs-t.svg` | `sweep.csv` | 有把握但錯的比例對 T，同上 |
| `router-operating-curve.svg` | `sweep.csv` | 2 990 組門檻的操作曲線：橫軸「有把握但錯」、縱軸「有把握比例」，並標出選定組合與舊門檻的位置 |
| `topk-recall.svg` | `topk-ablation.csv` | Recall@k 對 k，三種排序模式 |
| `topk-cost.svg` | `topk-ablation.csv` | k 的代價（context 字數）與預算線、75% 預算線 |
| `subgraph-hops.svg` | `subgraph-limits.csv` | 同流程／跨流程連通率與 J 對跳數 |
| `subgraph-nodes.svg` | `subgraph-limits.csv` | 完整畫出的子圖比例對節點上限，附 90% 規則線與 20 節點可讀性天花板 |
| `subgraph-size-vs-seeds.svg` | `subgraph-limits.csv` | 子圖自然大小（中位數／p90／最大）對問句點名的服務數 |
| `corpus-diameters.svg` | `subgraph-hops-external.csv` | 22 個真實系統的依賴圖直徑分布（最大就是 3） |
| `corpus-reach-vs-hops.svg` | `subgraph-hops-external.csv` | 跳數上限連得到多少比例的可達服務對（3 跳即 100%） |
| `corpus-slice-vs-hops.svg` | `subgraph-hops-external.csv` | 跳數上限對子圖大小的影響（1–12 跳完全相同） |
| `baseline-arms.svg` | `pure-llm-baseline.csv` | 三組對照的 precision／recall |

配色用經過色盲檢查的類別色（藍、橘、青、黃、洋紅；`node scripts/validate_palette.js` 全數通過，對比度偏低的三色以線尾直接標示序列名補償），每張圖都有圖例＋線尾標籤，識別不只靠顏色。

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

### 2026-09-18 新增（回應 9/14 反饋的第 2、3、4、5 點）

| 檔案 | 內容 |
|---|---|
| `Graph/SubgraphExtractor.java` | 多一個 `extract(graph, seeds, maxNodes, maxPathHops)` 多載讓實驗掃描上限；正式路徑仍呼叫兩參數版本、行為不變（有測試比對兩者輸出相同） |
| `test/.../Graph/GraphFile.java` | 把 DepWeaver 自己輸出的 `.mmd`／`.dot` 讀回成 `DependencyGraph`，實驗才能跑在真圖上 |
| `test/.../Graph/SubgraphLimitsExperimentTest.java` | 第 8 節的兩個實驗（自家圖＋外部 22 個系統，離線、本機可跑）＋ 選定規則與解析器的離線測試 |
| `test/resources/qa/flows.tsv` | 10 條有文件出處的業務流程（成員、起點、出處） |
| `test/resources/graphs/microdepgraph/*.graphml`＋`SOURCE.md` | 外部語料：MicroDepGraph 的 20 個真實系統依賴圖（LGPL-3.0，附出處、擷取日期與「這些邊代表什麼」的警語） |
| `test/.../Qa/ChunkRetrieverTopKAblationTest.java` | 網格擴大到語料全長、加 Precision@k 與實際段數、第 4.3 節第二版選 k 規則（`selectK`）＋其離線測試 |
| `test/.../Qa/PureLlmBaselineTest.java` | 第 9 節三組對照（`-Dqa.archive=… -Dqa.baseline=true` 才跑）＋評分函式的離線測試 |
| `test/.../Qa/ChatCompletions.java` | 實驗用的 chat 呼叫（同一個端點、模型、temperature 0），不需要 Spring context |
| `test/resources/qa/answer-labels.tsv` | 20 題「答案是一組節點」的問題與其標註查詢 |
| `docs/charts/plot_calibration.py` | 第 10 節的曲線圖產生器（純標準函式庫，輸出 SVG） |
| `docs/evidence-grading-related-work.md` | 反饋第 1 點的文獻整理 |
