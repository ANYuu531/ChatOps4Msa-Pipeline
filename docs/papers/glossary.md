# 兩篇論文名詞導讀（合併版，依出現順序）

AsianPLoP〈相依架構抽取：微服務系統的 Pattern Language（A Pattern Language for Dependency Architecture Extraction in Microservice Systems）〉與 COMPSAC〈DepWeaver〉兩篇共用很多名詞，這份把兩篇合在一起、每個名詞只解釋一次。已對齊 2026-10-14 事實查核後的版本。

**怎麼讀：**
- **第一部分**照 AsianPLoP 的出現順序，列出 AsianPLoP 用到的所有名詞。兩篇都有的名詞，「用途」欄分別寫出它在兩篇裡各做什麼，「出現位置」欄同時標兩篇。
- **第二部分**照 COMPSAC 的出現順序，只列 COMPSAC 才有的名詞；每一節開頭列出這節也用到、但已在第一部分解釋過的名詞。
- **第三部分**是兩篇參考文獻裡的會議、期刊縮寫（合併成一張）。
- 位置標示：AsianPLoP 用 §1.1、表 1；COMPSAC 用 I-A、表 III 這類章節號。
- 第一部分只出現在 AsianPLoP 的詞條，「本文」指 AsianPLoP；第二部分的「本文」指 COMPSAC。

---

# 第一部分：AsianPLoP 的名詞（含兩篇共用的）


## 摘要與詮釋資料（標題、摘要、CCS、關鍵詞、ACM 參考格式）

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 相依圖（dependency map） | 術語 | AsianPLoP 標題；COMPSAC 摘要 | 描述系統元件之間依賴關係的圖：哪個服務呼叫哪個服務、用了哪個資料庫、連到哪個外部主機。 | 【AsianPLoP】全文要重建的對象。　【COMPSAC】DepWeaver 要產出的主要產物。 |
| 相依架構抽取（Dependency Architecture Extraction） | 本文 pattern language 的名稱 | AsianPLoP 標題、副標題 | 從多個不完整的來源抽取出系統的相依架構（畫成相依圖）。 | 四個 pattern 合起來的名字。10/23 由「相依圖重建（Dependency Map Recovery）」改名：recovery→extraction、map→architecture。內文提到研究領域時仍用「架構重建（architecture recovery）」，因為那是文獻的用字。 |
| Slogan | pattern 的欄位 | AsianPLoP §5–§8 開頭 | 每個 pattern 一句好記的話，中文在前、英文在括號裡。 | 10/23 新增。另外每個 Solution 小節附一張流程圖（圖 3–6），步驟編號和 Solution 的條列一致。 |
| 微服務（microservices） | 術語 | AsianPLoP 標題；COMPSAC 摘要 | 一種架構風格：系統拆成許多可以獨立部署、透過網路（HTTP、gRPC、訊息佇列）互相呼叫的小服務。 | 【AsianPLoP】本文的應用領域。服務多、語言雜，相依圖很難靠人工維護。　【COMPSAC】研究對象。 |
| Pattern | 術語 | AsianPLoP 摘要 | 針對某個情境下反覆出現的問題，寫下社群已經在用、能在互相拉扯的需求（forces）之間取得平衡的解法。 | 本文的基本寫作單位；§1.3 給出正式定義。 |
| Pattern language | 術語 | AsianPLoP 標題、摘要 | 一組彼此銜接的 pattern：套用一個之後留下的問題由下一個接手，合起來可以一步步長出完整的設計。 | 本文的論文類型；§4 說明為什麼叫 language 不叫 catalog。 |
| 部署排序（deployment ordering） | 術語 | AsianPLoP 摘要；COMPSAC 摘要 | 決定服務部署的先後，通常被依賴的先上線。 | 【AsianPLoP】相依圖的用途之一，說明為什麼值得重建。　【COMPSAC】說明相依圖為什麼重要。部署排序也列在未來工作。 |
| 變更影響分析（change impact analysis） | 術語 | AsianPLoP 摘要 | 推估改動一個元件會波及哪些其他元件。 | 同上，相依圖的用途之一。 |
| 測試規劃 | 術語 | AsianPLoP 摘要 | 決定要測哪些路徑、找出還缺測試的地方。 | 同上；也連到 ① 的前提的「測試缺口清單」。 |
| 部署描述檔（deployment descriptor） | 術語 | AsianPLoP 摘要；COMPSAC I-A | 描述系統怎麼部署、由部署工具讀取的設定檔，例如 Kubernetes manifest、Docker Compose、Helm chart。 | 【AsianPLoP】三個證據來源之一（§2「讀部署描述檔」）。　【COMPSAC】靜態來源之一。 |
| 呼叫點（call site） | 術語 | AsianPLoP 摘要 | 程式碼裡發出呼叫的位置（哪個檔案、哪一行）。 | 靜態分析抽取的對象（§2「抽取程式碼呼叫點」）。 |
| 服務網格（service mesh） | 術語 | AsianPLoP 摘要；COMPSAC 摘要 | 在每個服務旁邊放一個 proxy，由它代為處理服務之間的網路流量（路由、加密、重試、量測）的基礎設施層，代表產品是 Istio。 | 【AsianPLoP】執行期觀測的資料來源：proxy 會記下每一次呼叫。　【COMPSAC】執行期觀測從這裡來（實作是 Istio）。 |
| 分散式追蹤（distributed tracing） | 術語 | AsianPLoP 摘要；COMPSAC I-A | 給一個請求一個 trace ID，記錄它在各服務之間經過的每一段（span）的呼叫關係與時間。 | 【AsianPLoP】另一種執行期觀測來源，可以直接畫出服務圖。　【COMPSAC】動態方法的來源之一。DepWeaver 用的是服務網格指標，不是追蹤。 |
| 執行期觀測（runtime observation） | 術語 | AsianPLoP 摘要（§1.1 有定義）；COMPSAC 摘要 | 系統實際執行時，由 proxy 或追蹤函式庫記錄真正發生過的呼叫。 | 【AsianPLoP】三個來源中最精確的一個，但只看得到被走過的路徑，① 的前提就是為了補它。　【COMPSAC】三個來源之一，可靠度最高，但只看得到有被觸發的路徑（細節見 III-B）。 |
| LLM（大型語言模型，large language model） | 術語 | AsianPLoP 摘要；COMPSAC 摘要 | 以大量文字訓練、能產生自然語言的模型（例如 GPT 系列）。 | 【AsianPLoP】本文中負責寫報告；④ 處理它重述事實出錯、自行填空的問題。　【COMPSAC】處理規則沒涵蓋的少數程式碼邊、整理文件，並撰寫報告中相依關係以外的章節；相依關係清單不交給它。 |
| 納入標準（兩個條件） | 方法 | AsianPLoP 摘要（§1.3 表 2 詳述） | 一個做法要同時滿足兩個條件才收錄：至少兩篇論文、至少兩個業界工具的官方文件。（10/19 起拿掉「作者自己的工具也有實作」：DepWeaver 論文未發表，不能在 pattern 裡提到。）另外有一個區分 pattern 與步驟的判準：要有自己互相拉扯的 forces 才寫成 pattern。 | 本文把「反覆出現」具體化的方式，§9 依此篩選。 |
| Known Uses | 術語（pattern 欄位） | AsianPLoP 摘要 | pattern 的「已知用例」欄位，列出現實中有誰用過這個做法。 | 本文每個 pattern 都把 Known Uses 分成論文、業界兩類，當作「反覆出現」的證據。 |
| ① 混合重建（Hybrid Reconstruction） | pattern | AsianPLoP 摘要 | 先以流量把執行期觀測補齊（前提），再把多個來源的邊以「來源→目標」為鍵合成一張圖，並保留每條邊的出處。 | 建圖組第 1 個，詳見 §5。 |
| 以流量走遍路徑（Exercise the Paths） | ① 的前提（不是 pattern） | AsianPLoP §5 前提、表 9 | 主動產生流量，讓執行期觀測看到平常沒走到的路徑。 | 10/19 老師意見：只有一種顯而易見的做法、沒有 forces，所以寫成 ① 的前提段落。 |
| ② 實測優先於推測（Observation over Inference） | pattern | AsianPLoP 摘要 | 每條邊標上「實測／推測」並畫成不同樣子；證據強的取代證據弱的。 | 建圖組第 2 個，詳見 §6。 |
| ③ 請人確認名稱對應（Human-Confirmed Name-Mapping） | pattern | AsianPLoP 摘要 | 規則判斷不了的名稱，附上候選清單交給熟悉系統的人選，答案存起來重複使用。 | 建圖組第 3 個，詳見 §7。 |
| ④ 基於事實的解釋（Fact-Based Explanation） | pattern | AsianPLoP 摘要；COMPSAC III-E | 先建一份統一模型；事實由程式從模型算出並寫成固定文字，LLM 只寫詮釋與建議；沒收集到的資料標成「未知／未量測」，不能當成 0 或 false。 | 交付組唯一一個，詳見 §8。（COMPSAC 補充：部署狀態用可為 null 的布林值，預設「未知」；沒連上叢集時寫 not measured／NOT MEASURED，不寫 0%；抽取失敗要記錄原因。RQ4 有三個實例。） |
| 一份目錄、多個視圖（One Catalog, Many Views） | 併入 ④（Solution 第 1 步） | AsianPLoP §8 別名、表 9 | 先建一個統一模型，圖、數字、報告、問答等產出物都從它產生。 | 10/19 起不再獨立成篇：和「基於事實的解釋」解的是同一個問題（產出物不走樣）。 |
| 無資料不等於零（No Data Is Not Zero） | 併入 ④（Solution 第 2、4 步） | AsianPLoP §8 別名、表 9 | 沒收集到的資料要標成「未知／未量測」，不能當成 0 或 false。 | 同上。 |
| CCS concepts（ACM Computing Classification System） | 標準 | AsianPLoP `!ccs` 行 | ACM 的電腦科學主題分類法（2012 版），投 ACM 格式的論文必須標註。 | 本文標在「Software and its engineering」底下的三個類別（見下面三列）。 |
| Software reverse engineering | CCS 類別 | AsianPLoP `!ccs` 行 | 軟體逆向工程：從現有系統（程式碼、執行行為）反推出它的設計或結構。 | 架構重建屬於這一類。 |
| Software architectures | CCS 類別 | AsianPLoP `!ccs` 行 | 軟體架構：系統由哪些元件組成、元件之間怎麼互動。 | 相依圖是一種架構視圖。 |
| Patterns（CCS） | CCS 類別 | AsianPLoP `!ccs` 行 | 設計模式與 pattern 相關研究。 | 標示本文是 pattern 論文。 |
| 架構重建（architecture recovery／reconstruction） | 術語 | AsianPLoP 關鍵詞（§1.1 再次出現）；COMPSAC 關鍵字 | 從程式碼、設定、執行紀錄等反推系統架構的研究領域。 | 【AsianPLoP】本文所屬的學術領域，Related Work 和 Known Uses 中的論文大多屬於這個領域。　【COMPSAC】本文所屬的研究領域。 |
| human-in-the-loop | 術語 | AsianPLoP 關鍵詞；COMPSAC 關鍵字 | 在自動化流程中安排人在關鍵步驟參與判斷。 | 【AsianPLoP】對應 ③（請人確認名稱）與 ① 的前提（請人提供帳密等值）。　【COMPSAC】DepWeaver 只在最後一步才問人。 |
| ACM Reference Format | 標準 | AsianPLoP `!acmref` 行 | ACM 論文模板首頁的「本文該怎麼被引用」格式區塊。 | 寫明本文預定發表在 AsianPLoP 2027。 |
| Hillside Group（HILLSIDE） | 組織 | AsianPLoP `!acmref` 行 | 推廣軟體 pattern 的非營利組織，主辦或支持 PLoP 系列會議並出版論文集。 | 本文論文集的出版者名稱。 |
| AsianPLoP（Asian Conference on Pattern Languages of Programs, People, and Practices） | 會議 | AsianPLoP `!acmref` 行 | PLoP 系列的亞洲場次，採 shepherding 與 writers' workshop 的審稿方式。 | 本文的投稿會議。依參考格式，是第 13 屆、2027 年 3 月 26–29 日在基隆舉行。 |
| NPAGES | 佔位符 | AsianPLoP `!acmref` 行 | 頁數佔位字，產生 Word 時由 build 腳本填入（目前 17）。 | 不是術語。 |

## 1.1 情境

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 佇列（queue／message queue） | 術語 | AsianPLoP §1.1 第 1 段；COMPSAC I-A | 服務之間非同步傳訊息的中介，例如 RabbitMQ、Kafka。 | 【AsianPLoP】相依圖的一種節點（和資料庫、外部主機並列）。　【COMPSAC】其中一種依賴形式，靜態查詢檔會比對它。 |
| 叢集（cluster） | 術語 | AsianPLoP §1.1 第 1 段 | 一群一起跑容器化服務的機器，通常指 Kubernetes 叢集。 | 「連到叢集外」代表外部相依；「沒有連上叢集」代表拿不到執行期資料（④）。 |
| repo（repository） | 術語 | AsianPLoP §1.1 第 2 段；COMPSAC I-A | 版本控制（git）的程式碼儲存庫。 | 【AsianPLoP】程式碼散在多個 repo，是相依圖難畫的原因之一；③ 的答案以 repo 為單位保存。　【COMPSAC】分析的單位，名稱答案也以 repo 為單位保存。 |
| Kubernetes | 產品／平台 | AsianPLoP 表 1 | 開源的容器編排平台，負責部署、擴展與管理容器化服務。 | DepWeaver 與多數 Known Uses 的執行環境。 |
| Kubernetes manifest | 術語 | AsianPLoP 表 1；COMPSAC II-A | 用 YAML 宣告 Kubernetes 資源（Deployment、Service 等）的檔案。（COMPSAC 補充：描述 Kubernetes 資源（Deployment、Service、ConfigMap 等）的 YAML 檔。） | 【AsianPLoP】部署描述檔的一種，可以從裡面取得服務清單與連線設定。　【COMPSAC】DepWeaver 用它取得 workload 名稱、環境變數與位址（III-B、表 II）。 |
| Docker Compose | 工具 | AsianPLoP 表 1；COMPSAC II-A | 用一份 YAML 檔定義並同時啟動多個容器的工具。（COMPSAC 補充：用一個 YAML 檔定義並啟動多個容器（`services`、`depends_on`、`links`、`environment`）。） | 【AsianPLoP】部署描述檔的一種，MicroDepGraph 就從它取邊（§2）。　【COMPSAC】MicroDepGraph 取邊的來源，也是 DepWeaver 的靜態來源之一（III-B、表 II）。 |
| Helm chart | 工具／術語 | AsianPLoP 表 1 | Kubernetes 的套件格式：一組帶參數的 manifest 範本。 | 部署描述檔的一種。 |
| wiring（連線設定） | 術語 | AsianPLoP 表 1 | 設定層面「誰連到誰」，例如環境變數裡寫的對方位址。 | 部署描述檔看得到的東西，但「設定了」不等於「真的有呼叫」。 |
| 環境變數（environment variable） | 術語 | AsianPLoP 表 1 | 啟動程式時從外部注入的設定值，例如 `ORDERS_ADDR=orders:8080`。 | 從部署描述檔推出連線的主要線索（表 3 也有出現）。 |
| depends_on | 術語（Docker Compose 欄位） | AsianPLoP 表 1 | Docker Compose 用來宣告啟動先後的欄位。 | 部署描述檔裡的相依線索。 |
| HTTP client | 術語 | AsianPLoP 表 1；COMPSAC III-B | 程式中發出 HTTP 請求的函式庫或物件。 | 【AsianPLoP】靜態分析辨識呼叫點的樣式之一。　【COMPSAC】「有使用證據」的依據。 |
| Feign | 函式庫 | AsianPLoP 表 1；COMPSAC III-B | Java 的宣告式 HTTP client（Spring Cloud OpenFeign）：寫一個介面加上 annotation，就能呼叫遠端服務。 | 【AsianPLoP】程式碼裡常見的服務呼叫寫法，靜態分析要能認得。　【COMPSAC】最常見的 Java 服務間呼叫證據，例如 CustomersServiceClient.java:36。 |
| gRPC／gRPC stub | 術語／框架 | AsianPLoP 表 1 | gRPC 是 Google 開源的 RPC 框架；stub 是從 .proto 介面定義自動產生的用戶端程式碼。 | 另一種呼叫點樣式。 |
| 資料庫連線設定 | 術語 | AsianPLoP 表 1 | 例如 JDBC URL、`DB_URL` 這類設定。 | 推測「服務→資料庫」邊的線索，但可能宣告了卻沒在用。 |
| 動態分派（dynamic dispatch） | 術語 | AsianPLoP 表 1；COMPSAC 摘要 | 實際呼叫哪個目標要到執行期才決定，例如透過 registry 查名字、反射、多型。 | 【AsianPLoP】靜態分析的盲點，表 3 的 orders → shipping 就是一例。　【COMPSAC】靜態分析的盲點，TeaStore 是實例（IV-B）。 |
| 解析器（parser） | 術語 | AsianPLoP 表 1 | 把某種語言的原始碼解析成語法樹的元件。 | 沒有解析器的語言（例如例子中的 Node），靜態分析就看不到。 |
| 宣告了卻沒用到的設定 | 術語 | AsianPLoP 表 1；COMPSAC I-A | 設定檔裡有，但程式碼並沒有使用的設定。 | 【AsianPLoP】靜態來源會因此產生假邊（①② 的 forces）。　【COMPSAC】靜態方法的誤報來源。piggymetrics 的 7 條錯邊就屬於這類（IV-B）。 |
| 呼叫計數 | 術語 | AsianPLoP 表 1 | 服務網格 proxy 統計的「A 呼叫 B 幾次」。 | 執行期觀測的主要指標，例如表 3 的「95 次請求」。 |
| proxy（代理） | 術語 | AsianPLoP §1.1「執行期觀測是什麼」 | 夾在服務與網路之間、轉送並記錄流量的程式。 | 執行期資料從這裡產生。 |
| Istio | 產品（開源） | AsianPLoP §1.1；COMPSAC III-B | 最常見的開源服務網格，以 Envoy proxy 作為 sidecar。 | 【AsianPLoP】DepWeaver 的執行期資料來源；§5 前提 也引用它的 Kiali 教學。　【COMPSAC】執行期觀測的基礎設施。 |
| sidecar | 術語 | AsianPLoP §1.1；COMPSAC III-B | 和主程式容器放在同一個 Pod、一起部署的輔助容器（服務網格的 proxy 就是一例）。 | 【AsianPLoP】解釋執行期資料怎麼被收集。　【COMPSAC】由它記錄「誰呼叫誰、幾次、成功與否」。 |
| 函式庫（library） | 術語 | AsianPLoP §1.1 | 供程式呼叫的可重用程式碼。 | 追蹤函式庫是另一種收集方式；§5 提到框架函式庫的名稱常被誤認成服務。 |
| OpenTelemetry | 標準／開源專案 | AsianPLoP §1.1 | CNCF 的可觀測性標準，提供 traces、metrics、logs 的 API、SDK 與 Collector。 | 分散式追蹤的代表；§6 提到它的 virtual nodes。 |
| Prometheus | 產品（開源） | AsianPLoP §1.1；COMPSAC III-B | CNCF 的監控系統兼時間序列資料庫，定期拉取各服務的指標。 | 【AsianPLoP】Istio 的呼叫計數存在這裡，DepWeaver 從這裡查執行期邊。　【COMPSAC】DepWeaver 查詢它取得三組計數。 |
| 時間序列資料庫（time-series database） | 術語 | AsianPLoP §1.1 | 專門存「時間點→數值」資料的資料庫。 | 說明執行期資料存在哪裡。 |
| telemetry（遙測） | 術語 | AsianPLoP §1.1；COMPSAC II-A | 系統執行時自動送出的量測資料，包括指標、追蹤、日誌。 | 【AsianPLoP】本文給的白話定義是「呼叫真的發生時被記下來的資料」。　【COMPSAC】動態方法的輸入。 |
| 動態分析（dynamic analysis） | 術語 | AsianPLoP §1.1 | 在系統執行時觀察它的行為來分析。 | 架構重建文獻對「靠 telemetry 的方法」的稱呼。 |
| 帳密／帳號（只有人知道的值） | 術語 | AsianPLoP 表 1 | 登入用的帳號密碼、收款帳號這類只有部署者知道的值。 | 「人」這個來源才拿得到；① 的前提要靠 ③ 的方式向人取得。 |

