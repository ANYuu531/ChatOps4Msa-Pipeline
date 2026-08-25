# 週進度會議講稿 · 2026/8/14

> 學生口吻、口語化。`【口頭補充】`= 投影片沒寫、用講的補；`【可能被問】`= 老師可能追問，先準備好答案。

---

## 開場

老師好，這是這禮拜的進度。因為上禮拜時間比較趕、有幾條線只草草帶過，我今天會**先花一點時間把上週那兩條線（去 Eureka、DB 判定通用化）講清楚**，然後接這禮拜新做的——拿兩個**沒看過的專案**（Bank of Anthos、train-ticket）來驗證工具到底泛不泛化。

先講一句整體脈絡：我們這個工具做的是「自動畫出微服務的相依圖」，證據來自兩層——**靜態**（clone 原始碼去解析）跟 **runtime**（部署後用 Istio 觀測真實流量）。下面每一條線基本上都是在讓這兩層「更準」或「更通用」。

---

## P2 · petclinic 去 Eureka（為什麼要做）

這頁在講一個問題：petclinic 用的是 **Eureka**（Netflix 的服務發現），而 Eureka 讓我們工具的「準確度」說不清楚，有三個症狀：

1. **圖看起來不準**——我們抽取是 clone 原始碼，只要 code 裡還宣告 Eureka，圖裡就會永遠多一個 `discovery-server` 的「幽靈節點」，它其實不是業務相依，卻一直在圖上。
2. **覆蓋率假性偏低**——Eureka 的 `lb://` 會把服務名解析成 Pod IP、**繞過** k8s 的 Service VIP，結果 Istio 那邊變成 passthrough、業務端點回 405、流量根本打不進去，覆蓋率就被拉低。
3. **不夠有代表性**——現在的微服務大多用 **k8s 原生服務發現**了，一個綁死 Eureka 的實驗對象，撐不起我們想講的「工具又準又通用」這個論述。

【口頭補充】所以這條的動機很單純：Eureka 同時傷到「準」跟「通用」，這兩個剛好就是我們工具的賣點，所以得處理。

---

## P3 · petclinic 去 Eureka（怎麼做）

做法是 **fork 原始碼、改走 k8s 的 Service DNS**。我寫了一個 `de-eureka.sh` 一鍵重構：把路由從 `lb://svc` 改成直連 `http://svc:port`、移除 `eureka-client` 依賴跟 `@EnableDiscoveryClient`/`@LoadBalanced` 註解、刪掉整個 discovery-server 模組、還有把 FallbackController 的舊 HTTP 客戶端換掉。

兩個「為什麼」：

- **為什麼要 fork code、不能只改部署設定？** 因為我們分析是**以原始碼為主**。如果只用 env override 改部署，code 裡還是宣告 Eureka，圖就還是會長出那個幽靈節點——一定要改到 code 才會乾淨。而 petclinic 的路由是定義在 code repo 的 `application.yml`，所以我只要 fork code repo 就好，config repo 不用動。
- **為什麼用 http 直連？** 比起再去引一套 Spring Cloud Kubernetes，直連最簡單也最穩，可以完全擺脫對任何特定服務發現客戶端的依賴；而且直連 `http://svc` 是走 meshed 的 ClusterIP，Istio 就能完整觀測流量，順便根治剛剛講的 `lb://` 繞過 Service VIP 那個追蹤問題。

---

## P4 · petclinic 去 Eureka（圖乾淨了）

這頁是 before / after 的對照圖。

- **Before（Eureka 版）**：右邊有一個紅框的 `discovery-server` 幽靈節點，業務邊還是 405 的虛線（打不進去）。
- **After（k8s-native fork）**：乾淨了，沒有幽靈節點，業務邊變成實線（Istio 真的觀測到），而且還順便多抓到 `config-server → github.com` 這條外部依賴。

【口頭補充】所以這條不只是「看起來比較乾淨」，是真的把「準」給修回來了——這也是後面所有泛化驗證的基礎。

---

## P5 · 通用化判定 DB 是否真的有使用（為什麼）★重點

