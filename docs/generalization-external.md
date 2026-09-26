# 標準答案不是我給的：對照第三方資料集、逐條可複驗、第二標註者（2026-09-25）

> 回應 2026-09-25 反饋第 2 點：**「程式評分的部分，因為標準答案是我給的，所以可靠度會降低一點，這部分要改說法或是用別的方式。」**
>
> 這個疑慮是對的，而且它有名字：**建構效度**（construct validity）。`docs/generalization-2026-09-22.md` §4 的 P／R 是程式算的，但分母與分子都來自 `docs/generalization/truth/<name>.tsv`——同一個人決定了什麼是正確答案，又寫了被評的工具。「程式計分」只保證**算得沒錯**，不保證**答案是對的**。
>
> 這一輪做三件事，強度由高到低：
>
> | | 做法 | 作者的參與程度 | 產物 |
> |---|---|---|---|
> | **①** | 對照 **MicroDepGraph 資料集自己發表的依賴圖**，7 個專案 | **零**——邊集是別人發表的，作者只寫對照程式 | `docs/generalization/external-agreement.md` |
> | **②** | 讓手寫 truth 的每一條**逐條可複驗**：出處補成完整的 `路徑:行`，可被第三方直接打開 | 作者從「決定答案」降為「抄錄出處」 | `truth/*.tsv`（已改寫 32 條）、本文件 §2 |
> | **③** | **第二標註者**：一個沒看過 truth、也沒看過工具輸出的 LLM 獨立標一份，算逐條一致度 | 作者只裁決不一致的條目 | `SecondAnnotatorTest` → `docs/generalization/second-annotator/<name>.md`（**待機器 B 跑，本機 API 無額度**） |
>
> 措辭也改：不再寫 “ground truth”／「標準答案」，一律寫**「參考邊集」**，指標寫**「一致度（agreement）」**而不是準確率。§4 說明每個詞怎麼換。
>
### 結論（要拿去簡報與論文的那三句）

1. **工具的泛化結果不再只靠作者的標註**：在一份**完全不是我們寫的**參考邊集上（第三方發表的 7 個專案、95 條邊），工具畫到 **91 條＝0.96**，漏的 4 條逐條可解釋（2 條是 repo 演進、2 條是已知邊界）。這個數字可以單獨拿去報告，因為標準答案不是我給的。
2. **作者標註目前沒有發現偏差，但只驗過一個專案**：唯一兩邊都有的 `robot-shop`，第三方的 12 條邊**全部**落在作者 truth 的 21 條裡，漏標 0、矛盾 0（§1.7）。作者多標的 9 條是第三方方法看不到的東西（程式碼呼叫、外部主機、nginx 路由），每條都有打得開的出處。**樣本 1，所以是「沒發現偏差」不是「證明沒有偏差」。**
3. **作者標註的地位降級、並且可被別人檢查**：不再稱它為 ground truth／標準答案，改稱「參考邊集」，指標改稱「一致度」；每一條出處都必須是第三方**打得開**的 `路徑:行`，由 `TruthEvidenceResolvesTest` 斷言（92／92），這道檢查第一次跑就抓到 **2 條寫錯的出處**。第二標註者（③）**還沒有數字**，所以「作者與另一個標註者看法有多接近」目前仍是空白。

附帶的收穫：這一輪對照當場逼出工具的 **3 個真缺陷**（§1.6），修完既有七個專案零回歸。

---

## 1. ① 對照第三方資料集

### 1.1 為什麼這份資料集可以當參考邊集

