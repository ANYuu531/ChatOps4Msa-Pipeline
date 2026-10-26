> **2026-10-20：這份 Markdown 稿已過時**，Word 稿的來源是 `docs/papers/src/compsac.txt`（含 RQ0、吻合率 0.98、測試數 280 等更新），以它為準。

# DepWeaver：以多來源融合、信心分級與對話式補值建構微服務相依圖

> **2026-10-13：最新版改為 Word，見 `docs/papers/compsac-2027-zh.docx`（原始檔 `docs/papers/src/compsac.txt`）。** 本檔停在 10/12，不再更新。

**DepWeaver: Multi-Source Fusion with Confidence Grading and Conversational Gap-Filling for Microservice Dependency Graph Construction**

> **這是什麼**：投 IEEE COMPSAC 的技術論文**中文完整稿**（2026-10-12 起草）。老師 10/09 之後的反饋：AsianPLoP 那篇裡「我們自己的解法」寫得太獨特、不適合當 pattern，改成寫詳細的技術論文投 COMPSAC；pattern 那篇改寫成「論文有、業界有、我們也用」的通用做法（`docs/asianplop-paper-zh.md` 重寫中）。
> **兩篇的分工**：本篇講**方法與實驗**——DepWeaver 怎麼做、量到什麼、哪裡會失敗；pattern 那篇講**通用的設計知識**，DepWeaver 只是 Known Uses 之一。兩篇引用同一批實驗，但本篇的主張是「這個方法有效」，pattern 那篇的主張是「大家都這樣做，原因在這裡」。
> **格式**：中文稿先給老師看，定稿後翻成英文、套 IEEE 雙欄模板。投稿規定見文末「附錄 C」。
> **用詞**：「相依圖」與碩論題目一致；「執行期觀測」第一次出現時定義（§III-B）；信心等級一律由低到高講。

---

## 摘要

微服務系統的相依圖是部署排序、變更影響分析與測試規劃的基礎，但沒有任何單一來源能完整地重建它：執行期觀測只看得到這段期間真的發生過的呼叫，靜態分析看不到動態分派與沒有文法的語言，專案文件則可能過時或錯誤。把多個來源合在一起可以提高完整度，卻帶來另一個問題：讀者分不出圖上哪一條關係是量到的、哪一條只是有人提過，覆蓋率數字與 LLM 寫的報告也可能和圖互相矛盾。

本文提出 **DepWeaver**，一個整合在 ChatOps（Discord）裡的相依圖建構方法與工具。它 (1) 從**執行期觀測**（Istio 服務網格的呼叫計數）、**靜態分析**（tree-sitter 抽取程式碼呼叫點、設定檔位址與部署描述的佈線）以及**專案文件**三個來源抽取關係，以「只被提到 < 有使用證據 < 量測到」三級**信心等級**加上逐條**出處**（provenance）融合成同一個標準模型；(2) 圖、覆蓋率、報告都由這個模型產生，報告中的事實段落由程式寫、LLM 只寫詮釋，覆蓋率只算量測機制做得到的關係，沒量測的值明說「未量測」而不是 0；(3) 自動手段都用盡時，才在對話中**問人**：缺少的流量參數（如密碼、收款帳號）與對不上的服務名稱。

我們在 9 個開源微服務系統上評估。對照第三方發表的 MicroDepGraph 資料集，7 個系統的 95 條參考邊中，靜態層畫到 91 條（一致度 0.96）；對照作者整理、每條附可複驗出處的參考邊集，7 個系統中有 6 個 precision 為 1.00。在真實部署的 Bank of Anthos 上，融合執行期觀測後業務呼叫覆蓋率 7/7、資料層 5/5，其中最後兩條邊是工具在對話中向操作者要到值之後才補上的；在 spring-cloud-microservice-examples 上，回答 3 個名稱問題讓一致度從 24/26 提高到 26/26。

**關鍵詞**：微服務、架構回復、相依圖、靜態與動態分析、服務網格、信心等級、provenance、ChatOps、大型語言模型、人在迴圈

---

## I. 緒論

### A. 動機

微服務系統由數十到數百個獨立部署的服務組成，服務之間透過 HTTP、gRPC、訊息佇列與共用資料庫互相依賴。這張相依圖決定了部署順序（誰要先起來）、變更的影響範圍（改一個服務要通知誰）、以及測試要補哪裡。實務上它很少被完整地記錄下來：架構圖在第一次發佈之後就開始過時，程式碼裡的呼叫散落在數十個 repo、數種語言裡，而真正跑起來的流量又只覆蓋其中一部分。

自動重建相依圖的研究可以依來源分三類（§II）。**動態方法**從分散式追蹤、服務網格或網路流量看到真的發生過的呼叫，precision 高，但只看得到這段期間被驅動到的路徑。**靜態方法**讀原始碼、設定檔與部署描述，看得全，但受限於語言文法、動態分派與「宣告了但沒用」的設定。**文件或 LLM 方法**能補到前兩者看不到的東西，但最容易出錯。比較研究已經量到單一來源的不完整：靜態呼叫圖相對於實際執行的 recall 中位數約 0.88 [Sui 2020]；在微服務架構回復工具的比較中，單一最佳工具 F1 為 0.86，把四個工具的結果合起來可以到 0.91 [Schneider 2025]。

所以融合是必要的。但融合之後產生了三個新的問題，這是本文要處理的：

1. **等級被抹平**。文件提過一句的關係和執行期量到幾千次的關係在圖上長得一樣，讀者無從判斷哪條可以據以決策。
2. **產物互相矛盾**。圖、覆蓋率數字、LLM 寫的報告各自從原始來源推導，報告寫「未觀測」而圖畫實線；覆蓋率的分母混進量不到的項目，產生假的低分或假的高分。
3. **有些缺口只有人知道答案**。要驅動某條路徑需要一組只有操作者知道的值（例如這個部署的密碼）；文件裡的某個名稱到底指哪個服務，規則與 LLM 都只能猜。

### B. 本文的方法

DepWeaver 把相依圖的建構拆成「收集 → 融合 → 產出 → 補值」四個階段，並遵守一條原則：**能由程式從證據算出來的，就不交給 LLM；LLM 只處理規則對不上的殘餘與最後的文字敘述**。它以 ChatOps 的形式運作：操作者在 Discord 下一個斜線指令，工具在背景收集證據、驅動流量、查詢量測，遇到只有人知道的事就在同一個對話裡開口問，最後貼回一張分層相依圖、覆蓋率與報告。