這頁在講一個重構。先講背景：我們有一個判定叫「這個服務**是不是真的有在用資料庫**」。原本的寫法是寫死的——`if ("jpa".equals(section)) { … }`，只認 Java 的 `@Entity`。

兩個問題：

- **DB 判定寫死**：只認 Java `@Entity`，跟 Spring/JPA 深度耦合。
- **無法支援多語言**：Python 端一條規則都沒有，完全沒有語言中立性。

所以重構目標就是把它變成**語言中立的抽象介面**：解耦 Spring/JPA、統一多語言規則、而且要高可擴充（未來新語言只要「實作既有介面」，不用改判定核心）。

### 【可能被問】老師可能會問：「語言中立的抽象介面具體怎麼做？是 interface 嗎？為什麼後面可以彈性新增？」

我要老實說：**這裡的「介面」不是 Java 的 `interface` 那個關鍵字**，它是一個**約定（contract）**、是資料層的介面，不是物件導向那種 class interface。具體是這樣做的：

我把系統拆成**兩端**，兩端只透過一組**固定的 section 名稱**溝通：

- **抽取端**：每個語言有自己的 tree-sitter 規則檔（`.scm`）。它負責認「這個語言的 ORM 長什麼樣」，認到就標記成一個叫 `persistence` 的 section（Java 因為歷史因素叫 `jpa`）。
- **判定端**：Java 這邊只有一行 `PERSISTENCE_SECTIONS = { jpa, persistence }`，它**只看這條標記的 section 名在不在這個集合裡**，完全不管它是哪個語言、哪個框架。

所以所謂的「介面」，就是**這組 section 名稱的約定**（加上 `.scm` 裡「capture 命名成 `section.field`」的慣例）。它的作用是把「**怎麼偵測**」（各語言各自負責）跟「**怎麼判定**」（統一一套）**解耦**開來——這其實就是介面該做的事，只是我用「約定好的字串」來當介面，而不是 Java 的 `interface`。

**為什麼後面可以彈性新增？** 因為判定端唯一寫死的東西，就只有「section 名稱的清單」而已；「怎麼認某個 ORM」整個下放到 `.scm`。所以要支援一個新 ORM 或新語言，我只要在對應的 `.scm` 加一條規則、讓它 emit 到 `persistence` section 就好——**判定端一行 Java 都不用改、也不用重新編譯**。這就是投影片說的「只需實作既有介面、零 Java 修改」的真正意思。

---

## P6 · 通用化判定 DB（怎麼做：一條鏈）

這頁是把上一頁「介面」的具體長相攤開來，一條鏈：

- **FLOW（核心流程）**：`.scm` 裡寫 `@persistence.x` → emit 的時候把 `.` 前面那段當成 section 名 → 進到 `EdgeLedger` 的自由 section → 判定端就是 `PERSISTENCE_SECTIONS.contains()` 這一句。
- **METHOD（判定機制）**：把原本 `"jpa".equals` 改成 `PERSISTENCE_SECTIONS = { jpa, persistence }`。各語言的 `.scm` 把 ORM 標記 emit 到 `persistence`，判定端只看 section 名。**為什麼做成介面**——就是為了「零 Java 修改」：新增一個 ORM 只要加一條 `.scm`（例如 Java 的 MyBatis，只要在清單裡加 "Mapper"）；如果當初是用語言特定的 if-else，那每加一個 ORM 都得改 Java、重編一次。
- **CONFIDENCE（三層信心）**：這個沒動。我們把「有沒有用 DB」分三層——① Runtime（動態跑到）② Code 真用（靜態有 ORM 呼叫）③ 只宣告（只有配置）。**為什麼三層不動**：因為 DB 多半不在 mesh 裡面，runtime 對 DB 是**低 recall**（常常看不到），所以不能只靠 runtime。而這套分層邏輯本身跟語言完全解耦，未來泛化到新語言，核心一行都不用改。

【口頭補充】所以第 5 頁講「為什麼要有這個介面」，這頁就是「介面實際長怎樣」——關鍵就是那組 section 名稱的約定。

---

