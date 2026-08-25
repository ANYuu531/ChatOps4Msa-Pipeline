# 碩一週進度會議 · 詳細講稿（2026/8/6）

> 學生：李安喻｜主軸：讓依賴分析工具更**準確**、更**通用**
> 用法：可直接照念。`【…】` 是給自己看的走位/提示，不用念出來。

---

## 一、計畫

### 第 2 頁｜政策分析按鈕（一）功能與防呆

這週在公司列表，每一列原本只有一顆「分析報告」，我在它旁邊加了一顆「政策分析」，點下去會導到外部策略網站 `aisb.strategy.soselab.tw`。

這裡的重點不是加按鈕本身，而是**防呆**。因為不是每一家公司都有政策分析報告，如果不管三七二十一每一列都放一顆可點的按鈕，使用者點進去很多會是空的、壞的。所以我讓系統先比對資料、動態決定按鈕長相：**有報告**就顯示藍色、可點；**沒有報告**就顯示灰色虛線框、不給連結。右邊這張列表就能看到，Tesla、Ørsted 是藍的、有政策分析，Google、Ford 這種就是灰的，一眼分得出來，也不會誤點。

### 第 3 頁｜政策分析按鈕（二）資料怎麼來

那「有沒有報告」是怎麼判斷的？靠這支腳本 `build_policy_analysis_index.py`，三個階段。第一階段先去爬 AISB 的公司清單；第二階段做**名稱正規化比對**——這步是關鍵，因為兩邊的公司名寫法不見得一樣，有大小寫、有 Inc./Corp. 這種後綴差異，一定要正規化之後才對得起來；第三階段輸出成 `policy_analysis_index.json`，前端就是讀這份索引來決定按鈕。

右邊是比對的數字：總共 47,752 家公司，真正對上、有政策分析的只有 119 家。所以你看，絕大多數是沒有的——這也正好回頭解釋為什麼那個防呆這麼重要，不然大部分按鈕都會是壞連結。

### 第 4 頁｜中英文切換

報告要能中英文切換。我這週用 **Argos Translate 搭配 zhconv**。Argos Translate 是一個**離線**翻譯引擎，做底層的中英互譯，完全不打外部 API；zhconv 負責把翻出來的簡體轉成正體，因為開源翻譯引擎大多以簡體輸出。

為什麼特別選這組合、而不是直接接 Google Translate 或 OpenAI？三個理由：**不用連網**，全本地端跑，敏感資料不外洩、斷網也能用；**不用金鑰**，省掉申請、管理雲端 API 憑證那些瑣事；**沒有用量限制**，零計費、無次數上限，很適合我們這種大規模、高頻次的批次翻譯。等於是拿一點翻譯品質，換到隱私、成本、跟可大量自動化這三件事。

### 第 5 頁｜Playwright 自動化測試

上面這些功能我用 Playwright 補了自動化測試，五支：`test_company_list` 測搜尋、排序、清除篩選、分析報告按鈕的有無；`test_policy_analysis` 專測剛剛那顆政策分析按鈕的顯示條件跟連結格式，也就是把防呆那段自動驗；`test_report_language` 測中英文切換，涵蓋有翻譯、沒翻譯兩種情況；`test_article_search` 測公司內搜尋、全站搜尋、還有空結果的處理；最後 `test_api_smoke` 是直接打 API、不開瀏覽器，跑最快，當快速煙霧測試。

【過場】計畫的部分到這，接下來進碩論。

---

## 二、碩論

### 【進碩論前，先立主軸——務必口頭講】

進碩論前先講一下這幾週的主軸，這樣後面三件事才不會像各講各的。老師其實一直在問我兩件事：這個依賴分析工具**準不準**、還有它是不是**只對這一個 Java 專案有效**。所以我把這週的工作都對到這兩條軸上——**準確度**跟**通用度**。等一下每一段我都會說清楚它是在回答哪一題：去 Eureka 是為了準確度、通用化 DB 是為了通用度、最後的流程圖是把「為什麼它通用」講清楚。

### 第 6 頁｜petclinic 去 Eureka（一）為什麼要做

第一件、對到準確度。我把測試用的 petclinic 從 Eureka 改成 k8s 原生服務發現。為什麼要做，三個原因。

第一，**圖看起來不準**。工具做程式碼抽取是去 clone 原始碼的，只要 code 裡還宣告 Eureka，圖裡就永遠多一個 `discovery-server` 幽靈節點——它其實不是業務依賴，是基礎設施，但它一直掛在那，讓圖不乾淨。