### C. 貢獻

1. **三來源、三級信心的融合模型**（§III-B、III-C）。執行期觀測、靜態分析與文件以（來源服務, 目標服務）為鍵合併；每條邊帶一個有序的信心等級（只被提到 < 有使用證據 < 量測到）與完整的出處清單（`檔案:行`、指標名稱、文件段落），合併時等級取最高、出處取聯集，圖上以點線／虛線／實線呈現。
2. **由同一模型產生、彼此一致的產物**（§III-D）。圖、覆蓋率、報告的事實段落與事後問答都是同一個模型的函式；事實段落由程式產生後拼進 LLM 寫的報告；覆蓋率的分母只算量測機制做得到、且信心高於「只被提到」的關係，被排除的逐項揭露；沒量測的狀態以三值表示。
3. **最後才問人的對話式補值**（§III-E）。依序嘗試：從 repo 挖範例請求 → 把錯誤回應餵回去讓 LLM 修正 → 在對話中問操作者 → 誠實標記無法到達。名稱對不上時同樣不丟棄、不模糊比對，而是列出候選讓人選；答案以專案為單位保存，下次只問新出現的名稱。
4. **在 9 個開源系統上的評估**（§IV），包含一份**不是作者標註**的第三方參考邊集、逐條可複驗的作者參考邊集、一個真實部署的執行期驗證，以及問人前後的對照。

### D. 研究問題

- **RQ1（靜態層的泛化）**：只用靜態來源，DepWeaver 在沒有調校過的系統上能重建多少相依？錯在哪裡？
- **RQ2（融合執行期觀測）**：加入執行期觀測之後，信心等級與覆蓋率怎麼變化？
- **RQ3（對話式補值）**：在自動手段用盡之後問人，能補回多少？要問幾題？
- **RQ4（產物的一致性）**：§III-D 的設計規則擋住了哪些實際發生過的錯誤？

---

## II. 背景與相關研究

### A. 微服務架構回復

**靜態方法**讀原始碼、設定檔與部署描述：Bushong 等人以靜態分析重建整個微服務網的架構 [Bushong 2022]；Code2DFD 在原始碼與設定檔中尋找關鍵字當作證據，產生資料流圖 [Schneider & Scandariato 2023]；MicroDepGraph 從 Docker Compose 檔取得相依 [Rahman 2019]；Soldani 等人從 Kubernetes 部署描述挖掘架構 [Soldani 2022]。**動態方法**從系統已經在收集的日誌、追蹤與指標重建執行期視圖 [Al Maruf 2022; Gortney 2022]，或直接觀察 socket 取得行程之間的網路相依 [Tsubouchi 2020]。**混合方法**結合靜態的服務資訊與執行期資訊，理由是有些關係「只在執行期才看得到」[Mayer & Weinreich 2018; Granchelli 2017]。

比較研究量到單一方法的不足：37 個重建工具中 19 個純靜態、10 個純動態、只有 8 個結合兩者 [Bakhtin 2023]；13 個靜態工具中單一最佳 F1 為 0.86，四個工具合起來 0.91 [Schneider 2025]。2026 年的文獻回顧把問題講得更直接：沒有任何單一來源足夠，而「可靠地融合異質證據」仍是未解的問題 [Grinenko 2026]。在微服務之外，SARIF 依各來源的相關性與品質融合相依、程式碼文字與目錄結構 [Zhang 2023]。

**本文的差別**：上述融合方法輸出的是一張邊的聯集或一個分群結果；DepWeaver 保留每條邊**由哪個來源、以什麼信心等級**支撐，並讓下游產物依等級決定要不要計分。

### B. 服務相依圖的分析與測試

Ma 等人提出以服務相依圖分析與測試微服務的 GMAT [Ma 2018]，之後加入服務與情境之間的邊以支援回歸測試的案例檢索 [Ma 2019]，並以版本為單位建構相依圖、在設計期與執行期標出異常 [Ma 2019b]；MicroViSim 從 Kubernetes YAML 產生相依圖與負載模擬 [Lin 2025]。這一系列工作以相依圖為**輸入**；本文處理的是相依圖本身怎麼在多來源下建得完整、而且讓讀者知道每條邊有多可信。

### C. LLM 與架構回復

Quadri 等人結合程式碼建出的知識圖與 LLM 推理做架構回復 [Quadri 2026]。另一方面，讓 LLM agent 自己重建微服務架構時，GPT-4o 的 F1 只有 0.604，作者認為不足以做可靠的逆向工程 [Liljas 2026]。在生成端，Satyrn 先以分析程式算出事實再交給 LLM 撰寫，報告中正確主張的比例超過 86%，直接讓 GPT-4 Code Interpreter 撰寫只有 57% [Sterbentz 2024]；PAL 讓 LLM 寫程式、由直譯器算答案 [Gao 2023]。DepWeaver 採取同樣的分工：相依關係由確定性的抽取與量測產生，LLM 只處理規則對不上的殘餘與報告的詮釋段落。

### D. 人在迴圈

Reflexion model 讓工程師定義高階模型並指定它如何對應到原始碼，工具據此計算一致與不一致 [Murphy 2001]；微服務架構回復工具也普遍需要人工驗證與修正不完整的結果 [Grinenko 2026]。業界工具以規則讓使用者改名、合併或拆分服務（例如 Datadog 的 service remapping）。DepWeaver 的差別是**在分析進行中、在同一個對話裡**提出具體問題（附排序過的候選），而不是事後由使用者寫規則。

### E. ChatOps 與微服務維運

MSABot [Lin 2020] 與 MsdoBot [Huang 2022] 以聊天機器人支援微服務的開發與維運；ChatOps4Msa 以低程式碼的服務組合建構微服務的 ChatOps [Wang 2023; Wang 2024]；PipelineBot 以 LLM 驅動的 ChatOps 自動化部署管線與監控 [Lai 2025]。DepWeaver 建在 ChatOps4Msa 之上，但處理的是不同的問題：相依圖的建構，以及在建構過程中向操作者索取只有人知道的資訊。

> **雙盲提醒**：B 與 E 兩段引用的是同一個實驗室（含作者本人）的前作，英文版一律以第三人稱寫，不加「我們先前的工作」。

### F. 呈現不確定性