## 1.2 問題

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 靜態分析（static analysis） | 術語 | AsianPLoP §1.2（「靜態架構重建工具」）；COMPSAC 摘要 | 不執行程式，只讀原始碼與設定檔來分析。 | 【AsianPLoP】和動態分析並列的兩大類重建方法。　【COMPSAC】三個來源之一（細節見 III-B）。 |
| F1（F1 score） | 術語（評估指標） | AsianPLoP §1.2；COMPSAC I-A | precision（找到的有多少是對的）和 recall（對的有多少被找到）的調和平均，介於 0 到 1。 | 【AsianPLoP】用來量化「單一來源不夠」：最佳單一工具 0.86，依特徵選出四個工具的最佳組合 0.91；LLM agent 約 0.6（§8）。　【COMPSAC】引用他人結果時使用。 |
| Schneider 等人 2025（schneider2025） | 論文 | AsianPLoP §1.2；COMPSAC I-A | EMSE 期刊論文：找到 13 個微服務靜態架構重建工具，實際比較其中 9 個；最佳單一工具 F1 0.86，依特徵選出最佳組合（共四個工具）後 0.91。注意這不是把四個工具的結果直接取聯集。 | 【AsianPLoP】主要證據：合併多個來源能提升完整度（也用在 ① 的 forces 與 Known Uses）。　【COMPSAC】支持「融合多來源比較好」的論點。 |
| Bakhtin 等人 2023（bakhtin2023，正式出版年 2024） | 論文 | AsianPLoP §1.2；COMPSAC II-A | ECSA 2023 工作坊論文，屬系統性對照研究（systematic mapping study）：盤點 37 個微服務架構重建工具，19 個純靜態、10 個純動態、8 個混合。 | 【AsianPLoP】說明「兩者都用」的工具還很少；§10 也引用。　【COMPSAC】說明混合方法還很少。 |
| Grinenko 等人 2026（grinenko2026） | 論文 | AsianPLoP §1.2；COMPSAC II-A | 2026 年的微服務架構重建方法主題式文獻回顧，結論是「沒有任何單一來源足夠」，並把「可靠地融合異質證據」和「跨方法共用的中間表示法」列為未解問題。（10/14 查核已對過原文。）（COMPSAC 補充：微服務架構重建方法的主題式文獻回顧，把「可靠地融合異質證據」列為未解問題，並指出混合工具鏈要允許人工驗證。） | 【AsianPLoP】用來界定本文要處理的問題；① 的前提和 §10 也引用。　【COMPSAC】支持本文的兩個切入點：融合，以及人工參與。 |
| 異質證據融合 | 術語 | AsianPLoP §1.2 | 把性質不同的來源（設定、程式碼、執行期、文件）合成一個結論。 | 文獻列出的未解問題，本文的 ①②④ 是部分回應。 |
| Forces | 術語（pattern 欄位） | AsianPLoP §1.2 | 套用解法時彼此衝突、必須取捨的需求或限制。 | 每個 pattern 都有 Forces 欄；§2 也以「沒有 forces」為由，不把三個背景做法寫成 pattern。 |

## 1.3 納入標準：兩個條件

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| PLoP（Pattern Languages of Programs） | 會議 | AsianPLoP §1.3 | Hillside Group 自 1994 年起舉辦的 pattern 會議，是同系列會議的源頭。 | 本文沿用 PLoP 系列對 pattern 的定義。 |
| Christopher Alexander | 人物 | AsianPLoP §1.3 | 建築師（1936–2022），著有《A Pattern Language》（1977）與《The Timeless Way of Building》（1979），是軟體 pattern 運動的思想源頭。 | pattern 的定義與 §4 的「generative」概念都出自他。 |
| 反覆出現（recurring） | 術語 | AsianPLoP §1.3 | pattern 必須是社群已在用的做法，不是個人發明。 | 本文把它具體化成兩個條件。 |
| ChatOps | 術語 | COMPSAC 摘要（AsianPLoP 稿 10/19 起不再出現） | 在聊天平台（例如 Slack、Discord）裡用機器人執行維運操作的做法。 | 【COMPSAC】DepWeaver 的運作形式：在 Discord 下指令、回答問題、拿報告。 |
| DepWeaver | 工具（作者自己的） | COMPSAC 摘要 | 作者開發的微服務相依分析工具，合併執行期、程式碼、文件與部署狀態。 | 【AsianPLoP】10/19 老師意見：COMPSAC 論文還沒發表，pattern 篇不能提到它；全文已無 DepWeaver，Known Uses 只剩論文與業界兩類。　【COMPSAC】論文主角。 |
| 別名 | pattern 欄位 | AsianPLoP §1.3 | 這個做法在業界或論文中的其他叫法。 | 方便讀者對照已知的術語。 |
| 一句話摘要 | pattern 欄位 | AsianPLoP §1.3 | 用一句話講完 pattern。 | 快速瀏覽用。 |
| Context | pattern 欄位 | AsianPLoP §1.3 | 問題出現在什麼情境，通常承接前一個 pattern 留下的狀態。 | 串起整個 language。 |
| Problem | pattern 欄位 | AsianPLoP §1.3 | 要解決的核心問題，本文以引言框列出。 | 每個 pattern 的核心。 |
| Solution | pattern 欄位 | AsianPLoP §1.3 | 解法步驟，最後附商店例子；本文用「因此：」開頭，沿用 Alexander 寫法中的「Therefore」。 | 每個 pattern 的核心。 |
| 實作要點 | pattern 欄位 | AsianPLoP §1.3 | 實作時要注意的細節與陷阱。 | 實作時的細節與陷阱，不指名是哪個工具的經驗。 |
| Resulting Context | pattern 欄位 | AsianPLoP §1.3 | 套用解法之後系統變成什麼狀態、還留下什麼問題。 | 用來引出下一個 pattern（generative 關係）。 |
| Consequences | pattern 欄位 | AsianPLoP §1.3 | 套用後的好處（＋）與代價（－）。 | 誠實交代取捨。 |
| Related Patterns | pattern 欄位 | AsianPLoP §1.3 | 和其他 pattern 的前後與互補關係。 | 說明 language 的結構。 |
| 服務目錄（service catalog） | 術語 | AsianPLoP §1.3 | 集中記錄組織內所有服務與擁有者的系統（例如 Backstage）。 | 本文的目標讀者之一；也是 ④ 的業界例子。 |
| 平台工程（platform engineering） | 術語 | AsianPLoP §1.3 | 替內部開發者建置共用工具與平台的團隊或職能。 | 目標讀者之一。 |
| 可觀測性平台（observability platform） | 術語 | AsianPLoP §1.3 | 整合指標、追蹤、日誌的監控產品（例如 Datadog、Dynatrace）。 | 目標讀者之一；許多 Known Uses 出自這類產品。 |

## 2 背景：三個證據來源

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 讀部署描述檔 | 方法（背景做法） | AsianPLoP §2 | 從 manifest、compose 檔取得服務清單與連線設定。 | 通過兩個條件，但只有一個顯而易見的做法、沒有 forces，所以當背景、不寫成 pattern。 |
| 抽取程式碼呼叫點 | 方法（背景做法） | AsianPLoP §2 | 用樣式比對或關鍵字搜尋，從原始碼找出呼叫與資料庫使用。 | 同上，當背景。 |
| 看執行期服務圖 | 方法（背景做法） | AsianPLoP §2 | 從服務網格計數或分散式追蹤畫出實際發生過的呼叫。 | 同上，當背景。 |
| MicroDepGraph | 工具（學術） | AsianPLoP §2；COMPSAC 摘要 | Rahman 等人提出的工具，從 docker-compose 中定義的相依關係與 Java 原始碼中的內部 API 呼叫取出服務相依圖。（COMPSAC 補充：Rahman、Panichella、Taibi 在 2019 年發表的資料集，收錄 20 個開源微服務系統的相依圖，邊取自 docker-compose 與 Java 內部 API 呼叫。） | 【AsianPLoP】「讀部署描述檔」的學界例子。　【COMPSAC】第三方參考邊集合，不是作者自己標的（IV-B）。 |
| Rahman 等人 2019（rahman2019） | 論文 | AsianPLoP §2；COMPSAC I-A | 公開一份整理過的開源微服務系統資料集，並用 MicroDepGraph 產生其中 20 個系統的相依圖（COMPSAC 稿的第三方參考邊集合就取自這裡）。 | 【AsianPLoP】證明讀部署描述檔是學界在用的做法。　【COMPSAC】靜態方法的代表，也是第三方參考邊集合的來源。 |
| Soldani 等人 2022（soldani2022） | 論文 | AsianPLoP §2；COMPSAC II-A | CLOSER 2022 論文，「離線」挖掘微服務架構：從 Kubernetes 部署檔取得節點，邊則取自 Kiali 先前產生的執行期圖，屬於混合方法。 | 【AsianPLoP】「讀部署描述檔」的另一個學界例子。　【COMPSAC】混合方法的先例。 |
| Backstage | 產品（開源） | AsianPLoP §2 | Spotify 開源、現為 CNCF 專案的開發者入口網站，核心功能是 Software Catalog。 | §2 用來舉 dependsOn 的例子；④ 用它作為「統一目錄」的業界例子。 |
| dependsOn | 術語（Backstage 欄位） | AsianPLoP §2 | Backstage 目錄實體之間的「依賴」關係欄位（backstagerel 文件）。 | 證明業界的服務目錄也用宣告的方式記錄相依。 |
| REST client | 術語 | AsianPLoP §2 | 呼叫 REST API（以 HTTP 動詞操作資源的 Web API 風格）的用戶端程式碼。 | 靜態分析比對的樣式之一。 |
| 持久化 annotation（persistence annotation） | 術語 | AsianPLoP §2 | Java 中標示「這個類別要存進資料庫」的 annotation。 | 推斷「服務真的用到資料庫」的程式碼證據。 |
| annotation（註記） | 術語 | AsianPLoP §2；COMPSAC III-B | Java 等語言中以 `@` 開頭、附在類別或方法上的中繼資料。 | 【AsianPLoP】樣式比對的線索。　【COMPSAC】用來辨識持久化程式碼。 |
| @Entity | 術語（JPA annotation） | AsianPLoP §2；COMPSAC III-B | 範例專案內建、持續送出模擬請求的負載產生器。Online Boutique 的 loadgenerator 模擬購物流程；Bank of Anthos 也有一個，同樣以 Locust 撰寫。 | 【AsianPLoP】持久化 annotation 的代表；表 3 的 orders → orders-db 由此佐證。　【COMPSAC】Java 端「這個服務真的有用資料庫」的證據。 |
| Bushong 等人 2022（bushong2022） | 論文 | AsianPLoP §2；COMPSAC I-A | CLOSER 2022 論文，以靜態分析重建微服務系統的整體架構，比對 REST 呼叫與持久化實體等程式碼樣式。 | 【AsianPLoP】「抽取程式碼呼叫點」的學界例子。　【COMPSAC】靜態方法的代表。 |
| Schneider 與 Scandariato 2023（code2dfd2023，工具名 code2DFD） | 論文 | AsianPLoP §2；COMPSAC I-A | JSS 期刊論文，從 Java 微服務自動抽出帶安全資訊的資料流圖（DFD），每個模型元素都能追溯到程式碼片段。（COMPSAC 補充：從 Java 微服務的原始碼與設定檔中找關鍵字當證據，自動產生帶安全資訊的資料流圖。） | 【AsianPLoP】「抽取程式碼呼叫點」的例子；也呼應 ① 的出處（provenance）概念。　【COMPSAC】靜態方法的代表。 |
| 可追溯性（traceability） | 術語 | AsianPLoP §2 | 模型中的每個元素都能回頭找到來源（檔案、行號）。 | 和 ① 的「出處」是同一個精神。 |
| 服務圖（service graph／service map） | 術語 | AsianPLoP §2 | 由執行期資料自動畫出的服務呼叫拓樸圖。 | 第三個背景做法的產出物。 |
| Al Maruf 等人 2022（almaruf2022） | 論文 | AsianPLoP §2；COMPSAC I-A | SOSE 2022 論文，利用微服務已在收集的 telemetry 資料（日誌、追蹤、指標）做系統的動態分析。（COMPSAC 補充：利用系統已在收集的遙測資料（日誌、追蹤、指標）做微服務的動態分析。） | 【AsianPLoP】「看執行期服務圖」的學界例子。　【COMPSAC】動態方法的代表。 |
| Kiali | 產品（開源） | AsianPLoP §2；COMPSAC II-A | Istio 的管理與視覺化主控台，以 Prometheus 中的 Istio 指標畫出服務圖。 | 【AsianPLoP】「看執行期服務圖」的業界例子；§5 前提 引用它的教學。　【COMPSAC】soldani2022 的執行期邊來源。 |
| Grafana Tempo | 產品（開源） | AsianPLoP §2 | Grafana Labs 的分散式追蹤後端，可以從 trace 產生服務圖指標。 | 業界例子；§6 提到它的 virtual nodes。 |
| Datadog | 產品（商用 SaaS） | AsianPLoP §2 | 商用可觀測性平台；本文引用它的 Service Map、inferred services、service remapping、monitor 設定。 | 在 §2、②、③、④ 都是 Known Use。 |
| AWS X-Ray | 產品（雲端服務） | AsianPLoP §2 | AWS 的分散式追蹤服務，會畫出服務圖（trace map）。 | §2 業界例子；② 引用它的 inferred segments。 |