## P7 · 通用化判定 DB（表格 + 本週新增 SQLAlchemy Core）★重點

這頁的表格就是「目前支援哪些 ORM」：Java 的 JPA / Spring Data（`@Entity` `@Repository`）、Python 的 SQLAlchemy 宣告式（`Base` / `__tablename__`）、**本週新增的 SQLAlchemy Core**（`Table()` + `Column()`）、還有 Python Django（`models.Model`）。每一列，其實就是一條 `.scm` 規則。

### 【可能被問】老師可能會問：「ORM 是什麼？未來如果有其他 ORM 呢？」

**ORM = Object-Relational Mapping，物件關聯映射。** 白話講，它是一個函式庫，讓你可以用「程式裡的物件 / class」直接操作資料庫，**不用自己寫 SQL**。你定義一個 class、標記它對應到哪張表，ORM 就幫你把物件存進資料庫、也幫你從資料庫撈出來變回物件。表格裡那些標記（`@Entity`、`__tablename__`、`Table()`、`models.Model`）就是各家 ORM「把 class 綁到資料表」的寫法。

**為什麼要用 ORM 標記來判定「DB 真的有用」？** 因為如果只看有沒有 datasource URL，那只代表「宣告了一個資料庫連線」，不代表真的在用；但只要程式裡有一個 `@Entity` 或一個 mapped model，就代表這個服務**真的在把物件存進 DB**——這才是「真的有用到」的鐵證。

**未來如果有其他 ORM？** 這就接回第 5、6 頁的設計了：因為架構是「**一個 ORM = 一條 `.scm` 規則 → emit 到 `persistence` section**」，所以要支援新的 ORM（例如 Java 的 MyBatis、Go 的 GORM、Node 的 TypeORM / Prisma），我只要**加一條規則**，判定核心一行 Java 都不用動。

**而且本週的 SQLAlchemy Core 就是實際把這個流程跑了一遍當驗證**：因為我下一個測試目標 Bank of Anthos 的 Python 服務用的是 Core（非宣告式），原本的 pattern 抓不到，我就加了一條——而且特別**限制它必須搭配 `Column(...)`**，避免誤判到像 `rich`、`prettytable` 那種也叫 `Table()` 但跟資料庫無關的東西。用 tree-sitter probe 驗證的結果：Core 有抓到、既有的 Django 沒被弄壞、rich 也不會誤抓。**這就實際證明了「零 Java 擴充」是真的能動，不是紙上談兵。**

---

## P8 · 目前產生依賴的流程圖（總流程）

【口頭補充】老師上次說這張太長，我濃縮成 **7 步**，中間「建圖合併」收成一個節點、細項放下一頁。這頁我不只念步驟，想講清楚**整條線的邏輯**——它其實就是「先蒐集三種證據 → 主動讓 Istio 看到相依 → 量測 → 合成出圖」。

一步一步講：

> 每步後面我標了它是**〔程式碼〕**（我們自己寫的確定性邏輯）還是**〔AI〕**（用 LLM）。