第二，**覆蓋率假性偏低**。Eureka 的 `lb://` 會把服務名解析成 pod IP、繞過 k8s 的 Service VIP，Istio 就把它當成 passthrough、看不到，結果業務端點回 405、流量根本打不進去，覆蓋率數字很難看——但這其實不是工具的問題，是 Eureka 這個部署方式害的。

第三，**不夠有代表性**。現在的微服務多半用 k8s 原生服務發現了，一個綁死 Eureka 的目標，撐不起「工具又準又通用」的論述。所以結論是：要證明工具準，先給它一個乾淨的目標；而乾淨的前提是**改 code、不是繞過部署**。

### 第 7 頁｜petclinic 去 Eureka（二）怎麼做、為什麼這樣做

怎麼做？我寫了一支 `de-eureka.sh` 一鍵重構：路由從 `lb://svc` 改成 `http://svc:port` 直連、移掉 eureka-client 依賴跟 `@EnableDiscoveryClient`、`@LoadBalanced`、刪掉整個 discovery-server 模組跟 docker-compose 裡的相依，最後把 FallbackController 用到的 Apache HTTP 常數換成 Spring 的 HttpStatus——這個是連鎖反應，那個 Apache 依賴其實是 eureka-client 傳遞進來的，一移除就編不過，順手一起修。

兩個「為什麼這樣做」的取捨。**為什麼 fork code、不是只改部署？** 因為工具分析的是原始碼，只改部署、code 裡還宣告 Eureka，圖就還有幽靈。而 petclinic 剛好拆兩個 repo，路由定義在 code repo 的 `application.yml`，所以我只 fork code repo 就夠、config repo 不用動。**為什麼 http 直連、不是導入 Spring Cloud Kubernetes？** 因為直連最簡單也最穩，完全不依賴任何服務發現客戶端；而且 `http://svc` 走 meshed ClusterIP，Istio 能完整觀測，順便根治 `lb://` 繞過 VIP、追蹤到 pod IP 的問題。等於一個改動同時解決兩件事。

### 第 8 頁｜petclinic 去 Eureka（三）成果

這是成果，左上 Before 右下 After 對照。Before 是 Eureka 版：`discovery-server` 是紅色虛線的幽靈，業務邊是虛線標 405、流量打不進。After 是 fork 之後：discovery-server 不見了、業務邊變實線代表 runtime 真的觀測到、config-server 對外連 github 的 egress 也乾淨呈現。

這裡我最想強調的是這個**對照實驗**，它其實是「準確度」最有力的證據：我有一次不小心把 `repo_name` 填成上游原版 petclinic，那個幽靈 discovery-server 就又回來了。這反過來證明——**圖是如實反映「那份被分析的 code」，不是工具憑空亂畫**。換句話說，圖乾不乾淨，是被分析對象決定的，工具只是忠實反映。這正是準確度要的。

---

### 第 9 頁｜通用化判定 DB（一）為什麼要做

進第二件，對到「通用度」。這件事是把「判斷一個服務有沒有**真的在用**資料庫」通用化。我先解釋為什麼這件事重要、以及為什麼原本的做法不夠。

依賴圖的價值，不只是畫出「A 連到 B」，更難、也更有價值的是**分辨程度**——尤其 DB 這一塊，老師之前自己就提醒過一個陷阱：一個服務在設定檔裡宣告了一個 datasource，**不代表它真的有在用**。很多是複製貼上留下來的、或宣告了但那段程式碼是死的。如果工具把「宣告過」就當成「有在用」，那張圖會嚴重高估依賴、誤導人。

所以工具要做的是：**要有證據**，才說這個服務真的持久化到某個 DB。而「證據」在 Java 裡，就是有 `@Entity`、`@Repository` 這種 JPA 標記。問題是——看左邊——當時的實作是一行寫死的 `if ("jpa".equals(section))`，它**只認 Java 的 jpa 標記**，跟 Spring/JPA 深度綁死。這代表什麼？代表如果今天來一個 **Python 的服務**，就算它明明用 SQLAlchemy 在寫資料表，工具也**一條 pattern 都沒有**、完全抓不到，那個服務在圖上就會缺 DB 邊、或只剩最弱的「宣告」層。