Guo 等人以受控實驗比較在圖的邊上同時呈現主屬性與不確定性的視覺變數 [Guo 2015]；Partial models 主張模型應該能表達建模者的不確定性，而不是過早選定一個答案 [Famelis 2012]。業界的服務圖會區分「直接量到」與「推測」：AWS X-Ray 以下游的呼叫紀錄產生 inferred segment，等到該服務自己送出資料時就取代它；CloudWatch Application Map 以虛線框標出沒有被量測的服務。DepWeaver 把這個二分擴充成三級，並把等級帶進覆蓋率與報告。

---

## III. 方法

### A. 整體流程

```
操作者：/get dependency analysis repo:<url> namespace:<ns|none> entry:<url> ...
   │
   ▼
① 收集（背景執行，結果寫入檢查點）
   ├─ 靜態：tree-sitter 抽呼叫點／持久化標記；設定檔與部署描述的位址與佈線
   ├─ 文件：DeepWiki 問 5 個固定問題（職責、同步、非同步、外部、入口）
   ├─ 叢集：Service／Deployment／Pod／EndpointSlice／VirtualService
   ├─ 流量：依 API 表面與文件產生 Postman 腳本 → 執行
   └─ 量測：Prometheus 查 Istio 的 HTTP 請求數、服務網格內與對外的 TCP 連線數
   │
   ▼
② 覆蓋率檢查 → 不足就產生針對缺口的流量，再量一次（可多輪）
   │
   ▼
③ 補值：缺少的值、對不上的名稱 → 在 Discord 問操作者 → 存入檢查點 → 繼續
   │
   ▼
④ 產出（操作者按 Generate report）
   融合 → 正規化 → 殘餘交給 LLM → 分層 → 標準模型
       ├→ 分層相依圖（PNG ＋ Mermaid）
       ├→ 覆蓋率（業務呼叫、資料層分開）
       ├→ 報告（事實段落由程式寫，詮釋由 LLM 寫）
       └→ 存檔，供之後在 Discord thread 追問
```

兩個運作模式共用同一條流程：**有叢集**時四個來源都收集（brownfield）；**沒有叢集**（`namespace:none`）時，需要叢集的步驟被跳過，只產出靜態與文件構成的草圖（greenfield），並在每個受影響的產物上註明「未量測」。

### B. 三個來源與它們各自看得到什麼

**執行期觀測。** 系統跑起來之後，Istio 在每個服務旁邊放一個代理（sidecar），服務之間的每一次呼叫都經過它；代理自動記下「誰呼叫了誰、幾次、成功與否」，寫進 Prometheus。DepWeaver 查三組計數：`istio_requests_total`（HTTP／gRPC 請求，以接收端為準）、服務網格內的 `istio_tcp_connections_opened_total`（資料庫這類非 HTTP 的連線只在 TCP 層看得到）、以及目的地為網格外的 HTTP 與 TCP（外部相依）。這類資料在監控領域稱為 telemetry，架構回復文獻稱為動態分析；它不是推出來的，是呼叫真的發生時被記下來的，所以精確，但只有被驅動到的路徑才會出現。

**靜態分析。** 以 tree-sitter 解析 Java 與 Python 原始碼，用查詢檔（`.scm`）比對 HTTP client、Feign、RestTemplate、訊息佇列、持久化標記（JPA `@Entity`／`Repository`、SQLAlchemy、Django ORM）等型態；其他語言退回由 LLM 讀程式片段。設定檔（Spring `application*.yml`、Spring Cloud Config 倉庫）、部署描述（Kubernetes manifest、Docker Compose）與反向代理設定（nginx `proxy_pass`、Apache `ProxyPass`）由確定性的解析器讀取位址與佈線。每一條抽到的關係都附 `檔案:行`。

**專案文件。** 透過 DeepWiki 對 repo 問五個固定問題，取得服務職責、同步與非同步呼叫、外部相依與入口的敘述，再由 LLM 整理成結構化的清單。文件是唯一可能提到「只在罕見流程才走」的呼叫的來源，也是最可能過時或錯誤的來源。

### C. 融合：信心等級與出處

**信心等級。** 每條邊有一個有序的三級等級，由低到高：

| 等級 | 意義 | 典型來源 | 圖上 |
|---|---|---|---|
| 只被提到（inferred） | 只有設定值、文件敘述或由變數名推得 | datasource URL 但沒有持久化程式碼；`${CATALOGUE_HOST}` 推成 `catalogue` | 點線 |
| 有使用證據（documented） | 有真的會發出呼叫的程式碼，或連線設定加上持久化程式碼 | `CustomersServiceClient.java:36` 的 Feign client | 虛線 |
| 量測到（observed） | 執行期觀測記到這條關係發生過 | `istio_requests_total{source=web,destination=orders}=95` | 實線 |

**出處。** 等級（有多可信）與出處（誰說的、在哪裡）分開存。一條邊可以有多筆出處（程式碼某一行、某個指標、文件某一段），但只有一個等級。

**合併規則。** 以（來源節點, 目標節點）為鍵：等級取最高、出處取聯集。下游（覆蓋率、報告、問答）只能照抄或降級，不能升級。選擇離散三級而不是 0–1 的連續值，是因為 (1) 沒有需要校準的參數，(2) 可以直接對應三種線型，(3) 「取最高」不需要加權公式。代價是無法表達「兩個弱來源互相佐證」。

**資料庫邊的升級條件。** 資料庫邊只有連線設定時是「只被提到」；同一個服務裡同時找到持久化程式碼才升為「有使用證據」；服務網格內的 TCP 連線數量到才升為「量測到」。這條規則把「宣告了但沒用」的資料庫和真的在用的資料庫分開。

**合併順序。** 執行期觀測先建骨架，再依序疊上程式碼邊（`CodeGraphMerger`：對齊節點，對不上的進殘餘清單）、文件邊（`DocGraphMerger`：只補強既有節點，不新增部署裡不存在的服務）、持久化證據、叢集的部署狀態。

**正規化。** 合併後的圖會帶進三類雜訊：框架函式庫被當成服務（`lettuce`、`hystrix`）、同一個服務的多種拼法（模組目錄名、部署名、文件裡的名字）、控制面元件。`GraphNormalizer` 以 14 條不含專案名稱的規則處理，例如「Kubernetes workload 名稱是 greenfield 的詞彙表」「模組目錄以完全相同或 `-<部署名>` 字尾對上 workload」「`localhost` 永遠不是節點」「compose 是佈線，不是某個服務的設定」（完整清單見附錄 A）。這 14 條規則都是在 RQ1 的系統上逐條差異追出來的，每條有對應的單元測試。