1. **靜態抽取** 〔程式碼〕——clone 原始碼，用 tree-sitter 解析出四種東西：服務間的呼叫（http-client / Feign）、DB 使用（就是前面第 5～7 頁那個 ORM 標記）、對外暴露的 endpoint、還有「哪些資料夾是一個服務」（服務根）。這是**純解析、不是 AI**。它完全不碰叢集，回答的是「**程式碼宣告了哪些相依**」。
2. **文件分析** 〔AI〕——問 DeepWiki（文件層）：每個服務的職責、同步/非同步關係、外部依賴、架構模式，再把 code + doc 合成一份 notes。**要讀自然語言，所以這裡用 LLM**。為什麼還要 doc：有些關係程式碼不好抽，最典型是非同步的「誰是 producer」，文件常常補得到。
3. **叢集 / 路由** 〔程式碼〕——用 kubectl 抓叢集現況：哪些服務/Pod 真的部署了、Istio 的 VirtualService / DestinationRule 路由長怎樣。就是下指令 + 解析輸出，確定性的。這步回答「**現在叢集實際長怎樣**」。
4. **驅動流量** 〔情境＝AI · 執行＝程式碼〕——這步是整條線的關鍵。因為 **Istio 是被動的：一條邊只有在請求真的跨過去時才會被記錄**。所以我們用 **LLM 產出**一段「真實使用者旅程」（要懂 API 語意），但**驅動它的執行器是我們自己寫的程式碼**（TrafficRunner），跑完再 settle 等 telemetry 落地。重點是——我們要的是**覆蓋率（有沒有踩到那條邊），不是壓力（load）**。
5. **量測** 〔程式碼〕——導完流量後查 Prometheus 的 `istio_requests_total`，拿到「哪些邊真的被觀測到、各幾次」；另一條查 egress，抓「離開 mesh 的外部呼叫」。就是 PromQL 查詢 + 解析。
6. **建圖合併** 〔程式碼為主〕——把前面所有證據（runtime 觀測 + 程式碼邊 + 文件邊 + 部署狀態）合成一張圖。細節在下一頁，**7 步裡只有 1 步用 AI**。
7. **產出** 〔圖＝程式碼 · 報告文字＝AI〕——依賴圖（Mermaid / PNG）跟覆蓋率是**程式碼**算的；最後那份**文字報告**是 LLM 寫的。還有補充建議（哪些邊還沒踩到）。

**還有一條迭代迴圈**（產出 →「覆蓋率不足」→ 回驅動流量）：如果還有邊沒被踩到，就**只針對那些缺的邊**再導一輪（resume），而不是整條重跑——前面 clone、DeepWiki 那些貴的步驟會從 checkpoint 拿回來、不重做。

【口頭補充 · 串本週】這頁還能帶到 greenfield：**如果沒給 namespace，第 3、4、5 步（叢集 / 驅動流量 / 量測）整段會被跳過**，只剩 1、2、6、7 → 就是純靜態出圖。這就是本週後面 BoA、train-ticket 那條 greenfield 的由來。

---

## P9 · 目前產生依賴的流程圖（建圖合併）

這張是上一頁「建圖合併」那個節點的細項。我想先講一個貫穿全圖的觀念，再走 7 步——**證據有強弱之分，合併是「由強到弱一層層疊」**：runtime 真的觀測到（實線）＞ code/doc 只是宣告（虛線）。合併的順序就是照這個強弱來的。

一步一步：

（這 7 步裡**只有第 4 步用 AI，其他 6 步全是我們自己寫的程式碼**。）

1. **Runtime 圖** 〔程式碼〕——先用 Istio 觀測到的邊當**骨架**，因為這是最強的證據（請求真的跨過去了），畫實線；egress 那條把外部呼叫也折進來。之後所有東西都疊在這個骨架上。
2. **併程式碼邊** 〔程式碼〕——把程式碼抽到的呼叫邊疊上去：如果這條 code 邊 runtime 也觀測到 → 合併（實線，再多一個「code 也證實」的來源）；如果 runtime 沒看到、但 code 有宣告 → **加一條虛線**（宣告但未驗證）。**本週 BoA 的 `env-address`、train-ticket 的 `path→service` 就是在這一步把「host 是變數」的呼叫目標解出來的**，而且這步是**確定性的、沒有 AI**。
3. **併文件邊 · 提升真用 DB** 〔程式碼〕——把 DeepWiki 講到的邊也疊上（**合併邏輯是程式碼**，只是輸入的 notes 來自前面的 AI 那步）；「提升真用 DB」的意思是：如果一個服務有 ORM 標記（第 5～7 頁那個），就把它的 DB 邊從「只宣告」**升級成「真的有用」**。
4. **LLM 補殘餘** 〔AI〕——前面確定性都解不掉的殘餘（host 是變數、又沒有範本可以對）才丟給 LLM 猜。**這是建圖裡唯一用到 AI 的一步**，也就是「**確定性優先、LLM 只補殘餘**」——能用規則解的絕不丟給 LLM，讓圖盡量可重現、不亂猜。
5. **標部署狀態** 〔程式碼〕——用 kubectl 抓到的部署清單，把每個節點標成「有部署 / 沒部署」；沒部署的畫成灰/虛，這樣圖能區分「宣告了、但叢集其實沒在跑」。
6. **正規化** 〔程式碼〕——清掉框架造成的「幽靈節點」（框架的 lib 被誤當成服務）、把別名合併（例如 `api-gateway-controller` → `api-gateway`）、做分組，讓圖乾淨。
7. **輸出** 〔程式碼〕——把這張正規化後的圖交給 emitter 產 Mermaid / DOT→PNG，再算覆蓋率。