## 3 貫穿全文的例子

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 線上商店例子（web、orders、payment、shipping、catalog、cart、orders-db、catalog-db） | 例子 | AsianPLoP §3 圖 1 | 虛構的小型電商：web 是入口，orders 呼叫付款與出貨，catalog 和 orders 各有資料庫，cart 是購物車。 | 每個 Solution 最後都用它舉例，表 3 列出每條邊在各來源中的樣子。 |
| 文件（documentation，作為來源） | 術語 | AsianPLoP 表 3；COMPSAC 摘要 | 人寫或自動產生的系統說明（DepWeaver 用 DeepWiki 這類文件）。 | 【AsianPLoP】第四種來源，最弱：可能寫錯，或用不同的名稱（例如 basket-service）。　【COMPSAC】第三個來源，可靠度最低，可能過時（III-B）。 |
| registry（服務註冊中心） | 術語 | AsianPLoP 表 3 | 服務啟動時登記名字、呼叫方在執行期查名字找位址的元件（例如 Eureka、Consul）。 | 造成動態分派，靜態看不到 orders → shipping。 |
| JPA（Java Persistence API，現稱 Jakarta Persistence） | 標準 | AsianPLoP 表 3 | Java 的 ORM 規格，以 annotation 把類別對應到資料表。 | orders → orders-db 的程式碼證據。 |
| TCP 連線 | 術語 | AsianPLoP 表 3 | 傳輸層的連線；資料庫流量通常只看得到連線數，看不到 HTTP 請求數。 | 說明 DB 邊的量測單位是「連線」，④ 也因此強調措辭要寫「連線數」。 |
| Node（Node.js） | 平台 | AsianPLoP 表 3 | JavaScript 執行環境。 | 例子中 cart 用它寫成，作為「沒有解析器的語言」。 |
| basket-service | 例子名稱 | AsianPLoP 表 3 | 文件對 cart 的另一種稱呼。 | 名稱對不上的例子，③ 用它示範請人確認。 |

## 4 Pattern Language 全貌

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 建圖組（①–③） | 本文分組 | AsianPLoP §4 | 回答「三個來源都有了，怎麼拼出一張可信的圖」。 | 圖 2 上排。 |
| 交付組（④） | 本文分組 | AsianPLoP §4 | 回答「把圖變成數字、報告與問答時，怎麼不走樣」。 | 圖 2 下排，只有 ④ 一個；已有圖的團隊可以直接讀這組。 |
| generative 關係 | 術語（Alexander） | AsianPLoP §4 | 一個 pattern 套用後留下的問題，自然引出下一個 pattern，讓整套 pattern 能「生成」設計。 | 圖 2 箭頭上的文字就是這種關係。 |
| catalog（pattern 目錄） vs. language | 術語 | AsianPLoP §4 | catalog 是彼此獨立的 pattern 清單；language 中的 pattern 有前後銜接。 | 說明本文為什麼自稱 language。注意和 ④ 名稱裡的「目錄」是不同意思。 |

## 5 混合重建（Hybrid Reconstruction）｜前提：先以流量走遍路徑（Exercise the Paths）

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 合成流量（synthetic traffic） | 術語 | AsianPLoP §5 前提 別名 | 由程式模擬使用者送出的請求，不是真實使用者的流量。 | ① 的前提的別名。 |
| 負載產生器（load generator） | 術語／工具類別 | AsianPLoP §5 前提 別名 | 自動、持續送出大量請求的程式（例如 Locust、k6、JMeter）。 | ① 的前提的主要手段之一。 |
| synthetic monitoring（合成監控） | 術語 | AsianPLoP §5 前提 別名 | 定期執行腳本化的模擬使用者操作，檢查服務是否正常。 | 業界對同類做法的稱呼。 |
| workload coverage（工作負載覆蓋率） | 術語 | AsianPLoP §5 前提 別名 | 執行期分析時，送進去的工作負載走到系統行為的比例。 | 動態分析文獻對 ① 的前提問題的稱呼（Grinenko）。 |
| 正式環境／測試環境（production／test environment） | 術語 | AsianPLoP §5 前提 Context | 正式環境服務真實使用者；測試環境用來驗證，資料可以丟棄。 | 寫入類流量只能在測試環境跑（副作用 force）。 |
| 4xx | 術語（HTTP 狀態碼） | AsianPLoP §5 前提 Forces；COMPSAC 表 III | 用戶端錯誤類狀態碼，例如 400、401、404。 | 【AsianPLoP】說明亂送的請求會卡在第一個錯誤，走不到深處。　【COMPSAC】第 2 層的回饋訊號。 |
| HTTP 200 | 術語（HTTP 狀態碼） | AsianPLoP §5 前提 Forces；COMPSAC III-F | 「成功」狀態碼。（COMPSAC 補充：回傳 200，但預期的邊沒有出現時，視為請求被拒絕。） | 【AsianPLoP】有些應用拒絕表單也回 200，所以要看相依邊有沒有出現，不能只看狀態碼。　【COMPSAC】因為有些伺服器端渲染的應用拒絕表單時也回 200。 |
| 副作用（side effect） | 術語 | AsianPLoP §5 前提 Forces | 請求除了取得回應之外，還改變了系統狀態（例如寫入資料）。 | 流量的代價之一。 |
| 端對端測試（end-to-end test） | 術語 | AsianPLoP §5 前提 Solution | 從使用者入口一路走到後端的整合測試。 | 產生流量的另一種來源。 |
| 停止條件 | 術語 | AsianPLoP §5 前提 Solution 3 | 「靜態有、執行期未量測」的清單不再縮短時就停。 | 回應「永遠走不完」這個 force。 |
| curl、watch | 工具（命令列） | AsianPLoP §5 前提 實作要點、Known Uses | curl 從命令列送 HTTP 請求；watch 每隔一段時間重複執行指令。 | repo 裡常見的現成流量來源；Istio 教學也用它們送流量。 |
| 整合測試、README | 術語 | AsianPLoP §5 前提 實作要點 | 專案附的測試與說明檔。 | 先找現成的流量腳本，找不到再自己寫。 |
| Postman collection | 標準（檔案格式） | AsianPLoP §5 前提 實作要點；COMPSAC III-F | Postman 工具的 API 請求集合格式（JSON，DepWeaver 用 v2.1）。 | 【AsianPLoP】把流量腳本存成標準格式，方便重跑。　【COMPSAC】流量腳本使用這個格式。第 3 層的問題以「空值變數加上說明」宣告在裡面。執行器是 DepWeaver 自己寫的，不是 Newman。 |
| 測試缺口清單 | 術語 | AsianPLoP §5 前提 Consequences | 「還沒量測到的邊」清單同時也指出測試還沒涵蓋的地方。 | ① 的前提的附帶好處。 |
| Cornelissen 等人 2009（cornelissen2009） | 論文 | AsianPLoP §5 前提 Known Uses | IEEE TSE 論文，以動態分析理解程式的系統性文獻調查。 | 引用它的論點：動態分析本質上不完整，難處在於決定要執行哪些情境，可以借用測試套件。 |
| Istio 的 Kiali 教學（istio 文件〈Visualizing Your Mesh〉） | 業界文件 | AsianPLoP §5 前提 Known Uses | Istio 官方的 Kiali 操作教學。 | 業界證據：教學先叫使用者反覆送流量，因為服務圖只顯示一段時間內真的流過的流量。 |
| Online Boutique | 範例專案（Google） | AsianPLoP §5 前提 Known Uses；COMPSAC IV-A | Google Cloud 的微服務示範電商（約 11 個服務、多種語言），內建 loadgenerator（以 Locust 撰寫）。（COMPSAC 補充：Google 的 microservices-demo，約 11 個服務，以 Go、C#、Node、Python、Java 撰寫，用 gRPC 溝通。） | 【AsianPLoP】業界證據：範例專案自帶負載產生器。　【COMPSAC】RQ1。逼出規則 14。 |
| loadgenerator | 元件 | AsianPLoP §5 前提 Known Uses；COMPSAC 表 III | Online Boutique 裡持續模擬購物流程的服務。 | 【AsianPLoP】同上。　【COMPSAC】第 1 層的範例請求來源之一，例如存款欄位。第 1 層只收負載產生器、端對端測試、OpenAPI 與 Postman 範例，不讀 README。 |
| information fusion（多來源融合） | 術語 | AsianPLoP §5 別名；COMPSAC 摘要 | 把多個感測器或資料來源的資訊合成一個較可靠的結論，源自訊號處理與資料融合領域。 | 【AsianPLoP】① 的別名；和作者碩論題目的方向（Multi-Source Fusion）相同。　【COMPSAC】論文題目的「Multi-Source Fusion」。III-A 起英文寫 canonical model。 |
| ServiceNow Service Mapping | 產品（商用） | AsianPLoP §5 別名 | ServiceNow ITOM 產品線中的模組，自動找出業務服務背後的設定項目（CI）與連線，寫進 CMDB。 | ① 和 ③ 的業界 Known Use。 |
| pattern-based／traffic-based mapping（discovery） | 術語（ServiceNow） | AsianPLoP §5 別名 | pattern-based 依預先定義的辨識規則找應用與連線；traffic-based 依實際網路連線資料補充。 | 對應「靜態＋動態」的業界說法。 |
| vFunction | 產品（商用） | AsianPLoP §5 別名 | 架構可觀測性（architectural observability）平台，主要用於把單體應用現代化、拆成服務；結合動態與靜態分析。 | ① 的業界 Known Use。 |
| 鍵（key） | 術語 | AsianPLoP §5 Solution；COMPSAC I-B | 合併時用來判斷「是不是同一條邊」的識別值，這裡是（來源服務，目標服務）。（COMPSAC 補充：同一對節點只保留一條邊，等級取各來源中最高的，出處全部保留。） | 【AsianPLoP】① 的核心規則。　【COMPSAC】貢獻 1，即演算法 1（III-C）。 |
| provenance（出處） | 術語 | AsianPLoP §5 Solution 3；COMPSAC 摘要（關鍵字：出處追溯） | 每條邊的證據來源：檔案與行號、指標名稱、文件段落。 | 【AsianPLoP】讓每條邊都能回頭查證；② 把標記和出處分開存。　【COMPSAC】每條邊都帶完整的出處清單，合併時取聯集。 |
| 加權投票／機率模型 | 術語 | AsianPLoP §5 Forces | 依來源可信度加權，或用機率計算邊存不存在的合併方法。 | 本文認為這類方法難以向讀者解釋，所以採「只做加法、保留出處」。 |
| 假邊（false edge） | 術語 | AsianPLoP §5 Forces | 圖上有、實際上不存在的相依關係。 | 弱來源帶進的雜訊。 |
| 骨架（skeleton） | 術語 | AsianPLoP §5 實作要點 | 先用最可靠的來源建立的基本圖，再往上疊其他來源。 | 先以最可靠的來源建骨架，再往上疊。 |
| 框架函式庫的名稱 | 術語 | AsianPLoP §5 實作要點；COMPSAC III-D | 例如快取、斷路器函式庫的名稱，常被誤認成服務。（COMPSAC 補充：例如 Eureka、Hystrix 這類函式庫名稱被誤畫成節點。） | 【AsianPLoP】要用可設定的排除清單濾掉。　【COMPSAC】第一類雜訊，用「函式庫名單」處理。 |
| 快取（cache） | 術語 | AsianPLoP §5 實作要點 | 暫存常用資料以加速存取的元件。 | 例子：快取函式庫被誤認為服務。 |
| 斷路器（circuit breaker） | 術語 | AsianPLoP §5 實作要點 | 下游一直失敗時暫時停止呼叫、避免連鎖故障的機制（例如 Resilience4j、Hystrix）。 | 同上。 |
| 可設定的排除清單 | 術語 | AsianPLoP §5 實作要點；COMPSAC III-F | 列出「不是服務」的名稱，而且可以依專案調整。 | 【AsianPLoP】降低假節點。　【COMPSAC】hystrix 被它誤刪，是 RQ3 的案例。 |
| Mayer 與 Weinreich 2018（mayer2018） | 論文 | AsianPLoP §5 Forces；COMPSAC II-A | SOSE 2018 論文，提出擷取微服務架構的方法，結合靜態的服務資訊、基礎設施資訊與執行期資訊，理由是有些關係只在執行期看得到。 | 【AsianPLoP】證據：有些關係只在執行期看得到，所以要混合來源。　【COMPSAC】支持融合的動機。 |
| 部署狀態 | 術語 | AsianPLoP §6 Related Patterns、§8 例子 | 某個服務在叢集裡有沒有部署。 | ④ 討論它「未知」時怎麼處理：預設未知，不是 false。 |

## 6 實測優先於推測（Observation over Inference）

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| inferred services（推測服務，Datadog） | 術語 | AsianPLoP §6 別名 | Datadog 依用戶端 span 推測出的下游服務（資料庫、佇列、第三方 API），這些服務本身沒有被監測。 | ② 的業界別名，出處是 Datadog 的〈Inferred Services〉文件（datadoginferred）。 |
| inferred segments（推測區段，AWS X-Ray） | 術語 | AsianPLoP §6 別名；COMPSAC II-E | X-Ray 依呼叫方記錄的下游呼叫產生的推測區段；被呼叫的服務自己送出資料後就會取代它（依本文）。 | 【AsianPLoP】② 的業界 Known Use，正好對應「更強的證據取代較弱的」。　【COMPSAC】業界只做「量測 vs. 推測」二分，本文擴充成三級。 |
| virtual nodes（虛擬節點） | 術語 | AsianPLoP §6 別名 | Grafana Tempo 與 OpenTelemetry 的服務圖中，對沒有被監測的一端（例如外部服務、資料庫）畫出的節點。 | ② 的業界別名。 |
| uncertainty in graph edges | 術語 | AsianPLoP §6 別名 | 視覺化研究的主題：怎麼在圖的邊上呈現不確定性。 | 學界別名，出自 Guo 2015。 |
| partial models（部分模型） | 術語 | AsianPLoP §6 別名 | 能明確表達「哪些部分還不確定」的模型。 | 學界別名，出自 Famelis 2012。 |
| 實測／推測標記 | 術語 | AsianPLoP §6 Solution | 每條邊一個標記：執行期觀測記錄到的是「實測」，只來自程式碼、設定或文件的是「推測」。 | ② 的核心；和出處分開儲存。 |
| 視覺通道（visual channel／visual variable） | 術語 | AsianPLoP §6 Forces；COMPSAC II-E | 圖上能用來編碼資訊的視覺屬性：線型、顏色、粗細、形狀等。 | 【AsianPLoP】最醒目的通道（實線／虛線）要留給可信程度。　【COMPSAC】DepWeaver 用點線、虛線、實線三種線型。 |
| 實線／虛線（／點線） | 術語 | AsianPLoP §6 Solution 4；COMPSAC III-C | AsianPLoP 只講實測＝實線、推測＝虛線兩級；COMPSAC 用三級三種線型（Mermaid 沒有點線，僅推測改畫虛線加「?」）。 | 【AsianPLoP】② 把最醒目的視覺通道給實測／推測標記；實作要點說兩級是底線，要細分就得在圖例交代。　【COMPSAC】讀者一眼分辨可信度。 |
| 有使用證據 | 術語（DepWeaver） | COMPSAC III-C／表 I（AsianPLoP 稿已不出現） | 有會發出呼叫的程式碼或服務位址設定，或資料庫連線設定加上持久化程式碼。注意英文代號叫 documented，但意思不是「文件提到」。 | 【COMPSAC】DepWeaver 介於實測與推測之間的中間級，畫成虛線，計入分母。 |
| 圖例（legend） | 術語 | AsianPLoP §6 實作要點 | 說明圖上符號意義的區塊。 | 必須跟著圖一起匯出。 |
| 降級（downgrade） | 術語 | AsianPLoP §6 Solution 5 | 下游產出物只能維持或降低可信程度，不能把推測講成確定。 | 約束報告與摘要。 |
| Guo 等人 2015（guo2015） | 論文 | AsianPLoP §6 Forces；COMPSAC II-E | IEEE TVCG 論文，以受控實驗比較在圖的邊上同時呈現主屬性與不確定性的成對視覺變數。 | 【AsianPLoP】證據：視覺變數會互相干擾，所以要把最醒目的通道留給可信程度。　【COMPSAC】用線型表示信心等級的依據。 |
| Famelis 等人 2012（famelis2012） | 論文 | AsianPLoP §6 Known Uses；COMPSAC II-E | ICSE 2012 論文，提出 partial models，讓模型能表達建模者的不確定性並據以推理。 | 【AsianPLoP】學界依據：過早選定一個答案會帶進錯誤資訊。　【COMPSAC】支持「圖要標示不確定性」。 |
| AWS CloudWatch Application Map | 產品（雲端服務） | AsianPLoP §6 Known Uses | AWS CloudWatch 中呈現應用程式拓樸與運作健康狀態的地圖檢視。 | 業界證據：沒有被量測的服務以虛線框或不同顏色呈現。 |