**殘餘交給 LLM。** 只有規則對不上的少數程式碼邊會交給 LLM 判斷目標是哪個節點，而且回傳的來源與目標必須是圖上已存在的節點，否則丟棄並記成待問的名稱（§III-E）。

**分層。** `GraphLayerAssigner` 把節點排成：入口層（gateway，或沒有被任何服務呼叫的服務）→ 依最長路徑深度排業務服務 → 資料層 → 外部主機 → 沒有任何邊的節點。環以 Tarjan 強連通元件縮點，同一個環的成員在同一層。在 train-ticket（52 條邊）上，分層後 51 條邊全部由上往下。

### D. 讓產物彼此一致

**一個模型、多個視圖。** 每次產出先建一個標準模型（`DependencyGraph`），再由它產生所有產物：Mermaid 與 Graphviz 圖、覆蓋率、報告的事實段落、問答用的存檔。衍生計算（覆蓋率、分層）只實作一次。模型可以序列化，報告發出幾天後在 Discord thread 追問時，讀回的是同一份模型，不重新查叢集。

**事實由程式寫，LLM 寫詮釋。** 報告分成兩類段落。事實段落（基礎設施相依、資料層、操作者回答過的名稱）由程式從模型產生，措辭固定（例如寫「連線數」而不是「請求數」，因為量到的是前者），以下一節的標題為錨點拼進 LLM 寫的報告；LLM 若自己也寫了該段，丟掉它的版本；找不到錨點就附在最後。LLM 收到同一份事實清單，只寫角色、風險與建議。

**誠實的覆蓋率分母。** 覆蓋率回答「應該被驅動的業務呼叫，有多少被量到了」。分母只算同時滿足兩個條件的邊：(1) 量測機制做得到——兩端都已部署、不是平台控制面、不是程序內元件（如 Guava cache）；(2) 信心高於「只被提到」。業務呼叫與資料層分開計算。被排除的邊數量與理由寫在分數旁邊；被排除的比計分的多時，加上「分數建立在很小的基礎上」的警告；未覆蓋的邊列成清單，直接成為下一輪流量的目標。

**未量測不是零。** 部署狀態以可為 null 的布林表示，預設「未判定」，只有在拿到叢集資料時才設為否。沒有連上叢集時，覆蓋率顯示「未量測」而不是 0%，交給 LLM 的字串是 `NOT MEASURED` 而不是 `0%`。抽取步驟失敗時記錄失敗與原因（`EdgeLedger.fail()`），不回傳空清單。

### E. 對話式補值：最後才問人

**流量參數。** 要讓執行期觀測看到一條呼叫，必須真的驅動它，而有些呼叫需要語意正確的輸入。DepWeaver 依序嘗試四層：

| 層 | 做法 | 例子 |
|---|---|---|
| 1 | 從 repo 挖範例請求（loadgenerator、測試、README 的 curl） | Bank of Anthos 的 loadgenerator 帶出存款欄位 |
| 2 | 把 4xx 回應餵回去，讓 LLM 修正請求 | 缺欄位、格式錯 |
| 3 | 在 Discord 問操作者 | 這個部署的密碼、一個存在的收款帳號 |
| 4 | 連人都給不了 → 標記 UNREACHABLE，從覆蓋率的分母移出並揭露 | — |

第 3 層的問題宣告在 Postman collection 裡（空值變數加 `ASK:` 說明）。沒有回答的值**不綁定**，需要它的請求被扣住並標記等待，而不是讓 LLM 編一個值。憑證必須整組問（只問帳號不問密碼會讓工具自己編一個）。值不進 prompt（LLM 只看到變數名），密碼類欄位遮罩。答案存進檢查點後自動重跑針對缺口的流量。

**名稱對不上。** 文件或程式碼殘餘邊提到的名稱，在四個地方可能對不上圖上的節點：文件服務名經所有對齊規則仍無對應、程式碼的短名稱同時符合多個節點、LLM 也對不上、以及函式庫名單命中但有程式碼或部署層的邊。原本的行為是默默丟掉。現在每一處都記成一個問題，候選以三個訊號排序（去掉 service、api、client 等填充字之後的包含關係、共用字詞、編輯距離）——排序只用來給人看，不做任何決定。操作者在表單裡回答候選編號、任一拼法的服務 id、`new`（新節點）或 `ignore`；答案以確定性規則解析，看不懂就保留問題而不猜。答案以 repo 為單位存在檢查點的 TTL 之外，下一次分析同一個 repo 時只問新出現的名稱；答錯可以用 `/reset dependency aliases` 清除。報告多一節「由操作者解析的名稱」，由程式產生。

**檢查點。** 收集、補值與產出之間以 JSON 檢查點分開：收集結果寫入檢查點後暫停並貼按鈕（Apply ServiceEntries、Provide values、Resolve names、Resume、Generate report），操作者回答後由新的一輪讀回檢查點繼續。這讓一次分析可以跨越數小時、多次對話，而不需要長時間持有執行緒。

### F. 實作

DepWeaver 是 ChatOps4Msa（Spring Boot 3.0、Java 16）的一個 capability：能力宣告在 YAML 管線（`dependency.yml`）裡，由既有的編排器依序呼叫工具步驟；叢集操作經由 Kubernetes MCP server；LLM 為 OpenAI 相容 API。測試套件 264 個，其中 263 個離線通過（唯一的例外需要連上 MCP server）。

> 待補：程式碼行數、各模組行數、tree-sitter 查詢檔數量（翻英文前用 `cloc` 量）。

---

## IV. 評估

### A. 受測系統

| 系統 | 語言 | 部署描述 | 用於 |
|---|---|---|---|
| Bank of Anthos | Java、Python | Kubernetes | RQ1、RQ2、RQ3、RQ4 |
| spring-petclinic-microservices | Java | Kubernetes（本研究 fork，去 Eureka） | RQ2、RQ4、外部對照 |
| ewolff/microservice-kubernetes | Java | Kubernetes | RQ1 |
| ecommerce-microservice-backend-app | Java | Compose | RQ1 |
| piggymetrics | Java | Compose | RQ1 |
| TeaStore | Java（Jakarta EE） | Kubernetes | RQ1 |
| Online Boutique | Go／C#／Node／Python／Java | Kubernetes | RQ1 |
| robot-shop | Node／PHP／Go／Python／Java | Helm、Compose | RQ1、外部對照 |
| spring-cloud-microservice-examples 等 6 個 | Java | Compose | 外部對照、RQ3 |
| train-ticket | Java | Kubernetes | 分層、回歸 |