【口頭補充 · 串本週】這個「強弱疊層」剛好解釋了本週 BoA、train-ticket 的圖**為什麼全是虛線**：greenfield 沒有第 1 步的 runtime 骨架，所有邊都落在第 2、3 步的 code/doc → 自然全虛線。這也是為什麼那些圖是「誠實」的——它老實告訴你「這些是宣告出來的，還沒被 runtime 驗證過」。

---

## ★（跨頁）AI 用在哪、哪些是我們自己寫的程式碼 —— 老師常問，先備好

【可能被問】老師很可能會問：「這整套是不是就靠 LLM？哪些是 AI、哪些是你們寫的？」

先給結論：**這套的「骨幹」是確定性程式碼，AI（LLM）只用在三個「需要讀/寫自然語言、或需要語意理解」的地方。** 所以它不是「叫 AI 畫圖」，而是「**程式碼建圖、AI 只補幾個特定角色**」。對照 P8、P9 的步驟：

**用到 AI（LLM）的只有三處：**
1. **讀文件**（P8-②）：問 DeepWiki + 把文件/程式碼整理成 notes——要讀自然語言。
2. **產流量情境**（P8-④ 的「產生」那半）：設計一段合理的使用者旅程，要懂 API 語意；**但執行那半是我們自己的程式碼**（TrafficRunner）。
3. **補殘餘 + 寫報告**（P9-④ 和 P8-⑦ 的報告文字）：確定性解不掉的邊才丟 LLM 猜；最後那份文字報告也是 LLM 寫的。

**其餘全是我們自己寫的確定性程式碼：**
- 靜態抽取（P8-①）：tree-sitter + `.scm` 規則，純解析。（只有「沒有 grammar 的語言」才會 fallback 用 LLM，但 Java / Python 都走確定性）
- 抓叢集（P8-③）、量測 Prometheus（P8-⑤）：kubectl / PromQL 查詢 + 解析。
- **建圖合併（P9）的 7 步裡有 6 步是程式碼**——runtime 圖、併程式碼邊（`env-address` / `path→service` 都是確定性）、提升真用 DB、標部署狀態、正規化、輸出 + 覆蓋率；只有「補殘餘」那一步用 AI。

**一句話總結給老師：**
> 「**圖的邊主要是程式碼確定性算出來的；LLM 只負責三件事——讀文件、產流量情境、補確定性解不掉的殘餘 + 寫報告文字。** 這是刻意的設計：能用規則解的就不丟給 AI，讓圖可重現、不亂猜——這也是 P9 講的『確定性優先、LLM 只補殘餘』。」

【口頭補充】這點很關鍵，因為它回答了「這是不是只是包一個 LLM？」——不是。核心是確定性的抽取 + 建圖，LLM 是**有邊界地**用在真的需要它的地方。而且本週修的東西（`env-address`、`path→service`、settle）**全在確定性那半**，等於是在「把更多原本要靠 AI 猜的，變成規則能解的」。

---

## P10 · Bank of Anthos 泛化驗證（抽取好，圖層不泛化）

從這頁開始是**這禮拜的重點**：拿一個從沒看過的專案來驗證泛化。我選 Bank of Anthos，因為它是 Java（Spring）+ Python（Flask）混合、有用 PostgreSQL、而且是 k8s 原生（不靠 Eureka），正好對上我們的方向。

結果分兩層：