## 7 請人確認名稱對應（Human-Confirmed Name-Mapping）

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| Reflexion model（反射模型）的 mapping | 方法（學界） | AsianPLoP §7 別名 | Murphy 等人的方法：工程師畫出高階模型，再指定模型元件對應到哪些原始碼（mapping），工具據此算出一致、不一致、缺少的部分。 | ③ 的學界源頭：「對應」由人指定。 |
| interactive deduplication（互動式重複資料比對） | 方法（學界） | AsianPLoP §7 別名 | 判斷兩筆紀錄是否指同一實體時，讓系統挑出最難判斷的配對請人標記。 | ③ 的另一個學界源頭。 |
| 實體對應（entity resolution／record linkage） | 術語 | AsianPLoP §7 Known Uses | 判斷不同資料來源中的名稱或紀錄是不是同一個實體。 | 名稱對應問題在資料庫領域的叫法。 |
| 主動學習（active learning） | 術語 | AsianPLoP §7（Sarawagi 論文題目） | 機器學習中由模型挑出最有資訊量的樣本請人標註。 | 呼應 ③「只問規則真的判斷不了的」。 |
| service remapping（Datadog） | 術語 | AsianPLoP §7 別名；COMPSAC II-D | Datadog 讓使用者用規則改名、合併或拆分服務的功能。 | 【AsianPLoP】③ 的業界 Known Use，不過是「事後寫規則」型。　【COMPSAC】業界做法是事後寫規則；DepWeaver 是在分析進行中、在對話裡直接問。 |
| Dynatrace | 產品（商用）／公司 | AsianPLoP §7 別名 | 商用可觀測性平台，產品包括 Smartscape 拓樸、Davis AI 等。 | ③ 的別名來源（merged services、service detection rules），也是 ④ 的 Known Use。 |
| merged services／service detection rules（Dynatrace） | 術語 | AsianPLoP §7 別名 | Dynatrace 把偵測到的多個服務合併成一個（merged services），以及自訂如何辨識服務的規則（service detection rules）。 | ③ 的業界別名。 |
| 手動加入／排除連線（ServiceNow） | 術語 | AsianPLoP §7 別名、Known Uses | ServiceNow 讓使用者審查建議的連線，記錄成手動加入或手動排除。 | §9 說這是最接近 ③ 的業界做法。 |
| 模糊比對（fuzzy matching） | 術語 | AsianPLoP §7 Problem；COMPSAC I-B | 依字串相似度自動判斷兩個名稱是否相同。 | 【AsianPLoP】③ 反對的做法：可能憑相似度捏造邊。　【COMPSAC】DepWeaver 刻意不用，改成附排序過的候選讓人確認。 |
| 排序過的候選 | 術語 | AsianPLoP §7 Solution 2；COMPSAC I-B、III-F | 列出可能的對應對象並排好順序，另附「新節點」「忽略」選項。（COMPSAC 補充：1. 去除 service、api、client 等通用詞後的包含關係。2. 共用字詞。3. 編輯距離。） | 【AsianPLoP】排序只給人看，不替人做決定。　【COMPSAC】只用來呈現給人看，不由工具決定。；只用於呈現，不做決定。 |
| 編輯距離（edit distance） | 術語 | AsianPLoP §7 實作要點；COMPSAC III-F | 一個字串最少要經過幾次插入、刪除、替換才能變成另一個（例如 Levenshtein 距離）。 | 【AsianPLoP】候選排序的依據之一。　【COMPSAC】排序訊號之一。 |
| 確定性規則解析 | 術語 | AsianPLoP §7 Solution 4；COMPSAC III-F | 用固定規則解讀人的回答（例如「1」就是第一個候選），看不懂就保留問題。（COMPSAC 補充：new 表示建立新節點，ignore 表示忽略。） | 【AsianPLoP】不讓 LLM 或猜測介入答案。　【COMPSAC】答案以確定性規則解析，解析不了就保留問題、不猜。 |
| 審查頁面、對話 | 術語 | AsianPLoP §7 Solution 3 | 使用者平常就在用的介面。 | 在那裡一次把問題問完。DepWeaver 是在 Discord 上提問。 |
| Murphy 等人 2001（murphy2001） | 論文 | AsianPLoP §7 Known Uses；COMPSAC II-D | IEEE TSE 論文〈Software Reflexion Models〉，提出上述的 reflexion model。 | 【AsianPLoP】③ 的學界 Known Use。　【COMPSAC】經典的「人提供對應」先例。拼法是 reflexion，不是 reflection。 |
| Sarawagi 與 Bhamidipaty 2002（sarawagi2002） | 論文 | AsianPLoP §7 Known Uses | KDD 2002 論文，以主動學習做互動式重複資料比對，挑出最難的配對請使用者標記（系統名 ALIAS）。 | ③ 的學界 Known Use。 |

## 8 基於事實的解釋（Fact-Based Explanation）｜併入：一份目錄、多個視圖（One Catalog, Many Views）、無資料不等於零（No Data Is Not Zero）

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| Software Catalog（Backstage） | 術語／功能 | AsianPLoP §8 別名 | Backstage 集中記錄服務、擁有者與中繼資料的目錄，多數工具可以以目錄裡的實體為中心組織。 | ④ 的業界 Known Use。 |
| Dynatrace Smartscape | 產品功能 | AsianPLoP §8 別名 | Dynatrace 平台內建的即時拓樸模型（主機、行程、服務之間的關係）；官方文件說平台各處都有拓樸模型的例子。 | ④ 的業界 Known Use。 |
| 拓樸模型（topology model） | 術語 | AsianPLoP §8 別名 | 描述系統元件與連接關係的資料模型。 | ④ 的別名。 |
| 中間表示法（intermediate representation，IR） | 術語 | AsianPLoP §8 別名 | 原本是編譯器術語：介於輸入與各種輸出之間的共用資料格式。 | ④ 的別名；Grinenko 把「跨方法共用的 IR」列為未解問題（§10）。 |
| metamodel（後設模型） | 術語 | AsianPLoP §8 別名 | 規定模型裡可以有哪些元素與關係的「模型的模型」。 | 架構重建文獻對統一模型的叫法。 |
| Model–View–Controller（MVC） | 架構模式 | AsianPLoP §8 別名 | 經典的 UI 架構模式（源自 Smalltalk）：model 存資料，view 呈現，controller 處理輸入。 | 類比：④ 的統一模型就是 MVC 的 model，所有產出物都是 view。 |
| 統一模型 | 術語 | AsianPLoP §8 一句話摘要；COMPSAC III-A | 每次分析先建立、所有產出物共用的資料物件。 | 【AsianPLoP】④ 的核心。　【COMPSAC】所有產出物都由它產生（III-E）。 |
| 視圖（view） | 術語 | AsianPLoP §8 | 從模型產生的產出物：圖、覆蓋率、報告、問答、匯出。 | 每個視圖都完全由模型決定（是模型的函數）。 |
| schema（資料結構定義） | 術語 | AsianPLoP §8 Forces | 模型有哪些欄位、什麼型別的定義。 | 代價：schema 會越長越大，是所有視圖的共同介面。 |
| 衍生計算 | 術語 | AsianPLoP §8 Solution 2；COMPSAC III-E | 從模型算出的分數、分層、排序等。 | 【AsianPLoP】只實作一次，避免各產出物算出不同結果。　【COMPSAC】避免兩套算法得出不同結果。 |
| 覆蓋率（coverage） | 術語（DepWeaver 指標） | AsianPLoP §7 Resulting Context（§8 例子）；COMPSAC 摘要 | DepWeaver 中「該量測的邊有多少被量測到」的比例（例如 4/4）。 | 【AsianPLoP】典型的數字型產出物；§9、④ 都討論它。　【COMPSAC】本文重新定義了它的分母（III-E）。 |
| 單元測試（unit test） | 術語 | AsianPLoP §8 實作要點；COMPSAC III-D | 不需要外部環境、只測一小段邏輯的自動化測試。 | 【AsianPLoP】視圖由模型決定，所以「報告與圖是否一致」可以寫成單元測試。　【COMPSAC】每條規則都有。 |
| 中繼資料（metadata） | 術語 | AsianPLoP §8 實作要點（§8 再次出現） | 描述資料本身的資料，例如「這次收集了哪些來源」。 | 要和模型一起存，④ 才能運作。 |
| Alshuqayran 等人 2018（alshuqayran2018） | 論文 | AsianPLoP §8 Known Uses | ICSA 2018 論文，以 metamodel 加上對應規則，把多個開源微服務系統的實作產物（程式碼、docker-compose、建置檔、Dockerfile）對應到同一個架構 metamodel 的實證研究（方法後來稱 MiSAR）。 | ④ 的學界 Known Use。 |
| Cerny 等人 2022（cerny2022） | 論文 | AsianPLoP §8 Known Uses | SOSE 2022 論文，回顧微服務架構重建與視覺化技術。 | 證據：重建結果通常是系統模型，再依觀點產生不同的視覺化；§10 也引用。 |
| Dynatrace〈Custom Topology Model〉文件 | 業界文件 | AsianPLoP §8 Known Uses | Dynatrace 說明如何擴充拓樸模型的官方文件。 | 支持「Smartscape 是平台內建拓樸模型」的引用出處。 |
| analytics augmented generation（分析增強生成） | 術語 | AsianPLoP §8 別名 | Satyrn 提出的做法：先用分析程式算出事實，再交給 LLM 寫成文字，類比 RAG 的「檢索增強生成」。 | ④ 的學界別名。 |
| Satyrn（Sterbentz 等人 2024，satyrn2024） | 論文／平台 | AsianPLoP §8 別名；COMPSAC II-C | EMNLP 2024 論文提出的平台：事實由分析程式產生再交給 LLM，報告中正確陳述超過 86%；直接讓 GPT-4 Code Interpreter 寫只有 57%。 | 【AsianPLoP】④ 最直接的量化證據。　【COMPSAC】「事實由程式產生」的依據。 |
| program-aided language models（PAL，Gao 等人 2023，pal2023） | 論文／方法 | AsianPLoP §8 別名；COMPSAC II-C | ICML 2023 論文：讓 LLM 把推理寫成程式，交給 Python 直譯器算出答案，不讓 LLM 自己算。 | 【AsianPLoP】④ 的學界 Known Use。　【COMPSAC】同樣是「計算交給程式」的分工依據。 |
| 直譯器（interpreter） | 術語 | AsianPLoP §8 Known Uses；COMPSAC II-C | 逐行執行程式的程式（例如 Python 直譯器）。 | 【AsianPLoP】PAL 中實際負責計算的元件。　【COMPSAC】PAL 的計算者。 |
| hypermodal AI | 術語（Dynatrace 行銷用語） | AsianPLoP §8 別名 | Dynatrace 對結合預測 AI、因果 AI 與生成式 AI 的稱呼。 | ④ 的業界別名：因果 AI 算、生成式 AI 解釋。 |
| causal AI（因果 AI） | 術語 | AsianPLoP §8 別名 | 依系統拓樸與因果關係推論根因的確定性分析（Dynatrace Davis 的核心）。 | 對應 ④ 裡「事實由程式算」的那一面。 |
| generative AI（生成式 AI） | 術語 | AsianPLoP §8 別名 | 產生文字、圖像等內容的 AI（例如 LLM）。 | 對應 ④ 的「再解釋」。 |
| 預測 AI（predictive AI） | 術語 | AsianPLoP §8 Known Uses | 依歷史資料預測趨勢或異常的 AI。 | Dynatrace 確定性答案的一部分。 |
| Davis AI／Davis CoPilot | 產品功能（Dynatrace） | AsianPLoP §8 Known Uses | Davis 是 Dynatrace 的 AI 引擎；Davis CoPilot 是 2023 年 7 月宣布的生成式 AI 助理，以自然語言解釋 Davis 的結果。 | ④ 的業界 Known Use，出處是官方部落格（§11 列為效度威脅）。 |
| K8sGPT | 工具（開源） | AsianPLoP §8 別名 | 掃描 Kubernetes 叢集問題的開源工具（CNCF sandbox 專案）：由預先寫好的 analyzer 診斷，開 explain 選項時才請 LLM 解釋結果。 | ④ 的業界 Known Use。 |
| analyzer／explain（K8sGPT） | 術語 | AsianPLoP §8 別名 | analyzer 是寫死規則的檢查器；explain 是請 LLM 用自然語言解釋 analyzer 的結果。 | 「先算再解釋」的具體分工。 |
| prompt（提示詞） | 術語 | AsianPLoP §8 Problem；COMPSAC III-F | 交給 LLM 的指令與輸入文字。 | 【AsianPLoP】④ 的論點：改 prompt 不能根治重述錯誤，有些事不該交給 prompt。　【COMPSAC】流量產生器的 prompt 另有規定。 |
| LLM agent | 術語 | AsianPLoP §8 Forces；COMPSAC I-A | 能自行呼叫工具、多步驟行動的 LLM 系統。 | 【AsianPLoP】Liljas 2026 測試它重建架構的能力。　【COMPSAC】用來對照「純靠 LLM」的效果。 |
| Liljas 等人 2026（liljas2026） | 論文 | AsianPLoP §8 Forces；COMPSAC I-A | ESEM 2026 論文，評估 LLM agent 能否自行重建微服務架構；只比較了兩個模型，較好的 GPT-4o 平均 F1 約 0.6（0.604）。 | 【AsianPLoP】證據：LLM 自己算事實不夠準。　【COMPSAC】證明「只靠 LLM」不夠可靠。 |
| 事實 vs. 詮釋 | 術語 | AsianPLoP §8 Solution 1 | 事實有唯一正確答案、模型裡都有；詮釋是角色、風險、建議。 | ④ 的分工界線；劃錯的話，LLM 會在詮釋裡重述事實。 |
| 結構錨點（structural anchor） | 術語 | AsianPLoP §8 實作要點；COMPSAC III-E | 報告中可以定位的位置（例如下一節的標題），程式產生的段落插在那裡。 | 【AsianPLoP】找不到錨點就附在最後，確保事實不會遺失。　【COMPSAC】找不到錨點時附在報告最後。 |
| 範本文字 | 術語 | AsianPLoP §8 Consequences | 程式依固定句型產生的文字。 | 代價：報告風格不完全一致。 |
| GPT-4 Code Interpreter | 產品功能（OpenAI） | AsianPLoP §8 Known Uses；COMPSAC II-C | ChatGPT 中讓 GPT-4 撰寫並執行 Python 的功能（後來改名 Advanced Data Analysis）。 | 【AsianPLoP】Satyrn 實驗的比較對象（57%）。　【COMPSAC】Satyrn 的對照組。 |
| A Prompt Pattern Catalog（White 等人，PLoP 2023） | 論文 | AsianPLoP §8 Related Patterns | 整理撰寫 ChatGPT prompt 的 pattern 目錄（Vanderbilt 大學團隊）。 | 和 ④ 方向互補：它教你把 prompt 寫好，④ 說有些事不該交給 prompt；§10 再次提到。 |
| No Data 狀態 | 術語 | AsianPLoP §8 別名 | Grafana、Datadog 的警示中，「查詢沒有拿到任何資料點」的獨立狀態，和正常、告警分開。 | ④ 的業界別名與 Known Use。 |
| Grafana | 產品（開源） | AsianPLoP §8 別名 | Grafana Labs 的儀表板與警示平台。 | 它的警示規則有獨立的 No Data 狀態（grafananodata）。 |
| Unknown condition（Kubernetes） | 術語 | AsianPLoP §8 別名 | Kubernetes 物件的 condition 狀態有 True、False、Unknown 三種，Unknown 代表無法判斷（例如節點失聯）。 | ④ 的別名：連 Kubernetes 也用三值。 |
| 三值邏輯（three-valued logic） | 術語 | AsianPLoP §8 別名 | 真、假之外多一個「未知」的邏輯。 | ④ 的理論基礎。 |
| SQL 的 NULL | 術語 | AsianPLoP §8 別名 | SQL 中表示「沒有值、未知」的特殊值，比較運算的結果是 UNKNOWN。 | 三值邏輯最常見的實例。 |
| runtime verification（執行期驗證） | 術語 | AsianPLoP §8 別名 | 執行時檢查系統的執行軌跡是否符合規格（例如時序邏輯公式）。 | inconclusive verdict 的出處領域。 |
| inconclusive verdict（無定論判定） | 術語 | AsianPLoP §8 別名 | 只看到部分執行軌跡時，既不能判定成立也不能判定違反的結果。 | 「未量測」在形式驗證中的對應概念。 |
| 執行軌跡（trace） | 術語 | AsianPLoP §8 Known Uses | 程式執行過程的事件序列。 | Bauer 論文的分析對象。 |
| deployed: false | 術語（欄位例子） | AsianPLoP §8 Problem | 布林欄位「是否已部署」為 false。 | 說明二值欄位會把「沒查」誤表達成「查了，沒部署」。 |
| 不中斷的設計（失敗不中斷） | 術語 | AsianPLoP §8 Context | 某一步失敗時，工具仍繼續產出其餘結果（類似 graceful degradation）。 | Force 之一：它會把失敗藏起來。 |
| 佔位值（placeholder） | 術語 | AsianPLoP §8 Solution 4 | 用 0、0/N、0% 暫代沒有的值。 | ④ 明確禁止，尤其不能交給 LLM。 |
| 可為 null 的布林值（nullable boolean） | 術語 | AsianPLoP §8 實作要點；COMPSAC I-B | 允許 true、false、null 三種值的布林型別（例如 Java 的 `Boolean`）。 | 【AsianPLoP】三值最簡單的實作，重點是預設必須是未知。　【COMPSAC】部署狀態用可為 null 的布林值表示，避免把「沒量」當成「零」。 |
| NOT MEASURED／未量測 | 術語（DepWeaver 輸出） | AsianPLoP §8 例子；COMPSAC 摘要、III-E | 交給 LLM 或顯示給讀者的明確字串，代替 0%。 | 【AsianPLoP】④ 例子裡交給 LLM 的字串。　【COMPSAC】見 III-E〈未量測不是零〉。 |
| Bauer 等人 2011（bauer2011） | 論文 | AsianPLoP §8 Known Uses | ACM TOSEM 論文，為 LTL（線性時序邏輯）與 TLTL（時間化 LTL）定義執行期驗證，判定結果有 true、false、inconclusive 三種。 | ④ 的學界 Known Use。 |
| LTL／TLTL | 術語 | AsianPLoP 參考文獻 bauer2011 | 線性時序邏輯（描述「最終會」「一直都」這類性質）及其加上時間限制的版本。 | 認得 Bauer 論文題目用。 |
| Bruns 與 Godefroid 1999（bruns1999） | 論文 | AsianPLoP §8 Known Uses | CAV 1999 論文，對只知道一部分的狀態空間，用三值時序邏輯做模型檢查。 | ④ 的學界 Known Use。 |
| 模型檢查（model checking）／狀態空間 | 術語 | AsianPLoP §8 Known Uses | 窮舉系統所有可能狀態（狀態空間），自動驗證是否滿足規格。 | Bruns 論文的背景。 |
| Evaluate as zero／Show NO DATA（Datadog monitors） | 術語 | AsianPLoP §8 Known Uses | Datadog 監控設定中處理缺資料的選項之一：把空結果補成 0，或顯示 NO DATA 等。 | 業界證據：缺資料怎麼處理是一個要明確設定的選項，不是默默補 0。 |
| 警示規則（alert rule） | 術語 | AsianPLoP §8 Known Uses | 監控系統中「條件成立就通知」的規則。 | Grafana No Data 狀態所在的位置。 |
| 自然語言問題→確定性查詢 | 術語（後續工作） | AsianPLoP §8 Resulting Context | 把讀者的提問轉成對模型的固定查詢再執行。 | 本 language 的終點留下的下一個問題，對應 DepWeaver 的報告問答功能。 |

