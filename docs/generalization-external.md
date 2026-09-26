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
> | **③** | **第二標註者**：一個沒看過 truth、也沒看過工具輸出的 LLM 獨立標一份，算逐條一致度 | 作者只裁決不一致的條目 | `SecondAnnotatorTest` → `docs/generalization/second-annotator/<name>.md`（**7／7 已跑**，見 §3.5 的裁決） |
>
> 措辭也改：不再寫 “ground truth”／「標準答案」，一律寫**「參考邊集」**，指標寫**「一致度（agreement）」**而不是準確率。§4 說明每個詞怎麼換。
>
### 結論（要拿去簡報與論文的那幾句）

1. **工具的泛化結果不再只靠作者的標註**：在一份**完全不是我們寫的**參考邊集上（第三方發表的 7 個專案、95 條邊），工具畫到 **91 條＝0.96**，漏的 4 條逐條可解釋（2 條是 repo 演進、2 條是已知邊界）。這個數字可以單獨拿去報告，因為標準答案不是我給的。
2. **作者標註目前沒有發現偏差，但只驗過一個專案**：唯一兩邊都有的 `robot-shop`，第三方的 12 條邊**全部**落在作者 truth 的 21 條裡，漏標 0、矛盾 0（§1.7）。作者多標的 9 條是第三方方法看不到的東西（程式碼呼叫、外部主機、nginx 路由），每條都有打得開的出處。**樣本 1，所以是「沒發現偏差」不是「證明沒有偏差」。**
3. **七個專案跑完，獨立標註者一條邊都沒有推翻**：40 條「只有標註者有」的邊逐條裁決後，是命名問題（**17 條**，其中 ewolff-k8s 那 5 條讓 Jaccard 變成 0.00 純粹是模組名 vs 部署名）、部署變體（5 條其實在 truth 裡，比對程式的 bug，已修）、標註者的判斷錯誤（**18 條**：把 H2 in-memory 與不存在的 mongodb 當成資料庫、把宣告未使用的常數當呼叫、**三條方向反轉**）。**沒有一條是作者漏標。** 一致度從 0.00 到 0.92，而數字本身不是產出（§3.5）。
4. **但它精準指出了 truth 裡最難複驗的兩組**：online-boutique 的 9 條出處是**一張 PNG 架構圖**——純文字的第三方無法複驗，而標註者從 README 文字推的結果有 3 條方向是反的；TeaStore 的 7 條出處是 **enum 常數的引用次數**，可複驗但要先讀懂 registry 分派機制。這兩組共 16 條（佔七個專案 139 條非變體參考邊的 11.5%），證據性質與其餘 123 條不同，論文要分開講（§3.6）。這是這個方法真正的價值：它不推翻邊，它指出哪些出處撐不起第三方複驗。
5. **作者標註的地位降級、並且可被別人檢查**：不再稱它為 ground truth／標準答案，改稱「參考邊集」，指標改稱「一致度」；每一條出處都必須是第三方**打得開**、而且**內容真的在裡面**的 `路徑:行`，由 `TruthEvidenceResolvesTest` 斷言（**113／113**）。三道檢查累計抓到 **3 條寫錯的出處 ＋ 3 條不精確的出處**，而**沒有一條是邊本身錯**——這個對比本身就是結論的一部分：作者對「有哪些依賴」的判斷站得住，對「證據在哪一行」的紀錄則需要程式看著。

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
| **合計** | **92** | **92** | 由 `TruthEvidenceResolvesTest` 斷言，不是人數的（第二標註者那一輪之後補到 **113／113**，見 §3.8） |

**這道檢查抓到兩條真的寫錯的出處**——這正是它存在的理由：

| 原本寫的 | 實際上是 | 怎麼發現的 |
|---|---|---|
| `dispatch/src/main.go`（dispatch → rabbitmq） | `dispatch/main.go` | 路徑打不開，同名檔案唯一 |
| `ratings/html/API.php`（ratings → catalogue） | `ratings/html/src/Service/CatalogueService.php:27`，`sprintf('%s/product/%s', $this->catalogueUrl)`，host 由 `config/services.yaml` 的 `CATALOGUE_URL` 注入 | 路徑打不開，且 repo 裡沒有這個檔名 |