`src/test/resources/graphs/microdepgraph/` 裡的 20 張圖是 MicroDepGraph 資料集的產物（Rahman, Panichella & Taibi, *A Curated Dataset of Microservices-Based Systems*, SattoSE 2019，[CEUR-WS Vol-2520](https://ceur-ws.org/Vol-2520/paper1a.pdf)；資料集 [clowee/MicroserviceDataset](https://github.com/clowee/MicroserviceDataset)）。它們本來是為了 `threshold-design.md` §8 的「真實系統有多深」而收進來的；這一輪發現它們還有第二個用途：**它們就是 20 個專案的依賴圖，由別人發表，我們一個字都沒改。**

其中 **7 個專案**的 GitHub repo 可以直接跑我們的靜態探針（資料集 README 的短連結逐一解析得到 repo 位址，解析結果寫在 `ExternalTruthAgreementTest.CASES`）。選這 7 個的理由是它們主要是 Java／Spring，工具有文法；其餘 13 個是 C#／Ruby／PHP／多語系，落在工具已知的「無文法語言」邊界內，比出來的只會是那條已經量過的邊界。

**時間對齊**：資料集的檔案日期是 2021-02-26。我們**把每個 repo checkout 到那一天之前的最後一個 commit**，兩邊才是看同一份程式碼。commit 與日期寫在對照表裡，每一格都是可點的 GitHub 連結。

### 1.2 兩邊量的不完全是同一件事（先講清楚）

| | MicroDepGraph | DepWeaver 靜態層 |
|---|---|---|
| 邊怎麼來 | Compose `depends_on`／`links` ＋ 內部 API 呼叫 | tree-sitter 抓的呼叫點、設定檔位址、manifest 佈線、反向代理路由 |
| 基礎設施 | 當一般節點（eureka、rabbitmq、zipkin、mysql） | 一部分被 `GraphNormalizer` 視為平台基礎設施 |
| 外部主機 | 沒有這個概念 | 有（`github.com`、`paypal.com`） |
| 證據等級 | 沒有 | 三級（observed／documented／inferred） |

所以這裡**不報 precision**：工具多畫的邊大多是資料集的方法看不到的東西（程式碼層的呼叫、外部主機），把它算成「錯」是錯的。報的是：

- **一致度**＝資料集的邊裡工具也畫了的比例。**這個方向上漏掉就是工具的問題**，沒有辯解空間。
- **工具另外畫了幾條**，並**逐條列出**，每條都要能追到來源。

### 1.3 結果

| 專案 | commit | 資料集邊 | 工具畫到 | 一致度 | 工具另外畫了 |
|---|---|---|---|---|---|
| spring-petclinic-microservices | `8e446ae` (2021-02-06) | 13 | 11 | 0.85 | 4 |
| ewolff/microservice（Microservices book） | `99d9d07` (2020-09-17) | 5 | 5 | **1.00** | 4 |
| Tap-And-Eat-MicroServices | `3ad20b8` (2017-01-04) | 4 | 4 | **1.00** | 3 |
| spring-cloud-netflix-example | `3b86bf0` (2020-09-11) | 26 | 26 | **1.00** | 2 |
| spring-cloud-microservice-examples | `6938297` (2017-03-23) | 26 | 24 | 0.92 | 5 |
| LakesideMutual | `4fc6b43` (2021-02-26) | 9 | 9 | **1.00** | 3 |
| robot-shop | `2fcc0c9` (2021-02-24) | 12 | 12 | **1.00** | 6 |
| **合計** | 7 個專案 | **95** | **91** | **0.96** | — |

逐條差異在 `docs/generalization/external-agreement.md`（程式產生）。

### 1.4 漏的 4 條，逐條原因

| 漏的邊 | 原因 | 是不是工具的問題 |
|---|---|---|
| `hystrix-dashboard → config-server`、`hystrix-dashboard → discovery-server`（petclinic） | **repo 演進**：資料集抓的版本有 hystrix-dashboard，2021-02-06 的 repo 已經沒有這個模組，compose 裡也沒有這個服務 | 不是。工具畫不出不存在的東西，而且它沒有為了對上答案而編造 |
| `hystrix → discovery`、`hystrix → gateway`（spring-cloud-microservice） | **工具的剩餘邊界**：compose 服務叫 `hystrix`，模組目錄叫 `cloud-hystrix-dashboard`。對應規則接受「完全相同」或「模組名以 `-<部署名>` 結尾」，而這裡部署名是模組名的**中綴縮寫**，對不上，於是那兩條 `links` 解析不到來源 | **是**。不修的理由：允許「任一 `-` 分隔片段唯一匹配」會把 `cloud-simple-service` 配到 `simple`，誤併的代價高於這 2／95 條 |

### 1.5 工具多畫的 27 條是什麼

抽樣（全部在 `external-agreement.md`）：

- `config-server → github.com`（petclinic）、`configservice → github.com`（tap-and-eat）：Spring Cloud Config 從 GitHub 抓設定檔，真的存在；資料集沒有外部主機的概念。
- `api-gateway → customers-service`／`vets-service`／`visits-service`（petclinic）：Feign client 的程式碼與 gateway 路由表，`CustomersServiceClient.java:36` 那一行；資料集靠 `depends_on`，那個檔案裡沒有這些。
- `order → catalog`、`order → customer`（ewolff）：程式碼層呼叫。
- `payment → user`、`payment → cart`、`payment → paypal.com`（robot-shop）：Python 程式碼與外部支付閘道。
- `web → cart`、`web → ratings`（robot-shop，標 `inferred`）：nginx `proxy_pass` 的 `${CATALOGUE_HOST}` 這類佔位符靠變數名推的，所以是點線。

**這一欄不是「工具比較好」的證據**，而是「兩邊的方法看得到的東西不同」的證據。要說工具比較好，得對每一條再找出處——那又回到作者標註，所以這裡只列不評分。

### 1.6 這一輪對照當場逼出的兩個真缺陷（都已修，零回歸）

**第一個：沒有 k8s manifest 時，節點名用錯了。**

第一次跑完，7 個專案裡有 4 個（petclinic、ewolff、tap-and-eat、spring-cloud-microservice）**一致度是 0.00**——不是邊錯，是**節點名一個都對不上**：工具用 Maven 模組名（`spring-petclinic-customers-service`），部署與所有呼叫端用的是 `customers-service`。更糟的是同一個服務會**同時以兩種拼法出現在圖上**（模組名那個帶著 compose 的邊，Feign 那個帶著程式碼的邊）。

原因：詞彙表只從 k8s manifest 的 workload 名建（2026-09-22 的規則 1），而這四個專案只有 Compose。

修法（**規則 11**）：**Compose 的 `services` key 也是部署詞彙表**，與 k8s workload 同級，但 **k8s 優先**——有 manifest 時完全不看 compose 名，避免兩種拼法混用（`ConfigExtractor.extractComposeServices` → 新的 `compose-service` ledger 區段；`CodeGraphMerger.indexMeta` 依序取 `k8s-workload`、`compose-service`，取到就停）。

**第二個：Compose v1 的檔案完全讀不到。**

tap-and-eat（2017）與 spring-cloud-microservice（2017）用的是 **Compose 第 1 版檔案格式**：沒有 `version:`、沒有 `services:`，**頂層每個 key 就是一個服務**，依賴宣告用 `links:` 而不是 `depends_on:`。工具只認 `services:` 與 `depends_on:`，所以這兩個專案的佈線是**整份看不到**（ledger 裡連一筆 compose 區段都沒有）。

修法（**規則 12**）：`composeServices()` 同時支援兩種格式（v1 的頂層服務要「是 map 且有 `image` 或 `build`」才算，避免把 `networks:` 之類當服務）；`links:` 與 `depends_on:` 一起讀（`links` 的 `服務:別名` 形式取冒號前那段）。

效果（同一份 checkout、改動前後）：

| 專案 | 改動前 | 改動後 | 資料集一致度 |
|---|---|---|---|
| spring-petclinic | 13 節點／15 邊，名字全是模組名 | **11 節點**／15 邊，名字全是部署名 | 0.00 → **0.85** |
| microservices-book | 7／5 | 8／**9** | 0.00 → **1.00** |
| tap-and-eat | 11／3 | 16／**7** | 0.00 → **1.00** |
| spring-cloud-netflix | 8／3 | 10／**28** | 0.04 → **1.00** |
| spring-cloud-microservice | 20／5 | 15／**29** | 0.00 → **0.92** |
| lakeside-mutual | 12／14 | 10／12（少 2 條假邊，見下） | 1.00 → **1.00** |

**第三個（精確度，不是漏抓）：XML namespace 被當成呼叫目標。**

LakesideMutual 的圖上有兩個外部主機 `lakesdemutual.com` 與 `lm.com`，**沒有任何程式會呼叫它們**。來源是三處 XML／SOAP 識別字：

- `@XmlSchema(namespace = "http://lakesdemutual.com/interfaces/xsd")`（JAXB **產生**的 `package-info.java`）
- `wsdl11Definition.setTargetNamespace("http://lm.com/ccore")`
- `new SoapActionCallback(\n    "http://lm.com/ccore/GetCustomerRequest")`（URL 字串在**下一行**）

namespace URI 依規範不必指向任何可連的東西。修法：`TreeSitterExtractor.dropNonAddressUrls()`——URL 字面值所在的**那一行或前兩行**若出現 `namespace`／`schemaLocation`／`xmlns`／`soapAction`，該 `url` 列標成不畫（沿用既有的 `unreferenced` 旗標，另記 `not-an-address=xml-namespace`），並在 ledger 留一條警告。這條規則對「畫少一點」的方向動手，所以誠實的說法是：**它有可能擋掉真的呼叫**（某行剛好同時有 URL 與這些字）；代價是我們接受的，因為假邊比漏邊嚴重。

**零回歸**：這三條改動之後，用**同一份 checkout** 對 2026-09-22 那六個專案加 Bank of Anthos、train-ticket 重跑探針，`ewolff-k8s / ecommerce / teastore / online-boutique / robot-shop / bank-of-anthos / train-ticket` 的節點數與邊數**逐項不變**；唯一的變動是 piggymetrics 少了一個**沒有任何邊的雜訊節點** `mongodb`（41 條邊完全不變，所以 §4 的計分不變）。

---

### 1.7 把第三方邊集拿去檢查**作者的 truth**（不是檢查工具）

前面幾節用第三方邊集檢查**工具**。但老師問的其實是另一件事：**作者標的那份參考邊集本身可不可信？** 只要有一個專案兩邊都有，就可以直接比——`robot-shop` 是唯一的交集（另外六個外部專案沒有作者 truth，另外六個作者 truth 的專案不在資料集裡）：

| | 條數 |
|---|---|
| 第三方（MicroDepGraph）的邊 | 12 |
| 作者 truth 的邊（非 variant） | 21 |
| **第三方的邊落在作者 truth 裡** | **12 / 12** |
| 第三方有、作者 truth 沒有（＝作者漏標） | **0** |
| 作者 truth 有、第三方沒有 | 9 |

多出來的 9 條是：`cart→catalogue`、`payment→user`、`payment→cart`、`shipping→cart`、`ratings→catalogue`（程式碼層的呼叫，第三方靠 `depends_on`／`links` 看不到）、`payment→paypal.com`（外部主機，第三方沒有這個概念）、`web→cart`、`web→ratings`、`load→web`（nginx 路由表與另一份 compose 檔）。每一條都有 §2 檢查過、打得開的出處。

**這一比的意義**：如果作者的標註有「往自己的工具傾斜」的偏差，最可能的形態是**漏掉工具抓不到的邊**（分母變小、分數變好看）。這裡漏標是 **0**——第三方認定的每一條都在作者的 truth 裡，沒有一條與之矛盾。**樣本只有一個專案**，所以這是「沒有發現偏差」而不是「證明沒有偏差」；但它是目前唯一一個不經作者的檢查，方向上是支持的。

## 2. ② 讓手寫的 truth 逐條可複驗

老師的疑慮有一個很具體的形態：**出處是人寫的、而且是簡寫的**。例如原本的 truth 裡寫

```
shipping   mysql   data   shipping/…/JpaConfig.java:16 jdbc:mysql://mysql/cities
account-service   account-mongodb   data   shared/account-service.yml
```

第一條有 `…`、第二條的 `shared/account-service.yml` 在 repo 裡實際位於 `config/src/main/resources/shared/account-service.yml`。要複驗的人得先自己找檔案。

這一輪用程式對每一條 evidence 檢查「這個路徑在 repo 裡打得開嗎」，並把能唯一定位的補成完整相對路徑（同名檔案用邊的**來源服務**消歧，例如 piggymetrics 的 `bootstrap.yml` → `<來源服務>/src/main/resources/bootstrap.yml`；`shared/*.yml` → `config/src/main/resources/shared/*.yml`）。

| 專案 | 有檔案出處的條數 | 現在可直接打開 | 備註 |
|---|---|---|---|
| robot-shop | 21 | **21** | — |
| ewolff-k8s | 5 | **5** | — |
| ecommerce | 22 | **22** | 原本 8 條寫「每個服務 application.yml」，改成該邊來源服務自己那一份並註明「每個服務都有同型設定」 |
| piggymetrics | 28 | **28** | — |
| teastore | 2 | **2** | 另有 7 條出處是 `Service.PERSISTENCE` 這種 enum 常數，不是檔案位址（就是工具畫不出來的那組） |
| online-boutique | 2 | **2** | 其餘出處是 README 段落與 manifest 名 |
| bank-of-anthos | 12 | **12** | — |
| **合計** | **92** | **92** | 由 `TruthEvidenceResolvesTest` 斷言，不是人數的 |

**這道檢查抓到兩條真的寫錯的出處**——這正是它存在的理由：

| 原本寫的 | 實際上是 | 怎麼發現的 |
|---|---|---|
| `dispatch/src/main.go`（dispatch → rabbitmq） | `dispatch/main.go` | 路徑打不開，同名檔案唯一 |
| `ratings/html/API.php`（ratings → catalogue） | `ratings/html/src/Service/CatalogueService.php:27`，`sprintf('%s/product/%s', $this->catalogueUrl)`，host 由 `config/services.yaml` 的 `CATALOGUE_URL` 注入 | 路徑打不開，且 repo 裡沒有這個檔名 |

兩條邊本身是對的（工具也畫了 `ratings → catalogue`），錯的是**出處**。在舊的寫法下，這種錯沒有人會發現——複驗的人找不到檔案，通常只會以為自己找錯了。

**還有一類不能變成檔案路徑，照實留著並標明性質**：TeaStore 的 7 條 `loadBalanceRESTOperation(Service.PERSISTENCE, …)`——出處是 Java enum 常數，目標由自家 registry 在執行期解析。這正是 truth 標成 `business` 而工具一條都畫不出來的那組，出處寫成「呼叫點 ＋ enum 常數名」是它能有的最精確形式。

這道檢查現在是 `TruthEvidenceResolvesTest`，**不可打開的出處會讓測試失敗**（指令見 §5），所以「出處只有我看得懂」這件事以後會被擋下來。

---

## 3. ③ 第二標註者（待機器 B 跑）

`SecondAnnotatorTest`：把**一個人類標註者會讀的東西**交給一個獨立的標註者——部署描述（Compose／k8s manifest／反向代理設定）、README、以及**每一行含位址的原始碼**（`http://`、`jdbc:`、`*_HOST`、`*_ADDR`…，附檔名與行號）——要它輸出同樣格式的邊清單。它**沒有看過** `truth/*.tsv`，也**沒有看過**工具的輸出或 ledger。

輸出 `docs/generalization/second-annotator/<name>.md`：兩人都認為存在的、只有作者有的、只有第二標註者有的，逐條列出，並算 Jaccard 一致度。

**這招的強度要說清楚**（已寫進測試的 javadoc）：

- 它量的是**兩個獨立標註者的一致度**，不是誰對。一致度高只代表作者的 truth 不是個人特有的讀法。
- 第二標註者不是裁判：它讀同一份 repo，會漏也會編，而且和工具用的是同一族模型（方法上並不完全獨立，只是彼此沒看過對方的答案）。
- 所以三招裡**最硬的還是 ①**（別人發表的邊集），③ 是補充。

**狀態**：程式已寫好並可執行，但**本機的 OpenAI key 沒有額度**（`chat 429: You have no credits remaining`），所以還沒有數字。指令在 §5，要在機器 B 上跑。

---

## 4. 措辭怎麼改（論文與簡報一律照這份）

| 原本的說法 | 改成 | 理由 |
|---|---|---|
| ground truth／標準答案 | **參考邊集**（reference edge set）；作者寫的那份叫**「依專案文件與程式碼整理的參考邊集」** | 「標準答案」暗示它是對的；它其實是一份有出處、可複驗的讀法 |
| precision／recall（對作者 truth） | **一致度（agreement）**；分方向講「參考邊集裡工具畫到多少」與「工具多畫了哪些」 | P／R 的語意預設分母是真值 |
| 「程式計分，不是人數的」 | 保留，但加一句「**程式只保證算得沒錯，不保證答案是對的**」 | 這正是老師指出的落差 |
| 「六個專案都驗過了」 | 「六個專案對**作者整理的**參考邊集；另外七個專案對**第三方發表的**參考邊集」 | 兩種強度不同，不能混報 |

`docs/generalization-2026-09-22.md` §4 的表頭與 §6 已照此改寫，並在 §4 前面指到本文件。

---

## 5. 重跑指令

**① 第三方資料集對照**（離線，不需要 API）：

```bash
# 1. clone 並對齊資料集擷取日（每個 repo 的 commit 見 ExternalTruthAgreementTest.CASES）
git clone https://github.com/spring-petclinic/spring-petclinic-microservices.git
cd spring-petclinic-microservices && git checkout $(git rev-list -1 --before=2021-02-26 HEAD)

# 2. 跑靜態探針，輸出到 docs/generalization/external/
mvn -o test -Dtest=GreenfieldProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.repo=/path/to/spring-petclinic-microservices \
  -Dprobe.out=docs/generalization/external/spring-petclinic.mmd

# 3. 計分（讀 external/*.summary.md 與 vendored 的 graphml，寫 external-agreement.md）
mvn -o test -Dtest=ExternalTruthAgreementTest -Dsurefire.failIfNoSpecifiedTests=false
```

計分測試會**斷言** robot-shop／lakeside-mutual／spring-cloud-netflix／tap-and-eat／microservices-book 的資料集邊必須全部畫到，且整體一致度 ≥ 0.95——命名規則若再被改壞，這裡會先失敗。

**② 出處逐條可複驗**（離線，需要 checkout 放在同一個目錄下，目錄名見 `TruthEvidenceResolvesTest.REPOS`）：

```bash
mvn -o test -Dtest=TruthEvidenceResolvesTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtruth.repos=/path/to/checkouts
```

沒有 checkout 的專案會被跳過並列出，所以只 clone 幾個也能用。**任何一條出處打不開就失敗**，訊息會指出是哪一條邊。

**③ 第二標註者**（需要有額度的 API key，機器 B）：

```bash
mvn -o test -Dtest=SecondAnnotatorTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dannotate=true -Dannotate.name=robot-shop -Dannotate.repo=/path/to/robot-shop
```

七個有 truth 的專案各跑一次（`robot-shop`、`bank-of-anthos`、`piggymetrics`、`ecommerce`、`ewolff-k8s`、`teastore`、`online-boutique`）。每次一通長 prompt，`gpt-4.1-mini` 等級的模型約數美分。跑完把表填進本文件 §3，並逐條裁決「只有第二標註者有」的條目——裁決的結果若顯示 truth 漏了，就改 truth 並在這裡記一筆。

---

## 6. 誠實界線

1. **交集只有 7 個專案，而且偏 Java／Spring。** 資料集的另外 13 個是 C#／Ruby／PHP／Go／多語系，落在工具已知的「無文法語言」邊界；跑它們只會重測那條邊界，不會增加對「命名與合併規則」的驗證強度。
2. **資料集是 2021-02-26 的快照，工具跑的是那天之前的 commit，但資料集本身是更早抓的。** petclinic 漏的 2 條就是這個落差（它的圖有一個 repo 已經移除的模組）。反方向的落差（資料集抓完之後 repo 才加的東西）會出現在「工具另外畫了」那一欄，我們沒有逐條排查。
3. **資料集的邊也可能有錯。** 它是工具產生的（MicroDepGraph），不是人工審核過的真值；`links`／`depends_on` 是宣告而不是呼叫。所以 §1.4「漏的 4 條」是對照差異的解釋，不是對錯判定。
4. **工具多畫的 27 條沒有逐條找第三方出處。** 它們在報告裡只列不評分。
5. **第二標註者還沒有數字**（§3）。
6. **這一輪沒有重跑 2026-09-22 那六個專案的 9/22 checkout。** 回歸是用今天的 checkout 做改動前後比較（同一份程式碼、同一台機器），這對「我的改動有沒有弄壞既有結果」是有效的；對「repo 本身有沒有變」則沒有結論——TeaStore 顯然變了（同一份探針在今天的 HEAD 上畫出 12 條邊，9/22 那次是 1 條），所以 `docs/generalization-2026-09-22.md` §4 的表**仍以 9/22 的 checkout 為準**，沒有改。