## 9 篩選紀錄

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 強／中／弱（證據等級） | 本文分級 | AsianPLoP §9 | 強是有微服務或服務圖領域的直接證據；中是來自相鄰領域、一般軟體工程或非正式來源；弱是證據零星，或借自其他領域。 | 誠實標出每個 pattern 的證據弱在哪裡。 |
| 查證日期 | 術語 | AsianPLoP §9 | 檢查業界文件內容的日期（2026-10-13，和參考文獻的 Retrieved 日期一致）。 | 業界文件會變動，所以要標日期。 |
| 覆蓋率的分母只算量測得到的項目 | 方法（被拒絕的候選） | AsianPLoP 表 9 最後一列 | 計算覆蓋率時，把根本量測不到的項目（例如未部署、控制平面的元件）排除在分母之外。 | 沒找到相依分析領域的論文或業界服務圖工具，所以判定是工具的設計選擇，不是 pattern（論文裡不點名 DepWeaver）。 |
| 程式碼覆蓋率（code coverage） | 術語 | AsianPLoP 表 9 | 測試執行到的程式碼比例；這類工具會列出被排除的程式碼行。 | 被拒絕候選在其他領域的類比。 |
| 部落格、開源專案（作為證據） | 術語 | AsianPLoP 表 9 | 非官方文件形式的業界證據。 | ④ 的「事實由程式算」這一面業界證據只有這兩種，所以評為「中」。 |

## 10 相關研究

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| EuroPLoP | 會議 | AsianPLoP §10 | PLoP 系列的歐洲場次（在德國 Kloster Irsee 舉行）。 | 作者查過它的論文集，沒有找到架構重建主題的 pattern 論文。 |
| Service Mesh Patterns（EuroPLoP 2022） | 論文 | AsianPLoP §10 | Maia 與 Correia 在 EuroPLoP 2022 發表的 pattern 論文（DOI 10.1145/3551902.3551962），整理服務網格處理安全、韌性、可觀測性與路由等跨服務需求的做法。 | 主題最接近的 pattern 論文之一（執行期觀測的資料來源）。 |
| Patterns for Log Dissemination in Cloud-Native Environments（AsianPLoP 2025） | 論文 | AsianPLoP §10 | Evan Ho、Yi Hsiu Chiu、Chun Feng Liao 在 AsianPLoP 2025（Software and Application 工作坊）發表的 pattern 論文，主題是雲原生環境中的日誌傳遞。 | 相鄰主題的 pattern 論文。 |
| Patterns for AI-Assisted Document Maintenance and Traceability（AsianPLoP 2026） | 論文 | AsianPLoP §10 | AsianPLoP 2026 第 1 組的工作坊論文（Dong-Yi Su 等人），主題是用 AI 協助維護文件並保持可追溯性。 | 本文認為它接近 ④。 |
| ChatGPT／prompt engineering | 產品／術語 | AsianPLoP §10（Prompt Pattern Catalog 題名） | OpenAI 的對話式 LLM 產品；prompt engineering 是設計 prompt 的技術。 | 認得題名用。 |

## 11 討論與限制

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 適用範圍 | 術語 | AsianPLoP §11；COMPSAC V-A | 系統由多個獨立部署的元件組成、相依圖要從多個來源拼出、有人會拿圖做決策。 | 【AsianPLoP】界定 language 的適用條件；單一來源的工具不需要 ①②。　【COMPSAC】只有單一來源（例如純追蹤）時，分級沒有作用。 |
| 效度威脅（threats to validity） | 術語 | AsianPLoP §11；COMPSAC V-D | 研究結論可能不成立的原因，是實證軟體工程論文的標準段落。 | 【AsianPLoP】本文列了三點：pattern 的銜接順序是歸納的、沒有實證驗證（10/19 起不能寫「在 DepWeaver 上走過一遍」）、官方文件不等於產品實際行為（④ 的 Dynatrace CoPilot 證據只來自官方部落格）、LLM 的行為會改變。　【COMPSAC】分成以下四類。 |

## 12 結論／致謝

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| Shepherd／shepherding | 術語（PLoP 慣例） | AsianPLoP 致謝 | 會前由一位資深 pattern 作者（shepherd）和作者來回修改幾輪，這個過程叫 shepherding。 | 致謝中的〔姓名〕要在 shepherding 結束後填入。 |
| Writers' Workshop | 術語（PLoP 慣例） | AsianPLoP 致謝 | PLoP 會議的論文討論形式：作者在旁靜聽，小組成員依序摘要、說優點、提建議。 | 致謝中的〔組別〕〔姓名〕會後再填。 |

---

# 第二部分：COMPSAC 才有的名詞


## 摘要（含關鍵字）

本節也用到（見第一部分）：微服務、相依圖、部署排序、變更影響分析、測試規劃、執行期觀測、靜態分析、動態分派、專案文件、覆蓋率、LLM、DepWeaver、ChatOps、服務網格、融合、出處、「未量測」、MicroDepGraph、架構重建、human-in-the-loop

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 文法（grammar，指 tree-sitter 的語言文法） | 術語 | COMPSAC 摘要 | 剖析器理解某種語言語法所需的定義。 | 沒有對應文法的語言（例如本文中的 Node、PHP）無法做程式碼層的抽取。 |
| tree-sitter | 工具 | COMPSAC 摘要 | 開源的增量式剖析器產生器，能把多種語言的原始碼解析成語法樹。 | 靜態分析的核心（細節見 III-B）。 |
| 三級信心等級（僅推測 < 有使用證據 < 量測到） | 方法 | COMPSAC 摘要 | 依證據強度把邊分成三級（定義見表 I）。 | 貢獻 1。決定線型（Graphviz 用點線、虛線、實線；Mermaid 沒有點線，僅推測改用虛線加「?」）以及這條邊是否計入覆蓋率。 |
| 事實段落（相依關係清單） | 方法 | COMPSAC 摘要 | 報告中列出相依關係的兩節（服務間同步相依、基礎設施與資料層相依）由程式產生；其餘章節（收集狀態、元件、非同步通訊、未解項目、信心摘要）才由 LLM 撰寫。 | 貢獻 2（III-E）。注意：不是「LLM 只寫詮釋」，LLM 寫的章節不少，只是相依關係清單不讓它寫。 |
| 操作者（operator） | 術語 | COMPSAC 摘要 | 在 Discord 上使用工具的人。 | 自動手段用盡後，由操作者回答缺少的值與名稱。 |
| 對話式缺口補齊（conversational gap-filling） | 方法 | COMPSAC 摘要 | 在對話中向人要缺少的資訊。 | 貢獻 3（III-F）。 |
| 14 個開源系統 | 系統 | COMPSAC 摘要 | 表 IV 的受測系統合計（7 個 RQ1 系統、MicroDepGraph 中的其他系統、train-ticket 等）。 | 評估範圍。 |
| 參考邊集合（reference edge set） | 術語 | COMPSAC 摘要 | 被當成「正確答案」的邊清單。 | 本文用兩份：第三方的 MicroDepGraph，以及作者整理、逐條可複驗的一份。 |
| 吻合率（agreement） | 指標 | COMPSAC 摘要 | 參考邊集合裡有多少條被工具畫出（形式上類似 recall）。 | 對 MicroDepGraph 使用。因為兩邊對「相依」的定義不同，所以不算 precision。結果 0.96（91/95）。 |
| precision（精確率） | 指標 | COMPSAC 摘要 | 工具畫出的邊中，正確的比例。 | 用作者參考邊集合評估，7 個系統中 6 個為 1.00。 |
| Bank of Anthos | 系統 | COMPSAC 摘要 | Google 的銀行範例應用，以 Java 與 Python 撰寫，使用 PostgreSQL（accounts-db、ledger-db）。 | 唯一做完整真實部署與執行期驗證的系統，也是 RQ1 到 RQ4 的主案例。 |
| 業務呼叫覆蓋率／資料層覆蓋率 | 指標 | COMPSAC 摘要 | 前者計算服務對服務的 HTTP／gRPC 呼叫，後者計算服務對資料庫的連線，兩者分開算。 | Bank of Anthos 的結果是 7/7 與 5/5。 |

## I-A 動機

本節也用到（見第一部分）：訊息佇列、repo、分散式追蹤、部署描述檔、「宣告了卻沒用到」的設定、LLM agent、F1、almaruf2022、bushong2022、code2dfd2023、rahman2019、liljas2026、schneider2025

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| HTTP | 術語 | COMPSAC I-A | 最常見的網頁與 API 通訊協定。 | 業務呼叫的主要形式，Istio 會產生它的請求指標。 |
| gRPC | 術語 | COMPSAC I-A | Google 開源的 RPC 框架，建在 HTTP/2 上。 | 和 HTTP 一起算在業務呼叫裡（Online Boutique 大量使用）。 |
| 共用資料庫 | 術語 | COMPSAC I-A | 多個服務連到同一個資料庫。 | 資料層的邊。 |
| GPT-4o | 工具 | COMPSAC I-A | OpenAI 在 2024 年推出的多模態模型。 | liljas2026 受測的兩個模型中較好的一個，平均 F1 約 0.6（0.604）。 |
| recall（召回率） | 指標 | COMPSAC I-A | 正確答案中有多少被找到。 | 引用 sui2020，也用在表 VI。 |
| 靜態呼叫圖（static call graph） | 術語 | COMPSAC I-A | 由靜態分析得出的「函式呼叫哪個函式」圖。 | sui2020 的研究對象。 |
| tsubouchi2020（Tsubouchi 等，2020，COMPSAC）／Transtracer | 論文／工具 | COMPSAC I-A | Transtracer 從 socket 層追蹤行程之間的網路相依關係。 | 動態方法的代表，II-A 會再提到。 |
| sui2020（Sui 等，2020，ICSE） | 論文 | COMPSAC I-A | 實證研究靜態呼叫圖相對於實際執行的 recall，中位數約 0.88。 | 量化靜態來源的不足。 |
| 三個新問題：等級被抹平、產出物互相矛盾、只有人知道答案 | 方法（問題陳述） | COMPSAC I-A | 融合多個來源後才會出現的三個問題。 | 依序對應貢獻 1、2、3。 |

## I-B 方法概要與貢獻

本節也用到（見第一部分）：以、三值、模糊比對、候選

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 收集、融合、產出、補齊（四階段） | 方法 | COMPSAC I-B | DepWeaver 流程的四個階段。 | 全文的結構。 |
| 「能由程式從證據算出來的，就不交給 LLM」 | 方法（原則） | COMPSAC I-B | 設計原則：確定性計算優先，LLM 只做程式做不到的事。 | 貫穿 III-D、III-E、III-F。 |
| Discord | 工具 | COMPSAC I-B | 聊天平台。 | DepWeaver 的介面。 |
| 斜線指令（slash command） | 術語 | COMPSAC I-B | Discord 輸入「/」開頭的指令，可帶參數。實作上是 `/get dependency analysis`。 | 觸發分析，參數包括 repo、namespace、入口 URL 等（namespace 填 none 就走無叢集模式）。 |
| 產生流量（traffic generation） | 方法 | COMPSAC I-B | 工具自己送請求去觸發呼叫，讓執行期觀測看得到。 | 提高覆蓋率的手段（III-F）。 |
| 量測指標（metrics） | 術語 | COMPSAC I-B | Prometheus 裡的計數，例如請求數、連線數。 | 執行期證據。 |
| 分層相依圖 | 方法 | COMPSAC I-B | 依層次由上往下排列的相依圖（III-D）。 | 最後貼回 Discord 的圖。 |
| 分母（denominator） | 指標 | COMPSAC I-B | 覆蓋率的分母，也就是「應該被量測的邊」有哪些。 | 只計可量測、且等級高於「僅推測」的邊（演算法 2）。 |
| 流量參數 | 術語 | COMPSAC I-B | 送請求時需要的欄位值，例如帳號、密碼、金額。 | 依四層取得（表 III）。 |
| 非作者標註的第三方參考邊集合 | 方法（評估設計） | COMPSAC I-B | 參考答案不是作者自己標的。 | 回應「標準答案是作者自己給的」這類質疑。 |

## I-C 研究問題

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| RQ0（假想敵） | 方法（研究問題，2026-10-20 新增） | COMPSAC I-C | 不用 DepWeaver、直接讓 LLM 畫圖，在「不給事實」與「給事實」兩種條件下各能畫得多正確？它的輸出缺什麼？ | IV-B（新的一節；原 IV-B 到 IV-E 各往後一個字母，本導讀其餘列的 IV-x 編號尚未跟著改）。三組：零檔案 F1 0.70、給檔案 0.90（濾自迴圈 0.93）、給檔案不給名稱 0.77（寬鬆對名 0.96）；DepWeaver 靜態層 0.88。結論：不基於事實畫不出來；基於事實畫得出來但沒出處、不穩定、名稱對不上。資料在 `docs/experiments/2026-10-20/`。 |
| 自迴圈邊（self-loop） | 術語 | COMPSAC IV-B | LLM 輸出的「X → X」邊，顯然是照抄元件清單；給檔案組 45 條錯邊中 32 條。 | 表 V 同時列濾除前後的數字。 |
| 寬鬆對名 | 方法（敏感度分析） | COMPSAC IV-B | LLM 自行取的名稱，允許去掉 `-container` 後綴或唯一的前綴／後綴吻合（`persistence` ↔ `teastore-persistence`）再計分。 | 說明「給檔案不給名稱」組的失分幾乎全是名稱問題。 |
| RQ1（靜態層的泛化） | 方法（研究問題） | COMPSAC I-C | 只用靜態來源，在沒調校過的系統上能重建多少？錯在哪？ | IV-C（原 IV-B）。 |
| RQ2（融合執行期觀測） | 方法（研究問題） | COMPSAC I-C | 加入執行期觀測後，等級與覆蓋率怎麼變？ | IV-C。 |
| RQ3（對話式缺口補齊） | 方法（研究問題） | COMPSAC I-C | 問人之後能補回多少？要問幾題？ | IV-D。 |
| RQ4（產出物的一致性） | 方法（研究問題） | COMPSAC I-C | III-E 的設計規則防止了哪些真的發生過的錯誤？ | IV-E。 |
| 調校（tuning） | 術語 | COMPSAC I-C | 針對特定系統修改規則或參數。 | RQ1 強調「沒有調校過」。不過 V-D 承認規則是在受測系統上歸納出來的。 |

## II-A 微服務架構重建

本節也用到（見第一部分）：docker-compose／Docker Compose、日誌、追蹤、指標、mayer2018、soldani2022、Kubernetes manifest、Kiali、bakhtin2023、grinenko2026

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 資料流圖（DFD） | 術語 | COMPSAC II-A | 描述資料在元件之間怎麼流動的圖。 | Code2DFD 的輸出。 |
| gortney2022（Gortney 等，2022，IEEE Access） | 論文 | COMPSAC II-A | 微服務架構動態視角視覺化的系統性對照研究（systematic mapping study）。 | 動態視覺化相關研究的參考。 |
| socket／行程（process） | 術語 | COMPSAC II-A | 作業系統層的網路端點，以及正在執行的程式。 | Transtracer 在這一層觀察。 |
| 混合方法（hybrid） | 術語 | COMPSAC II-A | 結合靜態與執行期資訊。 | DepWeaver 屬於這類，但多了等級與出處。 |
| granchelli2017（Granchelli 等，2017，ICSAW）／MicroART | 論文／工具 | COMPSAC II-A | 提出 MicroART，結合靜態與執行期資訊重建微服務架構。本文正文只寫引用，沒有點名 MicroART。 | 混合方法的先例。 |
| SARIF（sarif2023，Zhang 等，2023，ESEC/FSE） | 論文／工具 | COMPSAC II-A | 依各來源的相關性與品質，融合相依關係、程式碼文字與目錄結構，用來做架構重建。和 SARIF 靜態分析結果格式只是同名，是不同的東西。 | 微服務以外的融合先例。本文指出它只輸出分群結果，不保留來源等級。 |
| 分群（clustering） | 術語 | COMPSAC II-A | 把程式模組分成幾群當作元件。 | 對比 DepWeaver 保留逐條邊的等級。 |