RQ1 的 6 個新系統是刻意挑形狀不同的：非 Spring 的 Jakarta EE、自家 registry 加 enum 的動態分派、五種語言混合、Helm 模板、反向代理當入口。

### B. RQ1：靜態層的泛化

**參考邊集一：第三方。** MicroDepGraph 資料集 [Rahman et al. 2019] 發表了 20 個開源微服務系統的相依圖，邊來自 Compose 的 `depends_on`／`links` 與內部 API 呼叫。其中 7 個是工具有文法的 Java 系統。我們把每個 repo checkout 到資料集擷取日（2021-02-26）之前的最後一個 commit，只跑靜態層（不接叢集、不接文件、不呼叫 LLM）。由於兩邊對「相依」的定義不同（資料集沒有外部主機與程式碼層呼叫），這裡報**一致度**——資料集的邊工具畫到多少——而不是 precision。

| 系統 | 資料集邊 | 工具畫到 | 一致度 |
|---|---|---|---|
| spring-petclinic | 13 | 11 | 0.85 |
| microservices-book（ewolff） | 5 | 5 | 1.00 |
| tap-and-eat | 4 | 4 | 1.00 |
| spring-cloud-netflix | 26 | 26 | 1.00 |
| spring-cloud-microservice | 26 | 24 | 0.92 |
| LakesideMutual | 9 | 9 | 1.00 |
| robot-shop | 12 | 12 | 1.00 |
| **合計** | **95** | **91** | **0.96** |

漏的 4 條：2 條是 repo 演進（資料集抓的版本有 hystrix-dashboard，對齊後的 commit 已經沒有這個模組）；2 條是名稱對應的邊界（compose 服務叫 `hystrix`，模組叫 `cloud-hystrix-dashboard`），這兩條在 RQ3 由問人補回。工具另外畫了 35 條資料集沒有的邊（外部主機、程式碼層的 Feign 呼叫、反向代理路由），因為無法以第三方來源判定對錯，這裡只列不計分。

**參考邊集二：作者整理、逐條可複驗。** 對 RQ1 的 7 個系統，作者整理一份參考邊集，每條邊附第三方可以打開的 `路徑:行`；一個測試（`TruthProvenanceResolvesTest`）斷言 113 筆出處全部存在且內容相符。另由一個沒看過參考邊集與工具輸出的 LLM 獨立標註一次作為第二標註者；40 條「只有標註者有」的邊逐條裁決後，沒有一條是作者漏標（17 條為命名差異、5 條為部署變體、18 條為標註者錯誤，含 3 條方向反轉）。

| 系統 | 畫了 | 對 | 錯 | 漏 | P | R |
|---|---|---|---|---|---|---|
| Bank of Anthos | 12 | 12 | 0 | 0 | 1.00 | 1.00 |
| ecommerce | 20 | 20 | 0 | 18 | 1.00 | 0.53 |
| ewolff-k8s | 5 | 5 | 0 | 0 | 1.00 | 1.00 |
| Online Boutique | 17 | 17 | 0 | 0 | 1.00 | 1.00 |
| piggymetrics | 41 | 34 | 7 | 0 | 0.83 | 1.00 |
| robot-shop | 18 | 18 | 0 | 3 | 1.00 | 0.86 |
| TeaStore | 6 | 6 | 0 | 7 | 1.00 | 0.46 |

**錯在哪裡。** 每一個非 1.00 的格子都追到一個具體原因：

- **ecommerce 漏 18 條**全是控制面（服務 → 設定中心／服務註冊中心）：repo 的設定只寫 `localhost` 預設值，真的主機名只存在一個不在 repo 裡的目錄。工具依規則丟棄 `localhost`，沒有編造。業務邊 P／R 為 1.00／1.00。
- **piggymetrics 錯 7 條**同一個原因：共用的 `application.yml` 裡的 OAuth 與 RabbitMQ 設定被攤到每一個 config client，而實際上只有其中幾個服務用到。這 7 條都是「有使用證據」等級，靜態層無法分辨，需要執行期觀測降級。
- **TeaStore 漏 7 條**：所有服務間呼叫都寫成 `loadBalanceRESTOperation(Service.PERSISTENCE, …)`，目標是 enum 常數，經自家 registry 動態解析。靜態層抓不到任何主機字串，所以一條都沒畫，也一條都沒編。
- **robot-shop 漏 3 條**：兩條是 Node 與 PHP（沒有文法），一條的主機名來自另一行的 `getenv` 預設值。

**這些規則是怎麼來的。** 第一輪（修改前）6 個新系統全部有問題：一個整張圖塌成 3 個節點（TeaStore，模組名以 `.` 分隔被當成 DNS 後綴切掉）、一個六成邊是假的（ecommerce，沒人引用的常數檔）、一個資料層全漏（piggymetrics，設定在 Config 倉庫）。逐條差異追出的規則累計 14 條，沒有一條含專案名稱；修完之後 Bank of Anthos 與 train-ticket 的既有圖逐條不變（回歸）。第三方對照又逼出 3 條規則，其中第 14 條（workload 自己 env 裡的服務位址畫成點線）把 Online Boutique 的 recall 從 0.13 提高到 1.00——原本歸因為「語言沒有文法」的損失，大半其實是部署描述已經寫明、但工具沒有畫的宣告。

**RQ1 小結。** 靜態層在 Java 為主的系統上，業務邊 precision 為 1.00（7 個中 6 個），對第三方參考邊集的一致度 0.96。三個明確的邊界——沒有文法的語言、registry／enum 動態分派、跨行拼接的主機名——都不是加規則能解的，需要執行期觀測。

### C. RQ2：融合執行期觀測

**設置。** Bank of Anthos 部署在 k3s ＋ Istio 的叢集上，含真實的 PostgreSQL 資料庫（accounts-db、ledger-db）。DepWeaver 以完整流程跑：收集、驅動流量、查量測、補值、產出。

**結果。** 業務呼叫覆蓋率 **7/7**，資料層 **5/5**。資料庫邊原本完全看不到：Istio 只對 HTTP／gRPC 產生請求指標，資料庫連線是不透明的 TCP。加入服務網格內的 TCP 連線查詢後，資料庫邊才有執行期證據。

**同一條邊的三種狀態。** `transactionhistory → ledger-db` 在三次實驗中分別是：