這正好命中老師的提問：「不同語言有不同的 interface 實作」——潛台詞就是，**這工具是不是只對這個 Java 專案有效？** 所以右邊是我的重構目標：把 DB 判定從「Spring/JPA 專屬」抽成一個**語言中立的介面**——第一，解耦掉對特定 Java 框架的依賴；第二，讓 Java、Python、甚至未來其他語言，都用同一套規則判定；第三，高可擴充——未來要加語言或 ORM，只要實作既有介面，不用改判定核心。這三點，就是「通用度」最硬的證據。

### 第 10 頁｜通用化判定 DB（二）怎麼做、為什麼這樣做

怎麼做？核心是這條處理鏈。我要特別強調它的設計精神：**從頭到尾，中間沒有任何一層認得「這是哪一個 ORM」**——這正是它能通用的原因。我一步一步走。

【走鏈】第一步，在 tree-sitter 的 `.scm` 規則裡，我寫一個捕捉叫 `@persistence.x`。第二步，抽取器在 emit 的時候，有一個命名慣例：把「點號前面」的字當成 **section 名**、點號後面當欄位——所以 `@persistence.model` 就會變成一筆 section 是 `persistence` 的資料。第三步，這筆資料進到 EdgeLedger，而 EdgeLedger 的 **section 是自由字串**、不是寫死的列舉，所以一個新的 `.scm` 想引入新 section，完全不用改 Java。第四步，判定端就只做一件事：問一句 `PERSISTENCE_SECTIONS.contains(section)`——它只看 section 的名字，根本不知道、也不需要知道這背後是 JPA、SQLAlchemy 還是 Django。

所以實作上的改動非常小：把原本那行 `"jpa".equals` 改成一個集合 `PERSISTENCE_SECTIONS = { jpa, persistence }`。Java 的標記繼續走 `jpa`，其他所有語言都 emit 到 `persistence`。就這樣。

【為什麼做成介面】為什麼要花力氣做成這種介面、而不是簡單地寫 per-language 的 if-else？因為**擴充成本差很多**。用我這種介面設計，之後要加一個 ORM——比如 Java 的 MyBatis、Go 的 GORM、JS 的 TypeORM——對於**文法已經接好的語言**，我只要**新增一條 `.scm` 規則、零 Java 改動**。舉個具體的：要支援 MyBatis，我只要在既有那條規則的清單裡多加一個字串 `"Mapper"`，一個 token，收工，不用碰 Java、不用重編。反過來，如果當初是寫語言特定的 if-else，每加一種 ORM 都得改 Java、重編、重測、重部署——這就不叫通用了。

【為什麼三層信心不動】第二個關鍵：我泛化的時候，那套「三層信心」的邏輯**完全沒有動**，這也是刻意的。為什麼有三層？因為不同來源對 DB 的可靠度不一樣。**Runtime——也就是 Istio 觀測——對 DB 是低 recall**，這點很重要：資料庫通常沒有 Istio sidecar，它是有狀態的、常常是外部託管的，所以 Istio 根本看不到「服務打到 DB」這條流量。這代表什麼？代表**如果你只用 runtime 來判斷 DB，你會一條 DB 邊都畫不出來**。所以不能只靠 runtime。那 code 呢？code 的 recall 最好——就算連線字串被外部化到 config-server，只要服務裡有 repository、有 `@Entity`、有 model，我就抓得到；但 code 的 precision 是中等——可能是死碼、宣告了沒跑。最弱的是只在 config 或文件裡宣告、完全沒有使用證據的。所以我分三層：**runtime 觀測到 = 最高信心（實線）／code 有真用證據 = 中間（虛線 documented）／只宣告 = 最弱（點線加問號 inferred）**。而這套分層是**作用在合併後的邊上、跟任何語言的語法都無關**——所以我把 DB 判定泛化到新語言時，這段核心一行都不用改。這就是為什麼「加語言」的成本可以壓到「只寫一條 `.scm`」。

### 第 11 頁｜通用化判定 DB（三）涵蓋與 SQLAlchemy Core

這是目前實際涵蓋的範圍，四種：Java 的 JPA 抓 `@Entity`、`@Repository`；Python 的 SQLAlchemy **宣告式**抓繼承 `Base` 或有 `__tablename__`；Python 的 Django 抓 `models.Model`；還有**本週新增的 SQLAlchemy Core**，抓 `Table()` 加 `Column()`。

我特別講一下這週為什麼加 Core、以及怎麼確定它沒問題，因為這一段剛好是「零 Java 擴充」實際跑一次的例子。