## II-B 服務相依圖的分析與測試

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| GMAT（ma2018，Ma 等，2018，COMPSAC） | 論文／工具 | COMPSAC II-B | 以服務相依圖分析並測試微服務（Graph-based Microservice Analysis and Testing）。 | 以相依圖為輸入的前作。作者 S.-P. Ma 是指導老師（海洋大學 SOSELab；repo 的套件名 `ntou.soselab` 就是這個實驗室）。 |
| GSMART（ma2019，Ma 等，2019，FGCS） | 論文／工具 | COMPSAC II-B | 在相依圖中加入服務與情境之間的邊，支援回歸測試的案例檢索。 | 同上。 |
| VMAMV（ma2019b，Ma 等，2019，APSEC） | 論文／工具 | COMPSAC II-B | 以版本為單位建構相依圖，在設計期標出設計問題、在執行期偵測異常，並提供監控與視覺化。 | 同上。 |
| 回歸測試（regression testing） | 術語 | COMPSAC II-B | 修改後重跑測試，確認原本正常的部分沒有壞掉。 | GSMART 的用途。本文也用 train-ticket 做回歸檢查。 |
| MicroViSim（lin2025，Lin 等，2025，ASEW） | 論文／工具 | COMPSAC II-B | 針對 Kubernetes 上的微服務，從使用者定義的 YAML 產生相依圖並模擬負載。 | 本文的區隔是：這些工作拿相依圖當輸入，本文處理的是怎麼建出相依圖。 |

## II-C LLM 與架構重建

本節也用到（見第一部分）：Satyrn、GPT-4 Code Interpreter、PAL、直譯器

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| quadri2026（Quadri 等，2026，COMPSAC） | 論文 | COMPSAC II-C | 結合從程式碼建構的知識圖譜與 LLM 推理做架構重建。 | LLM 用於架構重建的相關研究。 |
| 知識圖譜（knowledge graph） | 術語 | COMPSAC II-C | 以實體與關係構成的圖狀知識表示。 | quadri2026 的方法。 |

## II-D Human-in-the-Loop 與 ChatOps

本節也用到（見第一部分）：Reflexion model、Datadog service remapping

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| MSABot（msabot2020，Lin 等，2020，ICSE Workshops） | 論文／工具 | COMPSAC II-D | 協助微服務開發與維運的聊天機器人框架。 | ChatOps 的前作。 |
| MsdoBot（msdobot2022，Huang 等，2022，ICEBE） | 論文／工具 | COMPSAC II-D | 可擴充的微服務開發維運聊天機器人平台。 | 同上。 |
| ChatOps4Msa（wang2023，ICEBE；wang2024，FGCS） | 論文／工具 | COMPSAC II-D | 以低程式碼的服務組合（後續版本加入 LLM）建構微服務 ChatOps。 | DepWeaver 建在它上面，是它的一個 capability（III-G）。 |
| 低程式碼（low-code）／服務組合（service composition） | 術語 | COMPSAC II-D | 用宣告式設定（YAML）把現有功能組合起來，不必寫大量程式。 | ChatOps4Msa 的核心概念。 |
| PipelineBot（lai2025，Lai 等，2025，ICEBE） | 論文／工具 | COMPSAC II-D | 由 LLM 驅動的 ChatOps，自動化部署管線與監控。論文標題沒有出現 PipelineBot 這個名字，正文才這樣稱呼。 | 同一系列的相關工作。 |
| 部署管線（deployment pipeline） | 術語 | COMPSAC II-D | CI/CD 中從建置到部署的自動化步驟。 | PipelineBot 處理的問題，和本文不同。 |

## II-E 呈現不確定性

本節也用到（見第一部分）：guo2015、視覺變數、partial models、AWS X-Ray inferred segment


## III-A 整體流程

本節也用到（見第一部分）：統一模型

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| namespace | 術語 | COMPSAC III-A | Kubernetes 的命名空間，用來分隔資源。 | 指令參數，指定要觀測哪一組部署。填 none 表示沒有叢集。 |
| 入口 URL | 術語 | COMPSAC III-A | 系統對外的網址，例如 frontend 或 gateway。 | 流量從這裡送進去。 |
| 收集管線 | 方法 | COMPSAC III-A | 依序執行各收集步驟的流程。 | 背景執行。 |
| 檢查點（checkpoint，JSON） | 方法 | COMPSAC III-A | 每個階段把結果寫成 JSON 檔。 | 讓收集、問人、產出可以分開，跨越數小時與多次對話（III-F）。 |
| 確定性計算（deterministic） | 術語 | COMPSAC III-A | 同樣的輸入一定得到同樣的結果，不經過 LLM。 | 覆蓋率由確定性計算決定要不要補送流量。 |
| 補送流量 | 方法 | COMPSAC III-A | 針對還沒覆蓋的邊再送一輪請求。 | 缺口補齊的一部分。 |
| Generate report（按鈕） | 方法 | COMPSAC III-A | Discord 上的按鈕。 | 按下後才從檢查點融合出模型並產生所有產出物。 |
| 產出物（artifact） | 術語 | COMPSAC III-A | 圖、覆蓋率、報告、問答存檔等輸出。 | 這裡的 artifact 不是 III-G 的「匿名 artifact」。 |
| 圖 1（c_arch.png） | 圖 | COMPSAC III-A | DepWeaver 的流程圖。 | 一眼看懂收集、檢查點、補齊、產出的關係。 |
| 有叢集模式（程式裡叫 runtime） | 術語 | COMPSAC III-A | 系統已經部署好、可以觀測的情境。 | 收集三個來源加上部署狀態。論文不再用 brownfield 這個詞。 |
| greenfield（無叢集模式） | 術語 | COMPSAC III-A | 系統還沒部署、只有原始碼的情境。 | 只產出靜態來源加文件的草圖，並在每個受影響的產出物上註明「未量測」。 |
| 草圖 | 術語 | COMPSAC III-A | greenfield 模式下的相依圖。 | 沒有任何「量測到」的邊。 |

## III-B 三個來源

本節也用到（見第一部分）：Istio、proxy／sidecar、Prometheus、HTTP client、Feign、annotation、JPA @Entity／Repository

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| istio_requests_total | 術語（指標） | COMPSAC III-B | Istio 的 HTTP／gRPC 請求計數器，帶 source_workload、destination_workload 等標籤。 | 業務呼叫的執行期證據，以接收端回報（reporter="destination"）為準。 |
| reporter（接收端回報） | 術語 | COMPSAC III-B | Istio 指標的標籤，表示是呼叫端（source）還是接收端（destination）的 proxy 回報。 | 網格內的 HTTP 用 destination；TCP 與網格外流量在實作中用 source，因為外部主機沒有 sidecar 可以回報（`dependency.yml`）。 |
| istio_tcp_connections_opened_total | 術語（指標） | COMPSAC III-B | Istio 的 TCP 連線開啟次數計數器。 | 資料庫這類非 HTTP 連線只在 TCP 層看得到，資料庫邊靠它升級成「量測到」。 |
| 外部相依關係（egress，網格外流量） | 術語 | COMPSAC III-B | 目的地在服務網格外的流量，例如 github.com。 | 第三組查詢。實作上以 destination_app="unknown" 篩選。 |
| 查詢檔（.scm） | 術語 | COMPSAC III-B | tree-sitter 的 query 檔，用 S 運算式寫語法樹比對樣式。 | 用來比對 HTTP client、Feign、持久化 annotation 等。加入新語言時，只要有文法就新增 .scm。 |
| RestTemplate | 工具 | COMPSAC III-B | Spring 的同步 HTTP client 類別。 | 同上。 |
| SQLAlchemy | 工具 | COMPSAC III-B | Python 的 ORM／SQL 工具包。 | Python 端的持久化證據。 |
| Django ORM | 工具 | COMPSAC III-B | Django 框架內建的 ORM（models.Model）。 | 同上。 |
| ORM／持久化（persistence） | 術語 | COMPSAC III-B | 把物件對應到資料表、存進資料庫。 | 資料庫邊從「僅推測」升級到「有使用證據」的條件。 |
| LLM 閱讀程式片段 | 方法 | COMPSAC III-B | 沒有文法的語言，改由 LLM 讀程式片段。 | 備援手段。 |
| Spring 的 application.yml | 術語 | COMPSAC III-B | Spring Boot 的設定檔（資料庫 URL、服務位址等）。 | 確定性解析器讀取的來源。piggymetrics 的共用 application.yml 造成誤報。 |
| Spring Cloud Config（設定 repo） | 工具 | COMPSAC III-B | 集中式設定伺服器，設定放在獨立的 git repo，服務啟動時去取。 | 需要另外讀設定 repo，piggymetrics 的資料層藏在這裡。 |
| 反向代理（reverse proxy：nginx proxy_pass、Apache ProxyPass） | 術語 | COMPSAC III-B | 位在前端、把請求轉送給後端服務的伺服器，設定檔中的 proxy_pass／ProxyPass 指令寫明轉送目標。 | 當成入口的邊清單（表 II，來自 ewolff 與 robot-shop）。 |
| 確定性解析器 | 方法 | COMPSAC III-B | 用固定規則讀 YAML、設定檔與代理設定，不經過 LLM。 | 讀取位址與連線設定。 |
| 「檔案：行號」 | 術語 | COMPSAC III-B | 程式碼出處的格式（設定檔的出處有時是「檔案＋設定鍵」）。 | 每條抽取到的關係都附上，第三方可以複驗。 |
| DeepWiki | 工具 | COMPSAC III-B | Cognition 提供的服務，會為 GitHub repo 自動產生 wiki 並可以問答。DepWeaver 透過它的 MCP 端點（mcp.deepwiki.com）呼叫。 | 文件來源。 |
| 五個固定問題 | 方法 | COMPSAC III-B | 服務職責、同步呼叫、非同步呼叫、外部相依關係、入口。 | 向 DeepWiki 提問，再由 LLM 整理成結構化清單。DeepWiki 每次用詞不同，會造成變異（IV-D、V-D）。 |

## III-C 信心等級、出處與合併

本節也用到（見第一部分）：有使用證據、點線、虛線、實線

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 表 I：三級信心等級 | 方法 | COMPSAC III-C | 三級的條件與範例。 | 本文核心定義。 |
| 僅推測（inferred） | 方法 | COMPSAC III-C／表 I | 只有文件敘述而無設定佐證、只有資料庫連線設定而無持久化程式碼、workload 自己環境變數中的位址，或由變數名猜出來的關係。 | 畫成點線，不計入覆蓋率分母。 |
| 量測到（observed） | 方法 | COMPSAC III-C／表 I | 執行期觀測記錄到這條關係發生過。 | 畫成實線。 |
| 等級與出處分開儲存 | 方法 | COMPSAC III-C | 一條邊可以有多筆出處，但只有一個等級。 | 讓等級好比較，出處又完整保留。 |
| workload | 術語 | COMPSAC III-C／表 I | Kubernetes 中實際執行的工作單位，例如 Deployment、StatefulSet。 | 無叢集時拿它的名稱當服務名稱清單（表 II）。 |
| datasource URL | 術語 | COMPSAC 表 I | 資料庫連線字串，例如 jdbc:postgresql://…。 | 只有它、沒有持久化程式碼時算「僅推測」。 |
| ingress／api-gateway（petclinic） | 術語 | COMPSAC 表 I | 外部流量的入口，以及 petclinic 的閘道服務。 | 範例：istio_requests_total 記錄到 95 次。 |
| spring-petclinic（microservices 版） | 系統 | COMPSAC 表 I | Spring 官方寵物診所範例的微服務版（api-gateway、customers、vets、visits、config、discovery 等）。 | 範例系統。RQ1 對齊 MicroDepGraph 用原專案；表 I 與 RQ4 的執行期數據來自作者去除 Eureka 的 k8s 版 fork。 |
| 合併規則（演算法 1，addEdge） | 方法 | COMPSAC III-C | 以（s, t）為鍵：新邊直接加入；已存在時等級取 max，layers、refs 取聯集，observed 取 OR，count 取 max。 | 貢獻 1 的具體形式。 |
| layers（runtime／code／doc） | 術語 | COMPSAC 演算法 1 | 這條邊被哪幾個來源層支撐。 | 出處的分類。 |
| refs | 術語 | COMPSAC 演算法 1 | 出處清單。 | 取聯集。 |
| count | 術語 | COMPSAC 演算法 1 | 量測次數，只有執行期來源帶這個值。 | 圖上標示呼叫或連線次數。 |
| 合併器（merger） | 方法 | COMPSAC III-C | 各來源的合併元件，例如程式碼合併器、文件合併器。 | 在呼叫 addEdge 前先完成名稱對齊。 |
| 「下游只能沿用或降低等級」 | 方法 | COMPSAC III-C | 產出物不能把等級拉高。 | 避免報告誇大證據。 |
| 離散三級 vs. 0 到 1 連續值 | 方法（設計取捨） | COMPSAC III-C | 選離散三級，不必校準參數或加權，又能直接對應線型。 | 代價是無法表達「兩個弱來源互相佐證」。 |
| 校準（calibration） | 術語 | COMPSAC III-C | 讓分數真的對應機率所需的調整。 | 連續值方案需要校準，所以沒有採用。 |
| 資料庫邊的升級條件 | 方法 | COMPSAC III-C | 只有連線設定時是僅推測；加上同一服務的持久化程式碼，升為有使用證據；網格內量測到 TCP 連線，升為量測到。 | 表 VII 的範例就是這個過程。 |
| Spring Boot | 工具 | COMPSAC III-C | Java 的 Spring 快速開發框架，自動組態。 | 受測系統多半用它，DepWeaver 本身也是（III-G）。 |
| SPRING_DATASOURCE_URL／隱式綁定 | 術語 | COMPSAC III-C | Spring Boot 會自動把這個環境變數綁到 spring.datasource.url，程式碼裡沒有任何一行讀它。 | 造成 Bank of Anthos 的資料庫邊原本找不到（表 VII 第一列）。 |
| Deployment | 術語 | COMPSAC III-C | Kubernetes 管理 Pod 副本的資源。 | DepWeaver 讀取它的環境變數設定。 |
| envFrom | 術語 | COMPSAC III-C | 把整個 ConfigMap 或 Secret 的鍵值一次注入成環境變數。 | 用來讀到注入的資料庫位址。 |
| configMapKeyRef | 術語 | COMPSAC III-C | 從 ConfigMap 取單一鍵注入成一個環境變數。 | 同上。 |
| ConfigMap | 術語 | COMPSAC III-C（隨 configMapKeyRef 出現） | Kubernetes 存放設定鍵值的資源。 | 位址的來源。共用 ConfigMap 注入的位址不畫（規則 14 的細節）。 |
| 合併順序 | 方法 | COMPSAC III-C | 先用執行期建立骨架，再依序疊加程式碼邊、文件邊、持久化證據、部署狀態。 | 程式碼邊對不上的進「未對應清單」；文件邊不新增服務節點（對不上的改為詢問），但可以新增資料庫、佇列與外部主機節點。 |
| 未對應清單 | 方法 | COMPSAC III-C | 目標對不上任何節點的程式碼邊。 | 交給 LLM 或交給人（III-D、III-F）。 |

## III-D 正規化、未對應的邊與分層