| 實驗 | 模型裡的狀態 | 原因 |
|---|---|---|
| 第一次（靜態，修正前） | 不存在 | Spring Boot 以環境變數 `SPRING_DATASOURCE_URL` 隱式綁定，程式碼裡沒有讀取這個值的行 |
| 第二次（靜態，修正後） | 虛線，有使用證據，「未在執行期觀測到」 | 從 Deployment 的 `envFrom` 讀到注入的位址，加上 JPA 持久化標記 |
| 第三次（執行期） | 實線，量測到 5 191 次連線 | 服務網格內 TCP 查詢 |

**等級影響下游的回答。** 我們以同一組 10 個問題、同一個模型（gpt-4.1-mini）在三次實驗的存檔上做問答，比較三組：只給報告、給報告加檢索、給圖查詢結果（DepWeaver）。第一次實驗的圖漏了上面那條邊，三組在相關問題上**都答錯**；修正後三組都答對，DepWeaver 組的回答是「宣告且有使用證據，但未在執行期觀測到」；第三次則是「量測到」。這組對照的標準答案是由同一張圖的查詢產生的，所以它量的是「回答與圖一致的程度」，不是「回答與事實一致的程度」——它說明的是：問答的品質跟著圖的品質走，而圖的錯誤會以更自信的語氣傳到回答裡。

### D. RQ3：對話式補值

**流量參數（Bank of Anthos）。** 為了確認第 3 層確實是最後手段，我們關閉第 1 層（挖範例請求）再跑一次。工具自動驅動到 5/7 後停住：剩下兩條（`ledgerwriter → balancereader` 等）需要一筆真的寫入成功的轉帳，也就是需要這個部署的密碼與一個**別人的**收款帳號。工具在 Discord 開口問這兩個值，操作者回答後自動重跑，覆蓋率 5/7 → 6/7 → 7/7。第 1 層開著時，工具沒有問任何問題——第 1 層已經從 loadgenerator 取得所需欄位，這是預期的行為。

**名稱（spring-cloud-microservice-examples）。** RQ1 的第三方對照中，這個系統漏了 `hystrix → discovery`、`hystrix → gateway`。根因是 compose 的 `hystrix` 服務（Hystrix dashboard）名稱命中函式庫名單，整個節點被正規化刪掉。改為「函式庫名單命中但有程式碼或部署層的邊 → 問」之後，工具問了 3 題，回答後一致度 **24/26 → 26/26**，其他 6 個系統的結果逐條不變。

**在 Discord 的五次實跑。** 同一個系統在真實 Discord 上跑了五次，每一次都暴露介面或規則的問題並修正（表單只接受候選 → 加入詞彙表；同一服務的模組名與部署名重複出現 → 問「是不是同一個」；文件把 repo 名當成元件 → 不問直接丟）。第三次起圖穩定在 26/26，且答案記憶生效：第三次只問新出現的 5 個名稱。我們也觀察到，DeepWiki 每次用詞不同，新名稱會零星出現；這是這個機制的常態而不是錯誤。

**限制。** 五次實跑與離線驗證的操作者都是作者本人，而且看過參考邊集。這證明了機制走得通，**不證明一個不熟悉系統的操作者會答對**。

### E. RQ4：設計規則擋住的錯誤

以下每一個錯誤都在開發與驗證中實際發生過，表中「擋住它的規則」對應 §III-D：

| # | 發生了什麼 | 擋住它的規則 |
|---|---|---|
| 1 | petclinic：覆蓋率被控制面、框架產生的偽節點與別名稀釋成 45%；改成只算可驅動的業務邊後是 4/4 | 誠實分母 |
| 2 | Bank of Anthos：同一個叢集、同一段流量，文件多講了幾條不存在的邊，覆蓋率 7/7 → 7/10 | 誠實分母（只被提到的不計分）＋信心等級 |
| 3 | Bank of Anthos：程序內快取永遠量不到，資料層卡在 5/6，報告還建議「重啟 pod」 | 誠實分母（程序內元件不計分） |
| 4 | Bank of Anthos：報告寫資料庫邊「Runtime observed: Unknown」，旁邊的圖畫實線；改 prompt 兩次，第二次把建置期函式庫與雲端替代方案列成相依 | 一個模型多個視圖＋事實由程式寫 |
| 5 | spring-cloud-microservice：LLM 寫的報告第 4 節列出操作者已答 `ignore` 的名稱 | 事實由程式寫 |
| 6 | 純靜態模式的報告寫出「25 Services observed / 50 Pods Running」 | 未量測不是零 |
| 7 | 純靜態模式的問答把「未量測」講成「覆蓋率 0%」；Discord 上的 greenfield 覆蓋率顯示 0% | 未量測不是零 |
| 8 | 容器映像檔裡沒有 git，程式碼抽取從未成功，流程照樣走完，報告看起來正常 | 失敗留痕跡（未量測不是零） |

八個錯誤有一個共同點：**沒有一個是抽取或量測本身的錯**，都是在「把證據變成讀者看到的東西」時發生的。#4 與 #5 特別值得注意：在同一類問題上，改 prompt 只是換一種錯法；把那一段改由程式產生之後，同類錯誤沒有再出現。

---

## V. 討論

### A. 適用範圍

DepWeaver 的設計假設三件事：證據來自可靠度不同的多個來源、產物不只一種（圖、數字、報告）、讀者會拿產物做決策。只有單一來源（例如純追蹤產生的服務圖）時，信心分級退化成沒有作用。

### B. 靜態與執行期的分工

RQ1 與 RQ2 顯示兩層是互補而不是替代：靜態層在 Java 系統上 precision 高，但對動態分派完全無能為力（TeaStore 0/7 的業務邊）；執行期層精確，但只看得到被驅動到的路徑，需要流量產生與補值才能讓它看到更多。piggymetrics 的 7 條錯邊（共用設定攤到所有服務）是另一個方向的互補：只有執行期觀測能把它們降級。

### C. 為什麼問人放在最後

問人有成本：打斷操作者、可能答錯。所以 DepWeaver 只在前面的自動手段都失敗時才問，而且問題要具體到可以用一個值或一個選項回答。RQ3 的兩個情境都符合這個條件：密碼與收款帳號是部署的事實，不是程式碼的事實；一個名稱指哪個服務，是只有熟悉系統的人知道的對應。

### D. 效度威脅