兩條邊本身是對的（工具也畫了 `ratings → catalogue`），錯的是**出處**。在舊的寫法下，這種錯沒有人會發現——複驗的人找不到檔案，通常只會以為自己找錯了。

**還有一類不能變成檔案路徑，照實留著並標明性質**：TeaStore 的 7 條 `loadBalanceRESTOperation(Service.PERSISTENCE, …)`——出處是 Java enum 常數，目標由自家 registry 在執行期解析。這正是 truth 標成 `business` 而工具一條都畫不出來的那組，出處寫成「呼叫點 ＋ enum 常數名」是它能有的最精確形式。

這道檢查現在是 `TruthEvidenceResolvesTest`，**不可打開的出處會讓測試失敗**（指令見 §5），所以「出處只有我看得懂」這件事以後會被擋下來。

---

## 3. ③ 第二標註者（七個專案跑完）

`SecondAnnotatorTest`：把**一個人類標註者會讀的東西**交給一個獨立的標註者——部署描述（Compose／k8s manifest／反向代理設定）、README、以及**每一行含位址的原始碼**（`http://`、`jdbc:`、`*_HOST`、`*_ADDR`…，附檔名與行號）——要它輸出同樣格式的邊清單。它**沒有看過** `truth/*.tsv`，也**沒有看過**工具的輸出或 ledger。

輸出 `docs/generalization/second-annotator/<name>.md`：兩人都認為存在的、只有作者有的、只有第二標註者有的，逐條列出，並算 Jaccard 一致度。

### 3.1 結果：robot-shop（2026-09-26 機器 B，`gpt-4.1-mini`，temperature 0，1 次呼叫、prompt 4 964 token）

> **材料版本：舊**（§3.4 修過材料收集之後要重跑；robot-shop 的證據多在 compose 與程式碼行裡，受影響較小，但為了各輪可比仍要重跑）

| | 條數 |
|---|---|
| 兩人都認為存在 | **18** |
| 只有作者的參考邊集有 | 3 |
| **只有第二標註者有** | **0** |
| 一致度（Jaccard） | **0.86** |

**最重要的那一格是 0**：獨立標註者**沒有指出任何一條作者漏標的邊**。前面 §1.7 用第三方資料集查的是同一件事（漏標 0），兩個互相獨立的檢查指向同一個結論。

**三條差異逐條裁決（結論：三條都是標註者漏抓，作者的 truth 沒有一條被質疑）**

| 只有作者有的邊 | 作者的出處 | 裁決 |
|---|---|---|
| `web → cart` | `web/default.conf.template:69` `proxy_pass http://${CART_HOST}:8080/` | **truth 對**。nginx 設定裡有 6 條 `proxy_pass`（catalogue／user／cart／shipping／payment／ratings），標註者只從 compose 抓到 4 條 `web → *`，**沒有讀反向代理的設定**——雖然材料裡給了它。工具抓到了這兩條（標 `inferred`，因為 host 是 `${CART_HOST}` 佔位符） |
| `web → ratings` | `web/default.conf.template:81` | 同上 |
| `payment → paypal.com` | `payment/payment.py:26` `PAYMENT_GATEWAY = os.getenv('PAYMENT_GATEWAY', 'https://paypal.com/')` | **truth 對**，但這一條是**真正的標註者分歧**而不是單純漏看：標註者引用了同一個檔案的第 24、25 行（`payment → cart`、`payment → user`），所以它**看得到**第 26 行，卻沒有把外部支付閘道算成依賴。「呼叫外部 SaaS 算不算系統的依賴」是判斷問題，不是事實問題 |

**兩個附帶發現，都值得寫進論文**

1. **標註者的行號全部查得住**，這和文獻對 LLM 引用的預期（§5.4：連結有效但事實正確率只有 39–77%）相反——在「材料就在 prompt 裡、而且每一行都附了檔名與行號」的條件下，它沒有編造出處。這反過來支撐 ③「事實由程式寫」的邊界：**把事實與位置一起餵給模型，它的轉述是可靠的；要它自己去找，才是不可靠的。**
2. **兩個標註者對「出處該指哪裡」有系統性偏好差異。** 同一條 `cart → catalogue`，標註者引 `cart/server.js:30`（`catalogueHost = process.env.CATALOGUE_HOST || 'catalogue'`，host 是**從哪來**的），作者引 `:362`（`request('http://' + catalogueHost …)`，**哪裡發出呼叫**）。`ratings → mysql`、`payment → cart`、`user → redis` 都是同一個形態。兩邊都可複驗，但這說明「逐條可複驗」還不足以消除分歧——**出處的慣例也要寫下來**（`truth/README.md` 已補：出處指呼叫點，host 的來源寫在同一格的說明裡）。