【決策緣由】起因是我下一個要測的專案 **Bank of Anthos**，它的 Python 服務用的是 SQLAlchemy 的 **Core** 風格、不是宣告式。差別在哪？宣告式長這樣：`class User(Base):` 裡面寫 `__tablename__ = "users"`——有一個 class 繼承 Base、有 `__tablename__`，我原本的 pattern 就是抓這兩個特徵。但 Core 風格長這樣：`users = Table('users', MetaData(), Column('id', ...), Column('name', ...))`——它是一個**函式呼叫**、沒有 class、沒有繼承 Base、沒有 `__tablename__`。所以我原本的 pattern **完全抓不到**它。如果不補，Bank of Anthos 的 Python 服務就會漏掉 DB 邊，準確度就有缺口——而且這個缺口的原因剛好是「工具還沒支援 Core 這種寫法」，正是通用度要補的。

【怎麼補、為什麼這樣補】所以我加了一條 pattern 抓 `Table(...)`。但這裡有個坑：光抓 `Table('...')` 會**誤判**——因為 `rich`、`prettytable` 這些跟資料庫完全無關的套件，也有叫 `Table("標題")` 的呼叫。如果我把它們也當成 persistence 標記，就會誤報一個服務「有在用 DB」。所以我加了一個限制：**必須同時帶有 `Column(...)` 引數**——因為只有真正的 ORM 資料表定義，才會在 `Table()` 裡面內嵌 `Column()`；rich 那種是不會的。這樣就把精確度救回來。

【怎麼驗證】然後我沒有直接相信它，我寫了一個臨時的 tree-sitter probe 實際跑一次：餵它三種東西——Bank of Anthos 風格的 Core `Table()`、一個 Django model、還有一個 rich 風格、沒有 Column 的 `Table("標題")`。結果如右下：**Core 成功抓到、Django 原本的也沒受影響、rich 的沒有被誤抓**，三個都符合預期。驗完我就把 probe 刪掉，維持 committed 測試都是純 Java 的慣例。

這一整段的意義是——我把 DB 偵測擴充到一個全新的 ORM 寫法，**從頭到尾沒有動任何一行 Java，只加了一條 `.scm`**。這就是前面講的「零 Java 擴充」，不是講講而已，是這週真的跑過一次。

---

### 第 12 頁｜目前產生依賴的流程圖（一）總流程

這張是工具的**總流程**，我從使用者下指令開始、一格一格帶，讓老師看到它的通用是怎麼來的。

【進入】最上面，使用者在 Discord 打一個指令，比如「get dependency analysis」加上參數。系統先經過 **NLP 解析**——這一層用 LLM 把自然語言轉成「意圖加參數」，也就是判斷出你要跑的是「依賴分析」這個 capability，並抽出要分析哪個 repo、哪個 namespace、entry URL 是什麼。接著做**權限檢查**，確認這個使用者有權跑；通過之後交給 CapabilityOrchestrator，丟進一個**背景執行緒**去執行。這個背景執行緒是必要的設計，因為 Discord 的互動要在三秒內回應，而分析要跑很久，不丟背景就會逾時。

【開 checkpoint】進到收集管線，第一件事是 `depstate-start`，開一個 **checkpoint**。這個 checkpoint 是整個流程的骨幹——後面每一個收集階段都會把結果存進去，所以可以中途暫停、之後 resume 而不用從頭重跑。

【三來源之一：Code】接著是三個互補的來源。**第一個是程式碼抽取**：先偵測技術棧，如果是 Java 或 Python，就走 tree-sitter 做**確定性**的深度抽取——抓 feign client、HTTP 呼叫、還有剛剛講的 DB 持久化標記；如果是還沒接文法的語言，就退回用 LLM 讀原始碼。但重點是——**這兩條路吐出來的是同一種格式**，叫 EdgeLedger，所以後面完全不需要為了「這是哪條路來的」而分岔。這也是通用的關鍵。這個階段的結果存成 `code` stage。順帶它也會產生 ServiceEntry 的建議，等最後才問要不要套用。

【三來源之二：Doc】**第二個來源是文件**：連到 DeepWiki，問五個問題——各服務的職責、同步呼叫關係、非同步通訊、外部依賴、還有架構入口點。然後用一次 LLM 把「文件的證據」跟「程式碼的證據」合併，存成 `merged_notes`。這一層補的是 code 看不到、但文件寫得出來的東西。

【三來源之三：K8s / Istio】**第三個來源是叢集實況**：用 kubectl 抓 services、deployments、pods、endpointslices，還有 Istio 的 VirtualService、DestinationRule。這層告訴我們「現在到底部署了什麼、長什麼樣」。