本節也用到（見第一部分）：框架函式庫被當成服務、單元測試

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 正規化（normalization） | 方法 | COMPSAC III-D | 清掉合併後圖上的三類雜訊。 | 14 條規則。 |
| 多種拼法（模組目錄名、部署名、文件名稱） | 術語 | COMPSAC III-D | 同一服務的不同名字。 | 第二類雜訊。 |
| 控制平面（control plane） | 術語 | COMPSAC III-D | 平台基礎設施，例如服務註冊中心（Eureka）、設定中心（config server）。 | 第三類雜訊。不算業務呼叫，也不計入覆蓋率分母。 |
| 14 條抽取與合併規則 | 方法 | COMPSAC III-D | 不含任何專案名稱的通用規則，每條都有單元測試（框架函式庫與控制平面另由名單處理）。1–10 條來自第一輪 6 個新系統，11–13 條來自第三方對照，第 14 條來自追查 Online Boutique。 | RQ1 的主要改進來源（IV-B）。 |
| 表 II 規則：以 workload 名稱作為服務清單 | 方法 | COMPSAC 表 II | 沒有叢集時，拿 manifest 的 workload 名稱當節點詞彙表。 | 來自 ewolff、TeaStore、Online Boutique。 |
| 表 II 規則：模組目錄名＝workload 名，或以「-部署名」結尾 | 方法 | COMPSAC 表 II | 判定模組目錄與 workload 是同一服務。 | 來自 ewolff、TeaStore。 |
| 佔位符（placeholder，`${KEY:default}`） | 術語／方法 | COMPSAC 表 II | 設定檔中待替換的變數。解析順序是環境變數、屬性、預設值，最後才用變數名本身（標為僅推測）。 | 來自 piggymetrics、robot-shop。 |
| 單獨的主機名變數（DB_HOST） | 方法 | COMPSAC 表 II | 只有主機名、沒有 URL 的變數也算呼叫目標。 | 來自 piggymetrics、TeaStore。 |
| 反向代理設定視為入口的邊清單 | 方法 | COMPSAC 表 II | 見 III-B「反向代理」。 | 來自 ewolff、robot-shop。 |
| Compose 是連線設定；depends_on 的目標也建立節點 | 方法 | COMPSAC 表 II | Compose 描述的是服務之間的佈線，不是某一個服務的設定。depends_on 是 Compose 宣告啟動相依的欄位。 | 來自 TeaStore、robot-shop。原本 depends_on 只建來源節點、不建目標節點。 |
| localhost 永遠不是節點 | 方法 | COMPSAC 表 II | localhost／127.0.0.1 指的是本機，不是另一個服務。 | 來自 ecommerce。也因為這條規則，ecommerce 的控制平面邊被丟棄（IV-B）。 |
| 未被引用的常數不算呼叫 | 方法 | COMPSAC 表 II | 宣告了 URL 常數，但同一模組內沒有任何引用時不畫邊。 | 來自 ecommerce（56 條邊中有 25 條來自常數檔）。 |
| workload 本身環境變數中的服務位址畫成僅推測（規則 14） | 方法 | COMPSAC 表 II | manifest 中寫在該 workload 自己 env 裡的位址，例如 `PRODUCT_CATALOG_SERVICE_ADDR`。 | 讓 Online Boutique 的 recall 從 2/16 提高到 1.00。 |
| 未對應的邊交給 LLM | 方法 | COMPSAC III-D | 只有規則沒涵蓋的少數程式碼邊交給 LLM 選目標。LLM 回傳的節點必須已經在圖上，否則丟棄並記成待詢問的名稱。 | 限制 LLM 不能自己捏造節點。 |
| 分層（layering） | 方法 | COMPSAC III-D | 節點順序：入口層、依最長路徑深度排列的業務服務、資料層、外部主機、沒有任何邊的節點。 | 讓箭頭一律由上往下。 |
| 入口層（gateway） | 術語 | COMPSAC III-D | API 閘道，或沒有被任何服務呼叫的服務。 | 放在最上層。 |
| 最長路徑（longest path）深度 | 方法 | COMPSAC III-D | 從入口到該節點的最長呼叫鏈長度。 | 不用最短路徑，是為了讓「既被入口直接呼叫、又在鏈末端被呼叫」的服務排在下方，避免箭頭往回指。 |
| Tarjan 強連通元件（SCC）演算法 | 方法 | COMPSAC III-D | Tarjan 在 1972 年提出的線性時間演算法，找出圖中彼此可以互相到達的節點群。 | 把每個環收縮成單一節點後再分層，同一個環的成員就會在同一層。 |
| 收縮（condensation） | 術語 | COMPSAC III-D | 把強連通元件縮成一個節點，圖就變成無環。 | 最長路徑必須在無環圖上才有定義。 |
| train-ticket | 系統 | COMPSAC III-D | 復旦大學 FudanSELab 的火車訂票系統，有數十個微服務，常用作微服務研究的基準系統。 | 分層驗證：51 條邊全部由上往下。也拿來做規則修改後的回歸檢查。 |

## III-E 讓產出物彼此一致

本節也用到（見第一部分）：一個模型、多個視圖、衍生計算、錨點、未量測不是零、not measured／NOT MEASURED

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| Mermaid | 工具 | COMPSAC III-E | 以文字語法描述、由 JavaScript 繪製的圖表格式（.mmd）。 | 其中一種圖的輸出。 |
| Graphviz | 工具 | COMPSAC III-E | AT&T 發展的開源圖形排版工具（dot），可以輸出 PNG。 | 貼回 Discord 的 PNG 圖。 |
| 序列化（serialization） | 術語 | COMPSAC III-E | 把模型存成檔案（JSON）。 | 數天後追問時讀回同一份模型，不重新查詢叢集。 |
| Discord thread | 術語 | COMPSAC III-E | Discord 訊息底下的討論串。 | 報告發出後在這裡追問細節（報告問答）。 |
| 事實段落由程式產生 | 方法 | COMPSAC III-E | 事實段落（同步相依、基礎設施與資料層相依）由程式產生、措辭固定，以 LLM 報告第 6 節標題為錨點插入。LLM 自己寫的同類段落會被丟棄；其餘章節由 LLM 依各來源原始紀錄撰寫。 | 貢獻 2，防止報告和圖矛盾（RQ4）。 |
| 「連線數」vs.「請求數」 | 術語 | COMPSAC III-E | 資料庫邊量到的是 TCP 連線，不是 HTTP 請求。 | 固定措辭的範例。 |
| 「由操作者解析的名稱」一節 | 方法 | COMPSAC III-E | 列出人回答過的名稱對應，由程式產生，固定附在報告最後。 | 防止 LLM 列出已被 ignore 的名稱（RQ4）。 |
| 只計可量測關係的覆蓋率分母（演算法 2） | 方法／指標 | COMPSAC III-E | 分母只收兩端都已部署、不涉及控制平面、資料層目標不是行程內元件，而且等級高於僅推測的邊。業務與資料層分開算。 | 貢獻 2，防止覆蓋率虛高或虛低。 |
| deployed(s)／兩端都已部署 | 術語 | COMPSAC 演算法 2 | 節點確實在叢集中執行。 | 沒部署的服務收不到流量，不應計分。 |
| 行程內元件（in-process） | 術語 | COMPSAC III-E | 活在服務程式裡面的「資料庫」，例如記憶體快取或嵌入式資料庫。 | 永遠量不到網路連線，所以排除。實例是 Bank of Anthos balancereader 的 Guava 快取（RQ4）。 |
| M（僅推測的邊數）與「計分基礎過小」警告 | 方法 | COMPSAC III-E／演算法 2 | 僅推測的邊數寫在分數旁邊。M 大於計分邊數時加上警告。 | 讓讀者知道分數的基礎有多薄。 |
| 未覆蓋清單（U） | 方法 | COMPSAC III-E | 在分母中但還沒量測到的邊。 | 直接成為下一輪流量的目標。 |

## III-F 對話式缺口補齊：最後才詢問操作者

本節也用到（見第一部分）：loadgenerator、4xx、Postman collection、prompt、HTTP 200 視為被拒絕、函式庫名單、候選排序三訊號、編輯距離、回答格式：候選編號、服務識別字、new、ignore

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 語意正確的輸入 | 術語 | COMPSAC III-F | 例如存在的帳號、正確的密碼。 | 有些呼叫沒有它就觸發不了。 |
| 四層缺口補齊（表 III） | 方法 | COMPSAC III-F | 第 1 層從 repo 擷取範例請求；第 2 層把 4xx 交回 LLM 修正；第 3 層在 Discord 問操作者；第 4 層標記為無法觸發，但仍留在分母裡。 | 貢獻 3，確保問人是最後手段。 |
| 綁定（binding）／標記為等待 | 方法 | COMPSAC III-F | 沒有回答的值不綁定，需要它的請求暫不送出。 | 不讓 LLM 自行編值。 |
| 憑證整組詢問 | 方法 | COMPSAC III-F | 帳號與密碼一起問。 | 只問帳號時，產生器會自己編造密碼（實際踩過）。憑證也是「最後才問」的唯一例外：缺少時直接整組詢問（第 V-C 節）。 |
| 值不進入 prompt／遮罩 | 方法 | COMPSAC III-F | LLM 只看到變數名，密碼類欄位顯示時加遮罩。 | 保護憑證。 |
| 伺服器端渲染（SSR） | 術語 | COMPSAC III-F | 由伺服器產生 HTML 頁面，錯誤常放在頁面內容裡，狀態碼仍是 200。 | 上一條規則的成因，例如 Bank of Anthos 的 frontend。 |
| 舊答案指向不存在的節點時略過 | 方法 | COMPSAC III-F | 不建立新節點。 | 避免系統改名後，舊答案憑空長出節點。 |
| 以 repo 為單位保存答案 | 方法 | COMPSAC III-F | 不隨檢查點過期而清除，下次只問新出現的名稱。答錯可以用斜線指令清除。 | IV-D 第三次執行起開始生效。 |
| 按鈕：Apply ServiceEntries、Resolve names、Provide values、Generate report、Pause & supplement（暫停後出現 Resume） | 方法 | COMPSAC III-F | 收集後暫停時貼出的按鈕。 | 分別是套用外部服務登錄、確認名稱、提供流量參數、產生報告、暫停補充資料；Resume 的完整標籤是「Resume (re-check traffic)」。 |
| ServiceEntry | 術語 | COMPSAC III-F | Istio 資源，把網格外的主機登錄進網格。登錄後外部流量的指標才帶得到主機名，否則會落到 PassthroughCluster，看不到主機名。 | DepWeaver 自動產生，操作者按鈕確認後由系統代為套用。 |
| 長時間持有執行緒 | 術語 | COMPSAC III-F | 程式一直占著一個執行緒等人回應。 | 檢查點設計避免了這個問題，所以一次分析可以跨越數小時。 |

## III-G 實作

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| capability | 術語 | COMPSAC III-G | ChatOps4Msa 中的一項功能模組。 | DepWeaver 就是其中一個。 |
| Java 17 | 術語 | COMPSAC III-G | Java 的長期支援版本，也是 Spring Boot 3.0 的最低要求。 | 實作環境。 |
| YAML 管線 | 方法 | COMPSAC III-G | 在 YAML 中宣告步驟（例如 `capability/devops-tool/dependency.yml`）。 | 低程式碼的組合方式。 |
| orchestrator | 術語 | COMPSAC III-G | ChatOps4Msa 既有的編排器，依序呼叫各步驟。 | 執行 DepWeaver 的管線。 |
| Kubernetes MCP server | 工具 | COMPSAC III-G | 透過 MCP（Anthropic 在 2024 年提出的 Model Context Protocol）提供 Kubernetes 操作的伺服器。本專案用的是一個 fork。 | 所有叢集操作都經過它。測試中 1 個失敗案例就是因為連不到它。 |
| OpenAI 相容 API | 術語 | COMPSAC III-G | 遵循 OpenAI Chat Completions 介面格式的 API。 | 可以替換模型供應商。 |
| gpt-4.1-mini | 工具 | COMPSAC III-G | OpenAI 在 2025 年推出的小型模型。 | 實驗中使用的 LLM。 |
| temperature 0 | 術語 | COMPSAC III-G | 取樣溫度設為 0，盡量降低輸出隨機性（但無法保證完全一致）。 | 提高可重現性。 |
| 測試套件（280 案例：269 通過、10 略過、1 失敗） | 指標 | COMPSAC III-G | 自動化測試的數量與結果。 | 誠實交代實作狀態。 |
| 匿名 artifact | 術語 | COMPSAC III-G | 審稿期間以匿名方式提供原始碼、資料與重現指令。這是雙盲審查（double-blind）的慣例，正文沒有出現 double-blind 這個字。 | 可重現性承諾。和 III-A 的「產出物」不同。 |

## IV-A 受測系統

本節也用到（見第一部分）：Online Boutique

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| Jakarta EE | 術語 | COMPSAC IV-A | 前身是 Java EE 的企業 Java 標準（JAX-RS、JPA 等），不是 Spring。 | TeaStore 使用，用來測試非 Spring 的情況。 |
| 自行實作的 registry 與 enum 動態分派 | 術語 | COMPSAC IV-A | 服務自己做服務註冊表，用 enum 常數選呼叫目標。 | TeaStore 的特性，也是靜態層的邊界。 |
| Helm 範本 | 工具 | COMPSAC IV-A | Kubernetes 套件管理工具 Helm 的模板化 manifest。 | robot-shop 使用。 |
| ewolff/microservice-kubernetes | 系統 | COMPSAC IV-A | Eberhard Wolff 的示範專案，有 order、customer、catalog 三個服務，前面由 Apache httpd 反向代理。 | RQ1。反向代理規則來源之一。 |
| ecommerce-microservice | 系統 | COMPSAC IV-A | Spring Cloud 電商範例（GitHub：SelimHorri/ecommerce-microservice-backend-app），含設定中心與服務註冊中心。 | RQ1。recall 0.53，遺漏的都是控制平面邊。 |
| piggymetrics | 系統 | COMPSAC IV-A | sqshq 的 Spring Cloud 個人理財範例，有 account、statistics、notification、auth、gateway、config、registry，使用 MongoDB。 | RQ1。precision 0.83（7 條錯邊）。 |
| TeaStore | 系統 | COMPSAC IV-A | 德國 Würzburg 大學 Descartes Research 的網路商店基準應用（2018），Java／Jakarta EE，有 registry、webui、auth、persistence、image、recommender。 | RQ1。recall 0.46，受限於 enum 分派。 |
| robot-shop | 系統 | COMPSAC IV-A | Instana（現屬 IBM）的 Stan's Robot Shop，多語言，前端是 nginx。 | RQ1。有 3 條遺漏（Node／PHP、getenv 拼接）。 |
| Go、C#、Node、PHP | 術語 | COMPSAC IV-A | 程式語言。 | 本文的 tree-sitter 只支援 Java 與 Python，這些語言靠部署描述檔或 LLM。 |
| 表 IV | 表 | COMPSAC IV-A | 受測系統一覽，列出語言、部署描述檔與用於哪個 RQ。 | 全部評估的地圖。 |

## IV-B RQ1：靜態層的泛化

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| checkout／commit | 術語 | COMPSAC IV-B | Git 中切換到某個版本，以及一次版本提交。 | 對齊資料集相依圖檔提交日（2019-08-19）前的最後一個 commit，比較才公平（10/20 更正：2021-02-26 只是 README 的最後修改日）。 |
| 只執行靜態層 | 方法（實驗設定） | COMPSAC IV-B | 不接叢集、不接文件、不呼叫 LLM。 | 隔離靜態層的能力。 |
| microservices-book | 系統 | COMPSAC 表 V | MicroDepGraph 收錄的 Java 系統，ewolff 為《Microservices》一書寫的範例。 | 5/5。 |
| tap-and-eat | 系統 | COMPSAC 表 V | MicroDepGraph 收錄的 Java 系統（GitHub：jferrater/Tap-And-Eat-MicroServices，2017 年左右的專案）。 | 4/4。用的是 Compose v1 格式，逼出規則 12。 |
| spring-cloud-netflix | 系統 | COMPSAC 表 V | MicroDepGraph 收錄的 Spring Cloud Netflix 範例系統（GitHub：yidongnan/spring-cloud-netflix-example）。 | 26/26。 |
| spring-cloud-microservice(-examples) | 系統 | COMPSAC 表 V | MicroDepGraph 收錄的 Spring Cloud 範例（GitHub：zpng/spring-cloud-microservice-examples，2017 年左右），含 hystrix、discovery、gateway。 | 靜態 24/26，問人後 26/26（RQ3）。 |
| LakesideMutual | 系統 | COMPSAC 表 V | 虛構保險公司的範例應用，展示領域驅動設計與 Microservice API Patterns（Kapferer、Zimmermann 等，Spring Boot）。 | 1.00。它的 SOAP／XML namespace 逼出規則 13：XML namespace 不是位址。 |
| 表 VI（原表 V） | 表 | COMPSAC IV-C | 對 MicroDepGraph 的吻合率，合計 93/95＝0.98（10/20 以 2019-08-19 重新對齊後；舊值 91/95）。 | RQ1 的主要結果之一。 |
| hystrix-dashboard／Hystrix | 工具 | COMPSAC IV-B | Netflix 的熔斷（circuit breaker）函式庫，以及它的監控儀表板。 | 4 條遺漏中，2 條是 repo 演進後已無此模組；2 條是 hystrix 命中函式庫名單被刪，在 RQ3 補回。 |
| 35 條資料集沒有的邊 | 指標 | COMPSAC IV-B | 外部主機、程式碼層 Feign 呼叫、反向代理路由。 | 第三方來源無法判定對錯，所以只列出、不計分。 |
| 作者參考邊集合（逐條可複驗） | 方法 | COMPSAC IV-B | 每條邊附檔案路徑，並附行號或設定鍵，由自動測試檢查 113 筆出處都存在且內容相符。 | 第二份參考答案，表 VI 用它。 |
| 第二位標註者（LLM） | 方法 | COMPSAC IV-B | 由一個沒看過參考答案與工具輸出的 LLM 獨立標註。 | 逐條判定 40 條「只有它標的」邊，沒有一條是作者漏標（17 條命名差異、5 條部署變體、18 條標註者錯誤）。限制見 V-D。 |
| 命名差異／部署變體 | 術語 | COMPSAC IV-B | 同一服務拼法不同；同一系統的不同部署組態（例如 HPA 版本）。 | 解釋標註差異的分類。 |
| 表 VI（P、R） | 表 | COMPSAC IV-B | 7 個系統對作者參考邊集合的 precision 與 recall。 | 6/7 的 P 為 1.00。 |
| 設定中心、服務註冊中心 | 術語 | COMPSAC IV-B | Spring Cloud Config Server，以及 Eureka 這類服務發現。 | 屬於控制平面。ecommerce 遺漏的 18 條都是這類。 |
| OAuth | 術語 | COMPSAC IV-B | 授權協定（auth server）。 | piggymetrics 共用設定中的 OAuth 設定造成錯邊。 |
| RabbitMQ | 工具 | COMPSAC IV-B | 開源訊息佇列代理。 | 同上。 |
| config client | 術語 | COMPSAC IV-B | 向 Spring Cloud Config 取設定的服務。 | 共用的 application.yml 被套到每一個 config client 上，造成 7 條錯邊。 |
| 自動降級 | 方法（未來工作） | COMPSAC IV-B | 執行期一直量不到時，自動把邊的等級調低。 | 目前沒有實作；piggymetrics 7 條錯邊中只有指向 auth-service 的 3 條會進未覆蓋清單，指向 rabbitmq 的 4 條是非同步邊、不在覆蓋率範圍內（V-B 再提）。 |
| Service.PERSISTENCE、loadBalanceRESTOperation、multicastRESTOperation | 術語 | COMPSAC IV-B | TeaStore 用 enum 常數加自家方法呼叫其他服務。 | 靜態層找不到主機字串，所以沒畫出 7 條邊，但也沒有畫錯。 |
| getenv 預設值加字串格式化 | 術語 | COMPSAC IV-B | 主機名在執行時由環境變數與字串拼接組成。 | robot-shop 遺漏 1 條邊的原因。 |
| 第一輪（修改前） | 方法 | COMPSAC IV-B | 6 個新系統第一次跑的結果，全部有問題。 | 規則 1–10 的來源。 |
| DNS 後綴 | 術語 | COMPSAC IV-B | 像 `.svc.cluster.local` 這種網域尾巴。 | TeaStore 的模組名用「.」分隔，被當成 DNS 後綴切掉，整張圖只剩 3 個節點。 |
| Feign contextId／偽節點 | 術語 | COMPSAC IV-B | contextId 只是 bean 的識別名，不是服務名，被誤當成節點。 | ecommerce 的雜訊來源。 |
| 規則 11–13（第三方對照歸納） | 方法 | COMPSAC IV-B | 11：Compose 的 services 也是部署詞彙表（有 manifest 時以 manifest 為準）。12：支援 Compose v1 格式與 links。13：XML namespace 不是位址。 | 新增後，既有系統的結果逐條不變。 |
| 規則 14 | 方法 | COMPSAC IV-B | workload 自己環境變數中的服務位址畫成僅推測。 | Online Boutique 的 recall 從約 0.13 提高到 1.00，並修正「損失來自語言沒有文法」的原本歸因。 |
| 三個明確的邊界 | 方法（結論） | COMPSAC IV-B | 不支援文法的語言、registry 與 enum 的動態分派、跨行拼接的主機名。 | 需要執行期觀測補足。 |