- **抽取層泛化良好**：對 BoA 跑靜態抽取，43 檔、103 條邊、0 語法錯；Python 端（SQLAlchemy）跟 Java 端（JPA）的「DB 真用」都判對，39 個跨語言 endpoint 也全抓到。→ 抽取這一層對沒看過的多語言 repo 沒問題。
- **圖層原本不泛化**：把抽取結果「組成圖」那一步塌掉了——因為合併邏輯是以 runtime 節點為詞彙表、又假設「一個服務一個頂層資料夾」的扁平 layout。但 BoA 是巢狀的 `src/<群組>/<服務>/`，來源全被歸到 `src`，整張圖變成 **0 條邊**。
- **修法（通用）**：加兩塊——`ServiceRootScanner` 用「含 build 檔 / Dockerfile 的資料夾」去找服務根（layout-aware）；`env-address` 從 k8s 的 ConfigMap 讀 `env→host`，把間接的呼叫目標解出來。修完 **0 → 6 條邊**，跟真實架構一致，也順便讓「沒給 namespace 就走 greenfield 靜態出圖」這件事成立。

【口頭補充】這頁最重要的一句話是：**泛化的是「抽取」，不泛化的是「組圖」**——我把問題精準定位到那一層再修，沒有亂改。

---

## P11 · Bank of Anthos 泛化驗證（修好「自動導流量」）

這頁是 runtime 這條——BoA 我有實際部署到叢集去跑。我們的工具會自己產流量、驅動、再用 Istio 觀測。結果踩到三個 bug，我一個一個修（症狀 → 根因 → 修法）：

- **Bug ① 產生器**：LLM 產的流量把「後端服務的內部端點」往 ingress 打（404），又用 JSON 送表單登入（400）。根因是 prompt 只有 API-gateway / REST 的心智模型。修法：加「前端型態判斷」——如果是 SSR 網頁前端就**只打頁面**（GET `/home` 會讓前端自己在後端 fan-out），不去打後端端點、登入用 form 靠 cookie。
- **Bug ② 執行器**：prompt 修好後 LLM 改用 urlencoded 送登入，但執行器只會送 raw（JSON）body，urlencoded 被直接丟掉。結果登入還是 400、沒 cookie、`/home` 沒 fan-out。修法：補上 urlencoded form body 支援（執行器本來就有 cookie jar，所以只差這塊）。
- **Bug ③ 查詢**：導完流量馬上查 Prometheus，但 Istio 的 Prometheus 大約 15 秒才 scrape 一次，最晚發生的深層邊還沒落地就被快照漏掉。我直接查 Prometheus 證實那些邊其實都在。修法：導完流量後等一個 scrape 週期再查。

【口頭補充】這三個都不是 BoA 專用的 hack，是通用的修正——換別的專案也會受益。

---

## P12 · Bank of Anthos 泛化驗證（兩個通用機制）

除了修 bug，我還加了兩個通用機制，目的是把「最後需要人工補」的殘量壓小：

- **機制 ① Tier 2 · 失敗回饋**：原本 resume 迭代只拿到狀態碼、盲目重試。我改成把 4xx/5xx 的**回應內容**（常常會寫「哪個欄位錯」）餵回 LLM，讓它自己修 payload。對所有專案通用、改動也小。
- **機制 ② Tier 1 · 挖 App 自己的範例**：深層寫入路徑（像存款、轉帳）需要語意正確的 payload，這靜態推不出來；但這種 payload 通常**就在 repo 裡**——負載測試（Locust）、e2e、OpenAPI。我加了一個 harvester 依慣例找這些檔、把原文餵給產生器，讓 LLM 照抄真實欄位。驗證：BoA 的 loadgenerator 裡就有 deposit 的 `{account_num, routing_num, amount, uuid}`——正是之前猜不到才 400 的那組欄位。

最後是**誠實邊界**：我把補 payload 的策略分四層（Tier 1 挖範例 · Tier 2 回饋 · Tier 3 對話問人 · Tier 4 錄製重放）。真正不可約的 domain 值（像信用卡 token）才落到「對話問人」或誠實標成 UNREACHABLE。**Tier 3 這層剛好接回老師之前說的「對話結合」那條線。**

---