【驅動流量 + 量測】三來源收完，接著**驅動流量**：用 LLM 依照剛剛抽到的 API 面、路由、文件，產生一組真實的使用者 journey，實際打進去——目的是讓 Istio 能觀測到服務之間真的有呼叫。打完之後查 Prometheus 的 `istio_requests_total`，這就是 **runtime 邊**的來源，存成 `traffic_raw`。緊接著我會算一次**確定性的覆蓋率**——用程式碼算、不經 LLM——看這一輪到底打到了幾成的業務邊，然後把數字貼到頻道，讓迭代的進度看得見。再來查 **egress**，也就是離開 mesh、連到外部的流量，比如服務連到 github 或某個第三方 API。最後做一次 **LLM 的完整性檢查**，列出還缺哪些邊、給補強建議。

【暫停與三條路】收集到這裡就 **checkpoint 暫停**，並貼出按鈕。使用者有三條路：第一，**套用 ServiceEntry**，讓那些外部依賴之後能被 Istio 觀測到；第二，**Resume 補流量**——這是一個**覆蓋率導向的迴圈**，它會讀「目前還沒被觀測到的邊」當這一輪的目標、產生針對性的 journey、再打、再量，可以跑好幾輪，把覆蓋率一點一點推上去；第三，按「**Generate report**」出圖。

【一個設計重點】最後我要點出一個設計哲學：**收集跟出圖是分開的**。所有東西都存在 checkpoint，暫停、補流量、resume 都不會重跑昂貴的步驟——像 clone repo、DeepWiki 問答這種——只有真的按下出圖，才進到下一張的建圖流程。

### 第 13 頁｜目前產生依賴的流程圖（二）建圖合併

這張是按下「Generate report」之後、把圖真正組出來的**合併鏈**。它的精神一句話：**確定性優先，LLM 只收殘餘**。我一樣一格一格走。

【骨架】最左邊，先拿 `traffic_raw` 的 Istio JSON，經過 **RuntimeGraphBuilder**，建出圖的**骨架**——這些是**實線、runtime 觀測到**的邊，信心最高。旁邊 `egress_raw` 再經過 `mergeIstioEgress`，把已經歸因的外部 host——比如 github.com——併進來。

【疊 code 邊】接著 `code_edges` 進 **CodeGraphMerger**。它把程式碼抽到的邊——feign、http client、url、kafka、db——**對齊**到骨架上：對得上既有節點就合併、標成「兩邊都證實」；對不上、但名字合理的就自己補一個新節點——因為一個 runtime 沒打到、但 code 明明有宣告的依賴，也是真的依賴，該畫出來；真的解不出來的，丟進一個叫 residue 的殘餘清單。

【疊 doc 邊】再來 `merged_notes` 進 **DocGraphMerger**，把 DeepWiki 的文件邊 enrich 上去。這裡的原則是「**只補強、不無中生有**」——如果文件講到一個服務，但它對不上任何已知的 workload，就**丟掉、不建幽靈節點**。這是我之前踩過雷特別修的，不然文件裡的別名、技術名詞會生出一堆假節點。

【DB 升級】然後 **promoteReallyUsedDbs**——這格就接到我剛剛第二件事講的通用化 DB：如果某個服務有持久化程式碼，不管是 jpa、SQLAlchemy 還是 Django，就把它的 DB 邊從「只宣告 inferred」升級成「真的有用 documented」。三層信心的中間那層，就是在這裡被打上去的。

【標部署狀態 + 清雜訊】接著 **K8sGraphBuilder** 用叢集實況，標記每個節點是 deployed 還是 not-deployed——所以那種「code 有、但沒部署」的服務，會被標成灰色。再來 **GraphNormalizer** 做清理：清掉框架帶進來的假 library 節點、合併別名、做分組，讓圖不要有雜訊。

【最後才用 LLM】到這裡才輪到 **resolveResidueWithLlm**——注意，**只有前面確定性解不出來的那些 residue，才拿去問 LLM**，而且會嚴格驗證：LLM 給的來源跟目標，一定要在已知節點集裡，否則丟棄。這保證 LLM 不會亂加邊、不會污染前面確定性建好的圖。

【輸出】這樣得到一個 canonical 的 DependencyGraph，最後分三路輸出：MermaidEmitter 出 `.mmd`、DotEmitter 經 Graphviz 出 PNG、CoverageAnalyzer 算覆蓋率——而且覆蓋率只算「可驅動的業務邊」，不把 DB、外部這種本來就打不到的算進去，才不會誤導。