**方法論紅線**：一致度 0.86 的三條缺口都是標註者漏抓，看起來只要在 prompt 裡加一句「記得讀反向代理設定」就會變成 1.00——**不可以這樣做**。那是往「讓標註者同意作者」的方向優化，第二標註者就不再獨立，這個檢查也就失去意義。其餘六個專案用**完全相同的 prompt** 跑。

### 3.2 結果：bank-of-anthos（同一天、同一個設定）

> **材料版本：舊**（同上，要重跑）

| | 條數 |
|---|---|
| 兩人都認為存在 | **10** |
| 只有作者的參考邊集有 | 2 |
| **只有第二標註者有** | **0** |
| 一致度（Jaccard） | **0.83** |

**兩條差異逐條裁決**

| 只有作者有的邊 | 裁決 |
|---|---|
| `ledgerwriter → ledger-db` | **truth 對，而且這是標註者最說不過去的一次漏抓。** 它標了 `balancereader → ledger-db`（引 `BalanceReaderApplication.java:49`）與 `transactionhistory → ledger-db`（同型的 `:49`），而 `LedgerWriterApplication.java:51` 有**完全一樣的** `SPRING_DATASOURCE_URL`，ledgerwriter 也有 `@Repository TransactionRepository extends CrudRepository`。三個結構相同的服務，它標了兩個、漏了一個 |
| `loadgenerator → frontend` | **邊對，但作者的出處寫錯了**（見下），標註者也沒標。這條邊工具也畫不出來（host 在 Dockerfile 的 `ENTRYPOINT` 命令列參數裡） |

**這一輪最有價值的發現：LLM 標註者的漏抓是隨機的，不是系統性的。** robot-shop 那次的兩條缺口有規則可循（「它不讀反向代理設定」），所以可以預測、可以在報告裡交代。但這一次它對三個**完全同型**的服務做了不同處理——這種漏抓無法用任何規則描述，也就無法預測。對「用 LLM 當第二標註者」這件事來說這是負面證據，要寫進論文：**它可以用來檢查「作者有沒有標出別人看得到的東西」（這個方向它做得很好，兩個專案都是 0），但不能用來反推「作者漏了什麼」，因為它自己的漏抓沒有規律。**

### 3.3 第二標註者順帶抓到第三條寫錯的出處

`loadgenerator → frontend` 原本寫 `src/loadgenerator/locustfile.py（FRONTEND_ADDR）`。檔案存在，所以 §2 的檢查放過了它——但 **`locustfile.py` 裡沒有 `FRONTEND_ADDR`**：它在 `src/loadgenerator/Dockerfile:49` 的 `ENTRYPOINT locust --host="http://${FRONTEND_ADDR}"`，值在 `src/loadgenerator/k8s/base/loadgenerator.yaml:50`。已改。

於是 `TruthEvidenceResolvesTest` 也跟著強化：**除了檔案要打得開，evidence 裡提到的程式碼識別字（`FRONTEND_ADDR`、`proxy_pass`、`redis.createClient` 這種）必須真的出現在那個檔案裡**。只讀「路徑之後、第一個分隔符之前」那一段，因為一格 evidence 常常接著指第二個來源，而第二個來源的識別字不是對第一個檔案的主張。

強化之後又抓出 3 條**不精確**（不是指錯檔案）的出處：piggymetrics 的 `auth-service`／`gateway`／`monitoring` → `config` 原本寫「`bootstrap.yml` / docker-compose depends_on」，而 compose 裡這三個服務**根本沒有 `depends_on`**；真正的出處是 `bootstrap.yml:6` 的 `spring.cloud.config.uri: http://config:8888`（更精確，因為它直接給了 host）。已改，92／92 仍然全過。