- **建構效度**：作者參考邊集由作者整理。我們以第三方資料集、逐條可複驗的出處與第二標註者三種方式降低這個風險，但第三方資料集的「相依」定義與本工具不同，所以只報一致度。
- **內部效度**：14 條正規化規則是在受測系統上追出來的，因此 RQ1 修正後的數字不是 hold-out 結果。第三方對照的 7 個系統中，有 3 條規則是在對照過程中新增的；新增規則後，既有 10 個系統的結果逐條不變。
- **外部效度**：受測系統以 Java／Spring 為主，執行期驗證只有 Bank of Anthos 一個完整案例（petclinic 為第二個，但沒有真實資料庫）。TeaStore 的執行期驗證程序已寫好但尚未執行。
- **操作者**：RQ3 的操作者是作者本人。
- **LLM 的變動**：報告與文件整理依賴 LLM；DeepWiki 的用詞每次不同。事實段落由程式產生，所以不受影響；詮釋段落與文件來源的邊會。

---

## VI. 結論與未來工作

本文提出 DepWeaver，以三來源融合、三級信心與逐條出處建構微服務相依圖，讓圖、覆蓋率與報告由同一個模型產生而彼此一致，並在自動手段用盡時才在 ChatOps 對話中問人。在 9 個開源系統上，靜態層對第三方參考邊集的一致度為 0.96；在真實部署上，融合執行期觀測與兩個問題的答案後，業務呼叫與資料層覆蓋率都達到 100%。

未來工作：(1) 請不熟悉受測系統的操作者回答名稱問題，量測問人的實際正確率；(2) 在 TeaStore 上完成執行期驗證，量化「靜態 0/7 → 執行期」的互補；(3) 以小型使用者研究檢驗信心分級是否讓讀者的決策更好；(4) 以相依圖產生部署順序（拓樸排序），並以執行期證據驗證。

---

## 參考文獻

> 2026-10-12 由兩次文獻查證整理。標「待確認」者只查到書目或二手描述，翻英文前要讀到原文。IEEE 格式與 10 頁限制下，最後大約保留 25–30 篇。

- [Al Maruf 2022] A. Al Maruf, A. Bakhtin, T. Cerny, D. Taibi, "Using Microservice Telemetry Data for System Dynamic Analysis," IEEE SOSE 2022. doi:10.1109/SOSE55356.2022.00010
- [Bakhtin 2023] A. Bakhtin et al., "Tools Reconstructing Microservice Architecture: A Systematic Mapping Study," ECSA 2023, LNCS 14590. doi:10.1007/978-3-031-66326-0_1
- [Bushong 2022] V. Bushong, D. Das, T. Cerny, "Reconstructing the Holistic Architecture of Microservice Systems using Static Analysis," CLOSER 2022, pp. 149–157. doi:10.5220/0011032100003200
- [Famelis 2012] M. Famelis, R. Salay, M. Chechik, "Partial Models: Towards Modeling and Reasoning with Uncertainty," ICSE 2012, pp. 573–583.
- [Gao 2023] L. Gao et al., "PAL: Program-aided Language Models," ICML 2023, PMLR 202.
- [Gortney 2022] M. E. Gortney et al., "Visualizing Microservice Architecture in the Dynamic Perspective: A Systematic Mapping Study," IEEE Access 10, 2022. doi:10.1109/ACCESS.2022.3221130
- [Granchelli 2017] G. Granchelli et al., "Towards Recovering the Software Architecture of Microservice-based Systems," ICSAW 2017, pp. 46–53. doi:10.1109/ICSAW.2017.48（架構師修正步驟待確認）
- [Grinenko 2026] Grinenko, Gizun, Morhun, Bekh, "Methods for reconstructing microservice architectures: A thematic literature review," WCH'26, CEUR-WS Vol-4278, paper 10. <https://ceur-ws.org/Vol-4278/paper10.pdf>
- [Guo 2015] H. Guo, J. Huang, D. H. Laidlaw, "Representing Uncertainty in Graph Edges: An Evaluation of Paired Visual Variables," IEEE TVCG 21(10), 2015. doi:10.1109/TVCG.2015.2424872
- [Huang 2022] Huang, Ma, Wang, "MsdoBot," ICEBE 2022, pp. 124–129. doi:10.1109/ICEBE55470.2022.00030
- [Lai 2025] Lai, Ma, Li, "LLM-Powered ChatOps Approach to Microservice Deployment Pipeline Automation and Monitoring," ICEBE 2025, pp. 24–31. doi:10.1109/ICEBE68123.2025.00012
- [Liljas 2026] Liljas, Esposito, Lenarduzzi, Taibi, "Can Agents Reconstruct Microservice Architecture?," ESEM 2026. doi:10.4230/LIPIcs.ESEM.2026.62
- [Lin 2020] Lin, Ma, Huang, "MSABot," ICSE Workshops 2020, pp. 36–40. doi:10.1145/3387940.3391501
- [Lin 2025] Lin, Ma, Lee, Lee, "MicroViSim," ASE Workshops 2025, pp. 161–164. doi:10.1109/ASEW67777.2025.00038
- [Ma 2018] S.-P. Ma, C.-Y. Fan, Y. Chuang, W.-T. Lee, S.-J. Lee, N.-L. Hsueh, "Using Service Dependency Graph to Analyze and Test Microservices," COMPSAC 2018, vol. 2, pp. 81–86. doi:10.1109/COMPSAC.2018.10207（相依圖是靜態或動態產生，待讀原文確認）
- [Ma 2019] S.-P. Ma et al., "Graph-based and scenario-driven microservice analysis, retrieval, and testing," Future Generation Computer Systems 100:724–735, 2019. doi:10.1016/j.future.2019.05.048
- [Ma 2019b] S.-P. Ma et al., "Version-Based Microservice Analysis, Monitoring, and Visualization," APSEC 2019, pp. 165–172. doi:10.1109/APSEC48747.2019.00031
- [Mayer & Weinreich 2018] B. Mayer, R. Weinreich, "An Approach to Extract the Architecture of Microservice-Based Software Systems," SOSE 2018. doi:10.1109/SOSE.2018.00012
- [Murphy 2001] G. C. Murphy, D. Notkin, K. J. Sullivan, "Software Reflexion Models: Bridging the Gap between Design and Implementation," IEEE TSE 27(4), 2001. doi:10.1109/32.917525
- [Quadri 2026] Quadri, Kontogiannis, Truong, "Architecture Recovery Revisited: A Hybrid Knowledge Graph and LLM-Based Approach," COMPSAC 2026, pp. 493–502. doi:10.1109/COMPSAC69091.2026.00070
- [Rahman 2019] M. I. Rahman, S. Panichella, D. Taibi, "A Curated Dataset of Microservices-Based Systems," SSSME 2019, CEUR-WS Vol-2520. arXiv:1909.03249
- [Schneider & Scandariato 2023] S. Schneider, R. Scandariato, "Automatic extraction of security-rich dataflow diagrams for microservice applications written in Java," JSS 202, 2023. doi:10.1016/j.jss.2023.111722
- [Schneider 2025] S. Schneider et al., "Comparison of static analysis architecture recovery tools for microservice applications," Empirical Software Engineering 30, art. 128, 2025. doi:10.1007/s10664-025-10686-2
- [Soldani 2022] J. Soldani, J. Khalili, A. Brogi, "Offline Mining of Microservice-based Architectures," CLOSER 2022. doi:10.5220/0011061000003200
- [Sterbentz 2024] Sterbentz et al., "Satyrn: A Platform for Analytics Augmented Generation," EMNLP 2024. arXiv:2406.12069
- [Sui 2020] L. Sui et al., "On the Recall of Static Call Graph Construction in Practice," ICSE 2020. doi:10.1145/3377811.3380441
- [Tsubouchi 2020] Tsubouchi et al., "Transtracer: Socket-Based Tracing of Network Dependencies Among Processes in Distributed Applications," COMPSAC 2020. doi:10.1109/COMPSAC48688.2020.00-92
- [Wang 2023] Wang, Ma, Chao, Lai, "Low-code ChatOps for Microservices Systems Using Service Composition," ICEBE 2023, pp. 55–62. doi:10.1109/ICEBE59045.2023.00026
- [Wang 2024] Wang, Ma, Lai, Chao, "ChatOps for microservice systems: A low-code approach using service composition and large language models," FGCS 161:518–530, 2024. doi:10.1016/j.future.2024.07.029
- [Zhang 2023] Zhang et al., "Software Architecture Recovery with Information Fusion," ESEC/FSE 2023, pp. 1535–1547. doi:10.1145/3611643.3616285