【收尾——一定要講的重點】最後我把整張圖的精神收在這句：**線型反映三層信心**。**實線**是 runtime 真的觀測到、最高；**虛線**是 code 或 doc 證實「真的有用」；**點線加問號**是「只宣告、沒證據」、最弱。所以像那種只在設定檔宣告、其實根本沒在用的 DB，永遠不會被畫成看起來有在用——這就回到我整份報告的主軸：讓這張依賴圖**又準、又通用**。

---

### 【口頭補充 · 收尾】為什麼下一個測試專案選 Bank of Anthos

最後補充一下，我下一步要做的是老師交代的「拿其他專案再測一次」，來證明準確度跟通用度不只對 petclinic 有效。我花了時間比較候選，最後選 **Bank of Anthos**（Google 的示範銀行 app），理由有五個，而且它剛好一個目標就同時打中我這兩條軸：

1. **一個專案同時有 Java 跟 Python。** 它的核心業務服務，有三個是 Java Spring Boot、三個是 Python Flask。這代表我**一次部署就能同時驗兩種語言**——這點很關鍵，因為我們的機器記憶體有限、一次只能部一個系統，如果 Java 一個、Python 一個要分兩次，很麻煩；Bank of Anthos 一次搞定。

2. **服務之間走 HTTP、不是 gRPC。** Java 側用 RestTemplate、Python 側用 requests，都是我們工具**最擅長**抽取的呼叫方式。我特別避開了像 Google Online Boutique 那種全 gRPC 的專案，因為 gRPC 是工具目前的弱項，拿它測會顯得不準、不公平。

3. **它有 PostgreSQL，而且兩種語言用不同 ORM。** Java 側用 JPA、Python 側用 SQLAlchemy——這剛好能**同時驗證我這週做的 DB 通用化**，包含我特地補的 SQLAlchemy Core。也就是說 DB 那條線在這個專案上會被真正考驗到。

4. **它是 k8s + Istio 原生的。** Bank of Anthos 本來就是 Google 用來示範 service mesh 的 app，它自己就附 k8s manifests 跟 Istio 的設定，所以**部署很單純**，不會像 petclinic 那樣要自己 fork、改一堆東西才跑得起來。

5. **它有官方的架構圖。** 這對「準確度」特別重要——我可以拿工具**自動產生的依賴圖，去跟官方畫的架構圖比對**，看它抓得準不準。這就給了我一個現成的 ground truth 來衡量準確度。

所以總結一句：Bank of Anthos 一個專案，就能同時驗**通用度**（Java + Python 一次測）跟**準確度**（有官方架構圖可比對），CP 值最高，這也是我選它的原因。

（誠實補充目前進度：這週已經把它部署上去了，但遇到一個小卡點——Bank of Anthos 預設開了 Google Cloud Trace，需要 GCP 的憑證，我們是自架的 k3s、沒有 GCP 憑證，所以服務會開機失敗。我已經找到解法，就是把 tracing 關掉，這不影響依賴圖，因為我們的 runtime 邊是來自 Istio sidecar、跟 app 自己的 tracing 無關。下週就能把分析跑完、拿結果跟官方架構圖比對。）

我這週的報告到這邊，謝謝老師。

---

### 附錄 · 可能被追問的 Q&A（自己準備，不一定念）

- **Q：Core 那條限制「必須有 Column」，會不會漏掉沒有 inline Column 的表定義？** A：會有極少數（例如用 reflection/autoload 的表），但那類本來就沒有欄位證據；且漏抓只是少一條 db 邊、不會誤報，符合「寧可保守也不高估」的原則。真有需要可再補一條 autoload 的 pattern。
- **Q：為什麼不先測 train-ticket？** A：它 40 多個服務、太吃記憶體，單機跑不動；而且它是純 Java，證明不了 Python 的通用性。
- **Q：關掉 tracing 會不會讓圖少東西？** A：不會。runtime 邊來自 Istio sidecar（proxy 層），跟 app 自己匯到 Cloud Trace 無關；反而少一條「app→cloudtrace」的假 egress 邊。
- **Q：三層信心裡「只宣告」那層有什麼用？** A：它讓「宣告了但沒證據」的依賴仍然可見、但明確標最弱（點線問號），不會被誤讀成真的有用——這正是老師提醒的 DB 高估風險的防線。