**累計**：這三道檢查（第三方邊集、出處可打開、出處內容相符）總共抓到 **3 條寫錯的出處 ＋ 3 條不精確的出處**，全部來自作者手寫的那 92 條。這個數字本身就是對「作者標註需要外部檢查」最好的論證——**而且每一條被抓到的都是出處，沒有一條是邊本身**。

### 3.4 piggymetrics 暴露了這個方法的一個缺陷：材料不足（這一輪的數字作廢）

| | 條數 |
|---|---|
| 兩人都認為存在 | 10 |
| 只有作者的參考邊集有 | **24** |
| 只有第二標註者有 | 0 |
| 一致度（Jaccard） | **0.29** |

**這 0.29 不是「作者的 truth 可疑」，也不是「標註者不行」，是我們給它的材料少於作者看得到的東西。** 證據很直接：標註者標的 10 條**全部**來自 `docker-compose.yml`，而作者那 24 條的出處是三類材料收集規則漏掉的檔案：

| 作者的出處類型 | 條數 | 為什麼沒給標註者看 |
|---|---|---|
| `config/src/main/resources/shared/<service>.yml`（Spring Cloud Config 倉庫）：`zuul.routes`、`accessTokenUri`、`rates.url`、`spring.mail.host`、`eureka.client.serviceUrl.defaultZone` | 16 | 材料只收檔名是 `application*`／`bootstrap*` 或路徑含 `k8s` 的 yml；`shared/gateway.yml` 一條都不符合 |
| `<service>/pom.xml` 的 `hystrix-stream`／`bus-amqp`／`stream-rabbit` starter（佇列的唯一證據） | 4 | 材料根本沒收任何依賴宣告檔 |
| Feign client 的 `.java`（`@FeignClient(name = "auth-service")`） | 3 | 程式碼行的篩選條件要有 `http://`、`_HOST` 之類，annotation 裡沒有 |

諷刺的是**工具自己看得到這三類**（2026-09-22 的規則 3、5、9 就是為它們加的），所以這一輪的對照是**對標註者不公平**，而不是對作者寬鬆。

**修法（改材料，不改 prompt）**：收**所有** `.yml`／`.yaml`（工具就是這樣掃的）、加收依賴宣告檔（`pom.xml`、`package.json`、`requirements.txt`、`go.mod`、`composer.json`、`build.gradle`）、程式碼行的篩選加上 `@FeignClient`／`RestTemplate`／`WebClient`／`getenv`／`process.env`／`accessTokenUri`／`defaultZone` 等呼叫線索，預算從 58 KB 提到 90 KB。piggymetrics 的材料因此從 4 個檔案變成 **28 個**（含 9 份 `shared/*.yml` 與 5 份 `pom.xml`），45 898 字。

**這是看過結果之後才改的，所以要說清楚兩件事**：
1. 改的理由是**公平性**（材料明顯少於作者與工具可見的範圍），不是分數。判準是「作者的出處類型有沒有被材料涵蓋」，這個判準不看一致度。
2. **三個已跑過的專案（robot-shop、bank-of-anthos、piggymetrics）都用新材料重跑了**（結果在 §3.5），否則各輪的材料不一致、數字不可比。§3.1、§3.2 的數字是**舊材料**下的第一輪，保留當紀錄；本節的 0.29 作廢，piggymetrics 在新材料下是 0.69。

**附帶的對照發現（這一輪最值得寫進論文的東西）**：標註者在前兩輪的行號**全部查得住**，這一輪卻把 Compose 的出處寫成 `docker-compose.yml (lines: 26-33)` 這種**互相重疊、明顯是猜的區間**。差別在於：前兩輪它引用的是材料裡**逐行附了行號**的程式碼行，這一輪引用的是整份貼上、**沒有行號**的 Compose 檔。

> **同一個模型、同一次呼叫裡：位置給它，它忠實引用；位置不給它，它就編。** 這正是 pattern ③「事實由程式寫」的邊界條件，而且是在自家資料上看到的對照組。修法順帶處理了它：現在每份完整檔案都逐行附行號。

### 3.5 七個專案跑完（2026-09-26 機器 B，修好的材料）