## P13 · Bank of Anthos 泛化驗證（runtime 成果圖）

這張是 BoA 修完之後，工具**自動**跑出來的 runtime 依賴圖。可以看到 `istio-ingressgateway → frontend`，然後 `frontend → userservice / contacts / balancereader / transactionhistory / ledgerwriter`，這 5 個後端全是實線（Istio 真的觀測到）。覆蓋率 **6/7 = 86%**；deposit / payment 從 400 變 200（就是 Tier 1 給對 payload 的效果）；整個是全自動、不是我人工補流量。

【口頭補充】剩一條 `ledgerwriter → balancereader` 是後端內部邊、比較難從外部觸發，這個我照實記，沒有硬湊。

---

## P14 · Greenfield 目前效果（train-ticket + 效果）

標題我寫「剛好裝不了 train-ticket，所以用它來測」——這句是真的：train-ticket 有 47 個服務，實際跑 runtime 大概要 25～35GB 記憶體，單機裝不下。**但這反而變成一個好機會**：我拿它來測「靜態 / greenfield」這條。

三塊：

- **怎麼運作**：呼叫指令時 namespace 留空 / 填 `none`，工具就自動判定 greenfield，orchestrator 會**跳過所有需要叢集的步驟**（k8s、Prometheus、流量、apply），只跑程式碼 + 文件，出一張靜態依賴圖。零 port、不碰叢集。
- **效果（兩個專案）**：兩個都能純靜態出圖——BoA 0→6、train-ticket **5→52**。圖全部是虛線 = code/doc 宣告、還沒經過 runtime 驗證，誠實不誇大。
- **誠實報告（本週剛修）**：之前靜態報告會**造假**叢集資料（像「25 個 Service observed / 50 個 Pod Running」）。我修了兩塊——① 把會幻覺的中間步驟 gate 掉 ② 讓報告 greenfield-aware，明確標「N/A — 靜態，無叢集」、不造假、還把建議改成「部署後帶 namespace 再跑一次」。

【口頭補充】train-ticket 其實又逼出一個**跟 BoA 不一樣的新缺口**：它的呼叫目標是編碼在 **URL 路徑**裡（`/api/v1/orderservice/...` → order-service），BoA 是放在 env。我也修了一個通用的「路徑段 → 服務」解析——這就是為什麼邊數會從 5 跳到 52。這也剛好說明「測第二個專案」的價值：它證明了我在 BoA 上的修法確實有點「BoA 形狀」，得靠另一個專案才逼得出新的通用修法。

---

## P15 · Greenfield 目前效果（train-ticket 靜態圖）

這張是 train-ticket 純靜態跑出來的圖：52 條服務呼叫邊（像 preserve → basic / seat / travel / order / food…）、還有 RabbitMQ 的非同步邊、跟 MySQL 的 db 邊。全部虛線，因為 greenfield 沒有 runtime——這張圖是誠實的「宣告層」相依圖。

【口頭補充】所以 greenfield 的定位是 runtime 的**前置**：先純靜態把圖建出來（適用還沒部署的專案），之後真的部署了，再用 namespace 補上 runtime 觀測。

---

## 收尾

這禮拜總結一下：我拿**兩個沒看過的專案**驗證泛化——

- **Bank of Anthos**（跑 runtime）：修好自動導流量的 3 個 bug、加了 2 個通用機制，最後全自動跑出 6/7 覆蓋的依賴圖。
- **train-ticket**（跑靜態 / greenfield）：又逼出一個「路徑編碼」的新缺口、也修成通用的，靜態就出 52 條邊；順便把 greenfield 的報告修到不再造假。

所以泛化這件事，從原本「只有 petclinic 一個專案」，變成「**兩個差異很大的專案 + 兩種目標編碼方式（env / 路徑）+ 兩種模式（runtime / static）**」都能動，而且**全部是通用修正、不是專案專用的 hack**。

下一步我想做的是把「**對話結合**」那條（讓人可以用自然語言查依賴圖）接起來——它剛好也是前面 Tier 3「對話問人」那層的延伸。

（報告到這邊，老師有沒有什麼問題。）