## IV-C RQ2：融合執行期觀測

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| k3s | 工具 | COMPSAC IV-C | Rancher（現屬 SUSE）推出的輕量 Kubernetes 發行版。 | 實驗叢集。 |
| PostgreSQL | 工具 | COMPSAC IV-C | 開源關聯式資料庫。 | Bank of Anthos 的 accounts-db 與 ledger-db。 |
| 圖 3（c_boa.png） | 圖 | COMPSAC IV-C | Bank of Anthos 的分層相依圖。 | RQ2 的主要成果圖。 |
| postgresql 泛稱節點 | 術語 | COMPSAC 圖 3 | 一個沒有任何邊、來源無法確定的通稱節點。 | 不列入計分。 |
| 不透明的 TCP | 術語 | COMPSAC IV-C | Istio 不解析資料庫協定，只看得到 TCP 連線。 | 所以要另外查 istio_tcp_connections_opened_total。 |
| transactionhistory → ledger-db | 術語 | COMPSAC IV-C／表 VII | Bank of Anthos 的交易紀錄服務連往帳本資料庫的邊。 | 示範同一條邊在三次實驗中的三種狀態：不存在、虛線、實線（5,191 次連線）。 |
| 表 VII | 表 | COMPSAC IV-C | 上述三種狀態與原因。 | 具體說明等級升級。 |
| 問答對照（只給報告、報告加檢索、圖查詢） | 方法（實驗） | COMPSAC IV-C | 用同一組 10 題、同一個模型，比較三種給 LLM 的資訊。 | 結論：問答的正確性受限於圖的正確性，圖的錯誤會原樣傳到回答。 |
| 檢索（retrieval／RAG） | 術語 | COMPSAC IV-C | 先從文件中找出相關段落再給 LLM（實作用 BM25 加 embedding）。 | 對照組之一。 |
| 存檔 | 術語 | COMPSAC IV-C | 序列化的統一模型。 | 問答讀的就是它。 |
| 「與圖一致」vs.「與事實一致」 | 術語 | COMPSAC IV-C | 標準答案由同一張圖的查詢產生，所以量到的是一致性。 | 誠實交代這組實驗的限制。 |

## IV-D RQ3：對話式缺口補齊

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 關閉第 1 層 | 方法（實驗設定） | COMPSAC IV-D | 不讓工具從 repo 擷取範例請求。 | 確認問人真的是最後手段；最後兩條業務邊就是在這次執行中由對話補齊的。 |
| ledgerwriter → balancereader | 術語 | COMPSAC IV-D | Bank of Anthos 寫入轉帳時會查餘額的邊。 | 需要成功的轉帳才會觸發，也就需要密碼與他人帳號。 |
| 收款帳號／餘額檢查 | 術語 | COMPSAC IV-D | 第一次給的是匯款人自己的帳號，不會觸發餘額檢查。工具換一個變數名重問，換成他人帳號後才到 7/7。 | 展示多輪補齊的過程：5/7、6/7、7/7。 |
| hystrix → discovery、hystrix → gateway | 術語 | COMPSAC IV-D | spring-cloud-microservice 遺漏的 2 條邊。 | 改成「命中函式庫名單但有程式碼或部署層的邊時提問」後，問 3 題，吻合率從 24/26 提高到 26/26。 |
| 五次實際執行 | 方法（實驗） | COMPSAC IV-D | 在真實 Discord 上跑了五次。 | 每次都修到一個介面或規則問題：加入完整詞彙表、改問「是否為同一個」、把 repo 名當元件的直接丟棄。 |
| 詞彙表 | 術語 | COMPSAC IV-D | 圖上所有可選的節點名稱。 | 表單原本只接受候選，後來加入完整詞彙表。 |
| 限制（操作者是作者本人） | 方法（效度） | COMPSAC IV-D | 作者熟悉系統，也看過參考答案。 | 只能說明機制可以運作，不能說明一般人能否答對。 |

## IV-E RQ4：設計規則防止的錯誤

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 表 VIII | 表 | COMPSAC IV-E | 八個實際發生過的錯誤，以及防止它們的規則。 | RQ4 的全部證據。 |
| 稀釋成 45% | 指標 | COMPSAC 表 VIII | petclinic 的覆蓋率被控制平面、偽節點、別名拉低。只計業務邊後為 4/4。 | 分母規則的實例。 |
| 別名（alias） | 術語 | COMPSAC 表 VIII | 同一服務的另一個名字，例如 api-gateway-controller。 | 雜訊來源。 |
| 7/7 變成 7/10 | 指標 | COMPSAC 表 VIII | 文件多提了不存在的邊，分母被灌大。 | 信心等級加分母規則的實例。 |
| 行程內快取，資料層卡在 5/6 | 指標 | COMPSAC 表 VIII | 快取永遠量不到，訊息還暗示操作者重啟 pod。 | 行程內元件排除規則的實例。 |
| pod | 術語 | COMPSAC 表 VIII | Kubernetes 最小的部署單位（一個或多個容器）。 | 錯誤訊息提到它。 |
| 建置期函式庫被列為相依 | 術語 | COMPSAC 表 VIII | LLM 把編譯時才用的函式庫寫成執行期相依關係。 | 證明靠改 prompt 會出現另一種錯，最後改由程式產生。 |
| 「25 Services observed、50 Pods Running」 | 術語 | COMPSAC 表 VIII | 純靜態模式下，LLM 編造出叢集數據。 | 未量測不是零的實例。 |
| 容器映像檔中沒有 git | 術語 | COMPSAC 表 VIII | 映像檔缺工具，程式碼抽取從未成功，報告卻看似正常。 | 「失敗要留下紀錄」的實例。 |
| 「證據到產出物的轉換過程」 | 方法（結論） | COMPSAC IV-E | 多數錯誤不是量測錯，而是在呈現給讀者的過程中被放大或掩蓋。 | 支持貢獻 2。 |

## V 討論

本節也用到（見第一部分）：適用範圍、效度威脅

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 靜態與執行期互補 | 方法（結論） | COMPSAC V-B | 靜態層 precision 高但處理不了動態分派；執行期精確但只看得到被觸發的路徑。 | TeaStore 0/7 與 piggymetrics 7 條錯邊是兩個方向的例子。 |
| 為什麼最後才詢問 | 方法 | COMPSAC V-C | 問人會打斷操作者，而且操作者可能答錯，所以只問能用一個值或一個選項回答的具體問題。 | 為貢獻 3 的設計理由辯護。 |
| 建構效度（construct validity） | 術語 | COMPSAC V-D | 量到的東西是不是真的是想量的東西。 | 參考答案是作者整理的；第二位標註者是 LLM 而非人類；第三方資料集對相依的定義不同。 |
| 內部效度（internal validity） | 術語 | COMPSAC V-D | 結論是否可能來自其他因素或偏差。 | 規則在受測系統上歸納而來，不是 hold-out；RQ3 的操作者是作者本人。 |
| hold-out | 術語 | COMPSAC V-D | 保留一組完全沒用來調整方法的資料，只拿來測試。 | 本文承認 RQ1 修正後的數字不是 hold-out 結果。 |
| 外部效度（external validity） | 術語 | COMPSAC V-D | 結果能否推廣到其他情境。 | 受測系統以 Java／Spring 為主，含缺口補齊的完整執行期驗證只有 Bank of Anthos 一例（petclinic 也跑過執行期，但沒有缺口補齊）。 |
| LLM 輸出的變異 | 術語 | COMPSAC V-D | 每次執行時 LLM 與 DeepWiki 的輸出不同。 | 事實段落不受影響；LLM 撰寫的章節與文件來源的邊會受影響。 |

## VI 結論與未來工作

| 名稱（原文） | 類別 | 出現位置 | 是什麼 | 在論文中的用途 |
|---|---|---|---|---|
| 未來工作（四項） | 方法 | COMPSAC VI | 1. 請不熟悉系統的人回答名稱問題。2. 在 TeaStore 完成執行期驗證。3. 用小型使用者研究檢驗分級是否改善決策。4. 用相依圖產生部署順序。 | 對應 V-D 的各項限制。 |
| 使用者研究（user study） | 術語 | COMPSAC VI | 找真人做實驗、評估工具。 | 用來檢驗信心分級是否幫助決策。 |
| AI 使用聲明（!ack） | 術語 | COMPSAC 致謝處 | COMPSAC 要求作者揭露使用了哪些 AI 系統、影響哪些章節（細節以 2027 年徵稿規定為準，目前還沒公告）。 | 定稿時要填寫。 |

---

# 第三部分：參考文獻裡的會議、期刊縮寫

| 縮寫 | 全名／說明 | 出現在 |
|---|---|---|
| DOI | 數位物件識別碼，論文的永久連結。 | AsianPLoP |
| SOSE | IEEE International Conference on Service-Oriented System Engineering（Al Maruf、Cerny、Mayer 的論文）。 | 兩篇 |
| ICSA | IEEE International Conference on Software Architecture（Alshuqayran）。 | AsianPLoP |
| ECSA／LNCS | European Conference on Software Architecture；LNCS 是 Springer 的 Lecture Notes in Computer Science 叢書（Bakhtin、Bruns）。 | 兩篇 |
| TOSEM | ACM Transactions on Software Engineering and Methodology（Bauer）。 | AsianPLoP |
| CAV | Computer Aided Verification，形式驗證的頂尖會議（Bruns）。 | AsianPLoP |
| CLOSER | International Conference on Cloud Computing and Services Science（Bushong、Soldani）。 | 兩篇 |
| TSE | IEEE Transactions on Software Engineering（Cornelissen、Murphy）。 | 兩篇 |
| ICSE | International Conference on Software Engineering，軟體工程頂尖會議（Famelis）。 | 兩篇 |
| ICML／PMLR | International Conference on Machine Learning；PMLR 是 Proceedings of Machine Learning Research（PAL）。 | 兩篇 |
| CEUR Workshop Proceedings | 開放取用的工作坊論文集（Grinenko、Rahman）。 | 兩篇 |
| TVCG | IEEE Transactions on Visualization and Computer Graphics（Guo）。 | 兩篇 |
| ESEM／LIPIcs | Empirical Software Engineering and Measurement；LIPIcs 是 Dagstuhl 的開放論文集叢書（Liljas）。 | 兩篇 |
| KDD | ACM SIGKDD 知識發現與資料探勘會議（Sarawagi）。 | AsianPLoP |
| JSS | Journal of Systems and Software（code2DFD）。 | 兩篇 |
| EMSE | Empirical Software Engineering（Schneider 2025）。 | 兩篇 |
| EMNLP | Empirical Methods in Natural Language Processing，NLP 頂尖會議（Satyrn）。 | 兩篇 |
| arXiv | 預印本平台（Rahman 2019 的連結）。 | AsianPLoP |
| CI（configuration item） | ServiceNow 參考文獻題名中的「設定項目」：CMDB（組態管理資料庫）裡記錄的每一個 IT 元件。 | AsianPLoP |
| Retrieved … from | 網頁類參考文獻的存取日期（本文統一是 2026-10-13）。 | AsianPLoP |
| GitHub repository／knowledge base | 業界證據的出處形式：開源專案頁面、廠商知識庫。 | AsianPLoP |
| COMPSAC | IEEE Computer Software and Applications Conference（本文投稿的會議） | COMPSAC |
| ICSAW | IEEE International Conference on Software Architecture Workshops | COMPSAC |
| ESEC/FSE | 歐洲軟體工程會議與 ACM SIGSOFT 軟體工程基礎研討會（聯合舉辦） | COMPSAC |
| FGCS | Future Generation Computer Systems | COMPSAC |
| APSEC | Asia-Pacific Software Engineering Conference | COMPSAC |
| ASEW | Automated Software Engineering Workshops | COMPSAC |
| ICEBE | IEEE International Conference on e-Business Engineering | COMPSAC |
| SSSME | Summer School on Software Maintenance and Evolution（2019，論文集收在 CEUR-WS vol. 2520） | 兩篇 |
| WCH | Workshop on Cyber Hygiene | 兩篇 |

---

# 容易搞混的地方

- **documented 不是「文件提到」。**英文代號 documented 對應的是「有使用證據」，也就是有程式碼或位址設定佐證。只有文件提到、沒有設定佐證的關係反而是 inferred（僅推測）。
- **「事實段落」不等於「LLM 只寫詮釋」。**COMPSAC 的程式只產生報告中列出相依關係的兩節；收集狀態、元件、未解項目這些章節仍由 LLM 撰寫。AsianPLoP ④ 講的是通用做法，DepWeaver 是其中一種實作。
- **兩個「目錄」。**AsianPLoP §4 的 catalog 指「pattern 目錄」（彼此獨立的 pattern 清單）；④ 別名裡的 Software Catalog（原本獨立成篇的「一份目錄、多個視圖」，10/19 起併入 ④）的「目錄」指服務目錄或統一模型。
- **兩個「別名」。**AsianPLoP 每個 pattern 的「別名」欄是這個做法的其他叫法；COMPSAC 的別名（alias）是同一個服務在不同來源裡的不同名字。
- **兩個「佔位」。**AsianPLoP ④ 的佔位值是拿 0、0% 暫代沒有的資料（要避免）；COMPSAC 表 II 的佔位符是設定檔裡的 `${KEY:default}`（要解析）。
- **兩個「降級」。**AsianPLoP ② 的降級是報告只能維持或降低可信程度；COMPSAC 的「自動降級」是執行期一直量不到時把邊的等級調低，目前沒有實作。
- **兩個 artifact。**COMPSAC III-A 的「產出物」（圖、報告等）和 III-G 的「匿名 artifact」（給審稿人的程式碼與資料）是兩回事。
- **Istio 查詢的 reporter。**COMPSAC 正文只寫 istio_requests_total「以接收端回報為準」；TCP 與網格外流量的查詢在實作中用的是 reporter="source"（`src/main/resources/capability/devops-tool/dependency.yml`）。正文沒有寫錯，但被問到時要能說明差別。
- **導讀補上的名稱。**MicroART（granchelli2017）、GSMART（ma2019）、VMAMV（ma2019b）在 COMPSAC 正文裡只有引用，沒有點名；導讀依引用內容補上名稱。