| 專案 | 兩人都有 | 只有作者 | 只有標註者 | Jaccard | 舊材料 |
|---|---|---|---|---|---|
| bank-of-anthos | 11 | 1 | 0 | **0.92** | 0.83 |
| robot-shop | 17 | 4 | 1 | 0.77 | 0.86 |
| piggymetrics | 25 | 9 | 2 | **0.69** | 0.29 |
| online-boutique | 7 | 9 | 3 | 0.37 | — |
| teastore | 6 | 7 | **1** | 0.43 | — |
| ecommerce | 13 | 25 | 23 | 0.21 | — |
| ewolff-k8s | 0 | 5 | 5 | **0.00** | — |

材料修正對 piggymetrics 的效果正如預期（0.29 → 0.69）。但**一致度本身不是這一節的產出**——逐條裁決才是，而裁決的結論是：

> **40 條「只有標註者有」的邊裡，沒有一條是作者漏標的。** 它們分成命名問題（17 條）、部署變體（5 條，其實在 truth 裡）、以及標註者的判斷錯誤（18 條）。17 ＋ 5 ＋ 18 ＝ 40。而 **60 條**「只有作者有」的邊裡，**有兩組指出了 truth 真正的弱點**（§3.6）。

**A. 命名問題（17 條）——最大的一類，而且映射了工具自己犯過的錯**

| 專案 | 條數 | 標註者用的名字 | truth 用的名字 | 誰對 |
|---|---|---|---|---|
| ewolff-k8s | 5 | `microservice-kubernetes-demo-order` | `order` | **truth 對**。標註者用了 Maven 模組名——**正是工具在規則 11 之前犯的同一個錯**。而 prompt 明確寫了「用部署描述的名字，不要用原始碼目錄名」，它還是踩了 |
| ecommerce | 10 | `user-service-container` 等 | `user-service` | **truth 對**。`*-container` 是 compose 的服務名本身（`container_name`），標註者把服務自己當成它的依賴。工具的規則 7 就是處理這個後綴的 |
| robot-shop | 1 | `payment-gateway` | `paypal.com` | **truth 對**，但標註者**其實標到了那條邊**，只是用環境變數名 `PAYMENT_GATEWAY` 當節點名而不是 host |
| piggymetrics | 1 | `external` | `api.exchangeratesapi.io` | 同上，它標到了那條外部依賴，名字寫成 `external` |
| 小計 | **17** | | | |

ewolff-k8s 的 0.00 因此是**假象**：五條邊**完全相同**，一條都沒有分歧。這一格在論文裡要特別講：**「模組名還是部署名」這個歧義強到人、模型、工具都會踩**，它不是工具的疏忽。

**B. 部署變體（5 條）——比對程式的 bug，不是分歧**

TeaStore 的 `teastore-* → teastore-kieker-rabbitmq` 五條**在 truth 裡**，標成 `variant`（只有 Kieker 追蹤變體才有的邊）。比對程式為了計分把 variant 整批排除，結果標註者標了它們就被算成「只有標註者有」。已修：現在單獨列成「標註者也標了、作者標為部署變體」一節，不計分也不算分歧。**修正後 TeaStore 的分歧只剩 1 條**（一個自環 `kieker-rabbitmq → kieker-rabbitmq`，標註者的錯），Jaccard 從 0.32 升到 **0.43**。

比對規則改了而模型的回答沒有改，所以**不必重新呼叫 API**：`SecondAnnotatorTest` 多了一個離線重算入口（`-Dannotate.replay=true`），讀 `second-annotator/*.md` 裡存下來的原始回答、用現行規則重新比對並改寫報告。七個專案重算後只有 TeaStore 的數字變動，其餘六個逐格相同——這同時驗證了重算與原本的比對一致。

**C. 標註者的判斷錯誤（18 條）**