---

## 附錄 A：14 條正規化規則

見 `docs/generalization-2026-09-22.md` §3 與 `docs/generalization-external.md` §1.6、§1.8。翻英文時整理成一張表（規則、觸發它的系統、對應測試）。

## 附錄 B：重現

- 靜態探針：`mvn -o test -Dtest=GreenfieldProbeTest -Dprobe.repo=<checkout> -Dprobe.out=<file>.mmd`
- 作者參考邊集計分：`GeneralizationScoreTest`；第三方：`ExternalTruthAgreementTest`；出處可複驗：`TruthProvenanceResolvesTest`
- 名稱問答：`-Dprobe.aliases=<answers.json>`，答案檔在 `docs/generalization/external/aliases/`

## 附錄 C：投稿資訊（2026-10-12 查證；2026-10-20 更新 2027 屆官方資訊）

**2027 屆官方站已上線**（[ieeecompsac.computer.org/2027](https://ieeecompsac.computer.org/2027/)）：東京，2027-07-13～16；full paper 截止 **2027-01-31**、通知 04-07、camera-ready 06-01；workshop paper 04-15。[2027 Information for Authors](https://ieeecompsac.computer.org/2027/information-for-authors/) 的規定與 2026 屆相同（10 頁含參考文獻與附錄、可加買 2 頁每頁 US$125、雙盲、AI 內容要在致謝揭露）；[2027 CFP](https://ieeecompsac.computer.org/2027/call-for-papers/) 只寫「依 IEEE 會議出版政策」，沒有把禁止一稿多投的字句寫出來，下表「一稿多投」那列仍引 2026 CFP。下表其餘內容以 2026 屆為準：

| 項目 | 內容 | 出處 |
|---|---|---|
| 截止 | 主會議（symposium）論文 2026-01-31，延到 02-20；通知 04-12。workshop 04-15，延到 04-30 | [2026 首頁](https://ieeecompsac.computer.org/2026/) |
| 2027 屆 | 東京，2027-07-13～16；full paper 2027-01-31、通知 04-07、camera-ready 06-01（官方） | [2027 首頁](https://ieeecompsac.computer.org/2027/)、[2027 CFP](https://ieeecompsac.computer.org/2027/call-for-papers/) |
| 頁數 | full paper **10 頁**、short 6 頁、workshop 6 頁；**參考文獻與附錄都算在頁數內**；full 可加買最多 2 頁（每頁 US$125） | [Information for authors](https://ieeecompsac.computer.org/2026/information-for-authors/) |
| 格式 | IEEE 會議模板（Word／LaTeX／Overleaf），US Letter | 同上 |
| 審查 | **雙盲**：拿掉姓名與單位，自己的前作要匿名引用 | 同上 |
| 出版 | IEEE Xplore | 同上 |
| AI 使用 | 用了 AI 產生的內容要在致謝寫明：哪個系統、影響哪幾節 | 同上 |
| 一稿多投 | 必須是未發表的原創；禁止同時投別處（JC/CJ 計畫除外） | [CFP](https://ieeecompsac.computer.org/2026/call-for-papers/) |
| 最合適的 track | **SETA**（Software Engineering Technologies & Applications，範圍「intelligent SE、conventional SE、emerging topics」，10 頁，至少一位作者親自報告）；備選 ITiP（含雲端與維運） | [SETA 2026](https://ieeecompsac.computer.org/2026/compsac-2026-symposium-on-software-engineering-technologies-applications-seta/) |

**對這篇的三個影響**：

1. **10 頁含參考文獻**。現在這份中文稿翻成英文後一定超過，§IV-E 的事件表、§III-C 的正規化細節與附錄 A 是先砍的候選；附錄 B 改成匿名的 artifact 連結。
2. **雙盲＋自己的前作**。實驗室已發表的 ChatOps4Msa（Wang et al., ICEBE 2023；FGCS 2024）與 **PipelineBot（Lai, Ma, Li, ICEBE 2025）** 都要以第三人稱引用；正文不能出現 SOSEL、機器 A／B、Discord 伺服器名、GitHub 帳號。本篇與 PipelineBot 的差別要在 §II 寫清楚（PipelineBot 是部署管線自動化與監控，本篇是相依圖的建構）。
3. **和 AsianPLoP 那篇不能重疊**。徵稿頁沒有提到 pattern 論文，但「禁止同時投別處」的規則下，兩篇的內容必須明顯不同：本篇講方法與實驗，pattern 那篇講通用的做法、DepWeaver 只當 Known Uses 之一，不重複實驗數字。投稿前寄信問 program chair 比較保險。