| 錯誤類型 | 條數 | 內容 |
|---|---|---|
| **照常識類推出不存在的資料庫** | 7 | ecommerce 的 `<svc> → <svc>-database` 6 條：出處（`application-dev.yml:13`）是真的，但那一行是 `jdbc:h2:mem:ecommerce_dev_db`——**H2 記憶體內資料庫**，程序內、不是獨立元件；而 `user-service-database` 這個名字 repo 裡根本不存在。piggymetrics 的 `registry → registry-mongodb` 1 條更直接：它引 `shared/registry.yml:1`，而那個檔案**只有兩行**（`server: port: 8761`），repo 裡也沒有 `registry-mongodb` 這個字串——純粹照「其他服務都有一個 mongodb」類推。prompt 明文寫了「不要從這類系統通常有什麼去推測」 |
| **宣告當成使用** | 7 | ecommerce 的 `user-service → product-service` 等。來源是 `AppConstant.java:20` 的 `PRODUCT_SERVICE_HOST = "http://PRODUCT-SERVICE/…"`——**每個服務都複製一份含全部 URL 的常數檔，而多數沒人引用**。這正是 2026-09-22 規則 9（`markUnreferencedUrlConstants`）處理的東西：工具與作者都不畫，標註者畫了。**這一組是規則 9 的獨立驗證：那些邊不是工具偷懶少畫，是真的不該畫** |
| **方向搞反** | 3 | online-boutique 的 `emailservice → checkoutservice`、`paymentservice → checkoutservice`、`adservice → frontend`——三條的真實方向都相反。這是最嚴重的一類錯誤（方向錯比漏抓更誤導），而且它只發生在「出處是架構圖、標註者只能從 README 文字推」的那個專案 |
| 自環 | 1 | TeaStore 的 `kieker-rabbitmq → kieker-rabbitmq` |

### 3.6 這一輪真正的產出：truth 裡最難複驗的兩組

60 條「只有作者有」的邊裡，大部分是標註者漏抓（沒讀 nginx、沒把共用設定攤到每個 client、沒標控制面）。但有**兩組**的性質不同——它們是**作者的出處本身難以被第三方複驗**：

**① online-boutique 的 9 條，出處是一張 PNG 架構圖。**

`frontend → adservice`／`recommendationservice`／`productcatalogservice`／`cartservice`／`shippingservice`／`currencyservice`／`checkoutservice`、`cartservice → redis-cart`、`loadgenerator → frontend`——truth 的 evidence 欄寫的是「README `docs/img/architecture-diagram.png`（frontend → ad）」或「架構圖」。

**這 9 條在文字上無法複驗**：標註者看不到圖片（任何純文字的第三方也看不到），而 README 的文字沒有把這些邊列出來。更糟的是，它從文字推測的結果**有 3 條方向是反的**（§3.5 C）——正好說明「沒有可讀的出處時會發生什麼」。

要補的做法：這些邊多半有 manifest 的證據（`cartservice → redis-cart` 就有 `kubernetes-manifests/cartservice.yaml` 的 `REDIS_ADDR`，truth 已經寫了），其餘的 `frontend → *` 可以改引 `src/frontend/main.go` 的 `*_SERVICE_ADDR` 環境變數宣告與 manifest 的 env。**「架構圖」可以留著當補充，但不能是唯一的出處。**

**② TeaStore 的 7 條，出處是 enum 常數的引用次數。**

`teastore-webui → teastore-persistence`（出處：「webui：`Service.PERSISTENCE` 引用 21 處」）這一組，目標是 Java enum 常數，經自家 registry 在執行期解析。標註者**一條都沒標**——材料裡沒有任何 host 字串可讀，這完全符合預期（`docs/generalization-2026-09-22.md` §6 早就把它列為「registry/enum 動態分派」這個邊界）。

**這是 truth 裡證據最弱的一組**：它可複驗（grep `Service.PERSISTENCE` 數引用次數），但複驗的人要先讀懂 `loadBalanceRESTOperation` 的分派機制才知道那個 enum 代表一個呼叫目標。論文要這樣寫：**這 7 條的等級不等於其他條**，它們靠的是對框架機制的理解，而不是一行可以指出來的字串。

**兩組加起來 16 條**——online-boutique 的 15／16 條與 TeaStore 的 7／13 條（非變體），佔七個專案 139 條非變體參考邊的 **11.5%**。 這就是「第二標註者」這個方法的真正價值——它一條邊都沒推翻，但它精準指出了**哪些邊的出處撐不起第三方複驗**。

### 3.7 這個方法本身的限制（跑完七個之後才看清楚）

1. **標註者看到的是摘錄，作者看到的是整個 repo。** 這個不對等無法完全消除（prompt 有預算上限），所以「只有作者有」永遠會包含一部分「材料沒給它看」。§3.4 修掉的是明顯的缺口，剩下的是本質的。
2. **它看不到圖片。** online-boutique 那 9 條就是這個限制撞上 truth 的弱點。
3. **它的錯誤類型比人類標註者更奇怪**：把服務自己當依賴、把環境變數名當節點名、把 H2 in-memory 當外部資料庫、方向反轉。人類標註者大概不會犯前三種，但可能犯第四種。
4. **它對命名慣例的遵守很不穩定**：prompt 明確要求用部署名，七個專案裡有兩個（ewolff-k8s、ecommerce）整批用錯。
5. 因此**這個方法能回答的是一個方向的問題**：「作者有沒有標出別人也看得到的東西」（七個專案的答案都是有）。它**不能**用來反推作者漏了什麼——它自己的漏抓與錯誤太多。

### 3.8 依這些發現改掉的 truth（2026-09-26 當天做完）

**① online-boutique：15 條「架構圖」出處全部換成可複驗的兩處。** 原本 16 條裡有 15 條的 evidence 只寫「架構圖」；現在每一條都指到**程式碼讀哪個環境變數**與**manifest 給它什麼值**，例如：

```
frontend  adservice  business
  src/frontend/main.go:138 mustMapEnv(&svc.adSvcAddr, "AD_SERVICE_ADDR")；
  值在 kubernetes-manifests/frontend.yaml:82 value: "adservice:9555"
```

`checkoutservice` 的 6 條同樣改成 `src/checkoutservice/main.go:111-116` ＋ `checkoutservice.yaml:57-67`。架構圖不再是任何一條的唯一出處。有檔案出處的條數從 **2 → 16**。

**② TeaStore：7 條 enum 邊的出處改成真正的呼叫點。** 原本寫「`Service.PERSISTENCE` 引用 21 處」，現在指到**第一個實際呼叫點**（例如 `DataBaseActionServlet.java:67 loadBalanceRESTOperation(Service.PERSISTENCE`）並保留引用處數。

查證過程還修正了一件事：`teastore-webui → teastore-auth` 的兩處 `Service.AUTH` 在 webui 自己的程式碼裡**都只是狀態頁的 `getServersForService`**，真正的業務呼叫發生在**共用模組** `utilities/tools.descartes.teastore.registryclient/.../LoadBalancedStoreOperations.java:56`。這條邊要跨兩層才看得出來（webui 呼叫 utility 的方法 → utility 對 `Service.AUTH` 發 REST），出處已照實寫成兩段。**這也是靜態層與獨立標註者都看不到它的真正原因**，比「enum 動態分派」的說法更精確。有檔案出處的條數從 **2 → 9**。

**③ 出處總數**：92 → **113**，全部打得開且內容相符（`TruthEvidenceResolvesTest`）。

**這招的強度要說清楚**（已寫進測試的 javadoc）：

- 它量的是**兩個獨立標註者的一致度**，不是誰對。一致度高只代表作者的 truth 不是個人特有的讀法。
- 第二標註者不是裁判：它讀同一份 repo，會漏也會編，而且和工具用的是同一族模型（方法上並不完全獨立，只是彼此沒看過對方的答案）。
- 所以三招裡**最硬的還是 ①**（別人發表的邊集），③ 是補充。

**狀態**：**七個專案全部跑完**（§3.5），逐條裁決見 §3.5、§3.6。§3.1 與 §3.2 是舊材料下的第一輪，保留當紀錄，指令在 §5，**prompt 一個字都不改**（§3.1 的方法論紅線）。每個專案一通長 prompt，`gpt-4.1-mini` 等級約數美分。

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
5. **第二標註者的材料是摘錄，不是整個 repo**（§3.7），所以「只有作者有」永遠含一部分「材料沒給它看」；它也看不到圖片。七個專案的數字在 §3.5。
6. **這一輪沒有重跑 2026-09-22 那六個專案的 9/22 checkout。** 回歸是用今天的 checkout 做改動前後比較（同一份程式碼、同一台機器），這對「我的改動有沒有弄壞既有結果」是有效的；對「repo 本身有沒有變」則沒有結論——TeaStore 顯然變了（同一份探針在今天的 HEAD 上畫出 12 條邊，9/22 那次是 1 條），所以 `docs/generalization-2026-09-22.md` §4 的表**仍以 9/22 的 checkout 為準**，沒有改。
