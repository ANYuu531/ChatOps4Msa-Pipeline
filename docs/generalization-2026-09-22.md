# 泛化驗證：六個沒看過的專案（2026-09-22）

> 問題：我們在 petclinic / Bank of Anthos / train-ticket 上做的調校，對一個**全新、沒測試過**的專案有沒有效？
> 做法：挑六個形狀各異的開源微服務專案，只跑**靜態 greenfield 路徑**（tree-sitter + 設定檔 + 合併 + 正規化 + 分層；不接叢集、不接 DeepWiki、不叫 LLM），把工具畫的圖對照每個專案自己的架構說明或程式碼事實，算 precision / recall。
> 結論一句：**第一輪六個全部有問題**（一個整張圖崩掉、一個六成邊是假的、一個資料層全漏）；追出來的**十條通用規則**修完後，**Java 專案的業務邊 precision 1.0、recall 0.86–1.0**（程式計分，見 §4）；三個失敗的地方都能指出是哪一種語言或哪一種呼叫型態不在工具的靜態表面上，而且圖上沒有為此編造任何邊；舊的 Bank of Anthos 圖逐條不變、train-ticket 只多不少（§5）。
> **2026-09-25／26 續**：又追加四條規則（§3），**七個專案的 precision 全是 1.00，Bank of Anthos 與 Online Boutique 的 recall 到 1.00**；其中三個失敗裡的一個（Online Boutique 的「薄表面」）追下去發現**歸因錯了一半**（§6 第 3 點、`docs/generalization-external.md` §1.8）。

跑法（離線、不開 port）：

```
mvn -o test -Dtest=GreenfieldProbeTest -Dprobe.repo=/path/to/checkout -Dprobe.out=docs/generalization/<name>.mmd
```

六張圖與逐條邊的摘要都在 `docs/generalization/`。

## 1. 為什麼挑這六個

| 專案 | 語言 | 部署描述 | 之前沒碰過的形狀 |
|---|---|---|---|
| [ewolff/microservice-kubernetes](https://github.com/ewolff/microservice-kubernetes) | Java (Spring Boot) | 單一 k8s yaml | Apache httpd 反向代理當入口；呼叫用 `String.format("http://%s:%s/…")`；host 來自 `@Value("${…:catalog}")` 預設值；Maven 模組名 ≠ 部署名 |
| [SelimHorri/ecommerce-microservice-backend-app](https://github.com/SelimHorri/ecommerce-microservice-backend-app) | Java (Spring Cloud) | `compose.yml`（Compose 規格新檔名） | Eureka id 當 host（`http://USER-SERVICE/…`）；Feign 帶 `contextId`；**每個服務都複製一份含全部 URL 的常數檔** |
| [sqshq/piggymetrics](https://github.com/sqshq/piggymetrics) | Java (Spring Cloud) | docker-compose | Spring Cloud Config 倉庫版面（`config/…/shared/<service>.yml` + 共用 `application.yml`）；每服務一個 MongoDB 以 `.host` 裸值宣告；Feign `url="${rates.url}"` |
| [DescartesResearch/TeaStore](https://github.com/DescartesResearch/TeaStore) | Java (Jakarta EE，非 Spring) | k8s yaml（多變體） | 反向網域模組名 `tools.descartes.teastore.webui`；**呼叫透過自家 registry + enum 動態分派**；`DB_HOST`/`DB_PORT` 分開兩個 env |
| [GoogleCloudPlatform/microservices-demo](https://github.com/GoogleCloudPlatform/microservices-demo)（Online Boutique） | Go/C#/Node/Python/Java | k8s yaml + kustomize | gRPC 用 `*_SERVICE_ADDR` env；**11 個服務只有 4 個是工具有文法的語言** |
| [instana/robot-shop](https://github.com/instana/robot-shop) | Node/PHP/Go/Python/Java | Helm（模板不可解析）+ docker-compose | nginx `proxy_pass http://${CATALOGUE_HOST}`；Python f-string host `http://{user}:8080`；Helm 模板 |

## 2. 第一輪結果（修改前）：六個全部有問題

| 專案 | 節點/邊 | 症狀 |
|---|---|---|
| ewolff | 5 / **0** | 節點叫 `microservice-kubernetes-demo-catalog` 不叫 `catalog`；多一個聚合 pom 的幽靈節點；`http://%s:%s/catalog/` 解不出來；Apache 的三條路由完全看不到 |
| ecommerce | 23 / 56 | 12 個 `*clientservice` 幽靈節點（Feign `contextId`）；`localhost` 成節點；**25 條邊來自沒人引用的常數檔** |
| piggymetrics | 11 / 14 | **四個 MongoDB 一條邊都沒有**（設定在 config 倉庫的 `shared/<service>.yml`，工具只讀 `application*.yml`）；`registry → auth-service` 等邊掛在 config server 名下；`rates-client` 幽靈節點 |
| TeaStore | **3 / 1** | 五個服務全部塌成一個叫 `tools` 的節點（模組名以 `.` 分隔，被當成 DNS 後綴切掉）；JPA 標記零命中（`@Entity` 的 pattern 放在 Spring pack，Jakarta EE 專案不載入） |
| Online Boutique | 12 / 1 | 幽靈節點 `src`（`src/cartservice/src/Dockerfile`）；cartservice 不存在所以 `redis-cart` 邊畫不出來 |
| robot-shop | 15 / 14 | `cart → redis` 等邊指向**不存在的節點**（compose depends_on 只加來源不加目標）；`web` 的六條前門路由看不到 |

## 3. 追出來的通用規則（沒有一條寫死專案名）

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 1 | **manifest 的 workload 名是 greenfield 詞彙表**：Deployment/StatefulSet/DaemonSet/Job 的 `metadata.name` 進節點表；模組目錄以完全相同或 `-` 字尾對上 workload；對不上的模組（library、docker base image）不預先當節點，有邊碰到才出現 | ewolff、TeaStore、Online Boutique | `ConfigExtractor`（`k8s-workload`）、`CodeGraphMerger.indexMeta` |
| 2 | **模組目錄名正規化**：`tools.descartes.teastore.webui` → `tools-descartes-teastore-webui`；`src/`、`app/` 這類包裝目錄往上爬；`*-tests`/`e2e` 不是服務；包含 ≥2 個子模組的父目錄是聚合、不是服務 | TeaStore、Online Boutique、ewolff | `ServiceRootScanner` |
| 3 | **Spring Cloud Config 倉庫版面**：不叫 `application` 的 Spring 形狀 yml 也讀；檔名對到服務就歸該服務；旁邊的共用 `application.yml` 攤到每個 config client（bootstrap 有 `spring.cloud.config.uri` 的服務） | piggymetrics | `ConfigExtractor.looksLikeSpringConfig`、`CodeGraphMerger.indexConfig` |
| 4 | **佔位符解析擴充**：`${key}` 先查 env 表、再查屬性表（所有設定檔的 key→值）、再用 `${key:default}` 的預設值；最後才用**變數名本身**（`${CATALOGUE_HOST}` → catalogue），這條標成 `inferred` | piggymetrics、ewolff、robot-shop | `CodeGraphMerger.substitutePlaceholders` |
| 5 | **裸主機名也是目標**：`spring.data.mongodb.host: account-mongodb`、`zuul.routes.x.serviceId`、k8s/compose 的 `DB_HOST: teastore-db`（沒有 port 也算，因為變數名說了它是 host） | piggymetrics、TeaStore、robot-shop | `ConfigExtractor.addressHost`、`syncTarget` |
| 6 | **反向代理設定是入口的邊表**：Apache `ProxyPass`、nginx `proxy_pass` 的上游進 `url` 區 | ewolff、robot-shop | `ConfigExtractor.extractReverseProxy` |
| 7 | **compose 是佈線不是某服務的設定**：`compose.y*ml` 也認；`services.<x>.environment` 變成 env 表與 workload-env（同 k8s）；不再攤平成 config 列；depends_on 目標也要建節點；compose 名對 workload 用 `-container` 去尾或唯一字尾對應（`db` → `teastore-db`） | TeaStore、robot-shop、ecommerce | `ConfigExtractor.extractComposeEnvironment`、`matchNodeLoose` |
| 8 | **不是目標的東西不畫**：`localhost`/`127.0.0.1` 永遠不是節點；Feign 的 `contextId`/`path`/`fallback` 是 bean 佈線；Feign 宣告了 `url` 時 `name` 只是 bean id；值不是位址的 config 列不算「解不開」（不丟給 LLM） | ecommerce、piggymetrics、TeaStore | `CodeGraphMerger.mergeOne` |
| 9 | **宣告不等於使用**：`static final String X = "http://…"` 的常數若在自己模組內沒有任何引用，該 URL 不畫邊（extractor 標 `unreferenced`）；`@Entity/@Table` 搬到 java-generic pack（JPA 是 Java 標準不是 Spring）；JAX-RS `@Path` 當 http-server | ecommerce、TeaStore | `TreeSitterExtractor.markUnreferencedUrlConstants`、`java-generic.scm` |

另外把**格式字串 host** 補齊：`http://%s:%s/catalog/` 走既有的「URL 路徑點名服務」規則；`http://{user}:8080/…` 的變數名本身對上服務就用它。

**2026-09-25／26 又追加四條**（前三條由第三方資料集的對照逼出來，第 14 條由「Online Boutique 為什麼只有 0.13」的追查逼出來；細節與前後數字在 `docs/generalization-external.md` §1.6、§1.8）：

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 11 | **Compose 的 `services` key 也是部署詞彙表**，與 k8s workload 同級，但**有 manifest 時完全不看 compose 名**（避免同一服務出現兩種拼法） | spring-petclinic、ewolff/microservice、Tap-And-Eat、spring-cloud-microservice | `ConfigExtractor.extractComposeServices`（新 `compose-service` 區段）、`CodeGraphMerger.indexMeta` |
| 12 | **也認 Compose 第 1 版檔案格式**（沒有 `services:`，頂層即服務，要有 `image`／`build` 才算）與 **`links:`**（v1 宣告依賴的方式，`服務:別名` 取冒號前） | Tap-And-Eat、spring-cloud-microservice（皆 2017 年的專案） | `ConfigExtractor.composeServices`、`extractComposeDependsOn` |
| 13 | **XML namespace 不是位址**：URL 字面值那一行或前兩行出現 `namespace`／`schemaLocation`／`xmlns`／`soapAction` 時不畫邊 | LakesideMutual（`@XmlSchema`、`setTargetNamespace`、`SoapActionCallback` 三種形態，畫出兩個沒人呼叫的外部主機） | `TreeSitterExtractor.dropNonAddressUrls` |
| 14 | **workload 自己 env 裡的服務位址是宣告，畫成 `inferred`**（點線）；**共用 ConfigMap 注入的仍然不畫**（它被發給不呼叫它們的 workload，只有程式碼層看得到誰真的用）。位址對節點改用寬鬆比對（manifest 常注入短名 `registry`，workload 叫 `teastore-registry`） | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | `CodeGraphMerger.mergeWorkloadWiring` |

**2026-10-26 又追加九條（15–23）**，起因是老師要求「程式碼部分要優化，P 和 R 都要提升」：先對七個專案的漏邊與錯邊逐條追到形狀，再拿一個**沒看過的私有 Istio polyrepo**（AICS，13 個 repo，Python）當對照；每條都有單元測試（`GreenfieldGeneralizationTest` 24 個、`ExtractionGeneralizationTest` 12 個），改完 Bank of Anthos 與 train-ticket 逐邊不變、MicroDepGraph 一致度維持 93／95。

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 15 | **loopback 位址以 port 讀回擁有者**：client 宣告裡的 `localhost:PORT`／`127.0.0.1:PORT`，若全 repo **恰好一個**模組的 `server.port` 是 PORT，就畫到那個模組（`inferred`）；零個或多個擁有者就什麼都不畫，localhost 仍永遠不是節點。Spring 的 `[optional:]configserver:` 匯入前綴先剝掉 | ecommerce（八個 client 的 `spring.config.import` 預設 `localhost:9296`，cloud-config 聽 9296） | `CodeGraphMerger.portOwner`、`ConfigExtractor.isRelevant`（`server.port`） |
| 16 | **Spring Cloud 的隱含註冊中心**：pom 有 Eureka client starter、而自己的設定（含 config repo 裡以它命名的檔與共用檔）都沒有 `eureka.client.serviceUrl.defaultZone` 的模組，連到 repo 裡**唯一**有 Eureka server starter 的模組（`inferred`）；零個或多個 server 就略過；兩端都要在部署詞彙表裡 | ecommerce（9 個 client 沒有任何 `eureka.client.*`） | `CodeGraphMerger.mergeImplicitRegistry`、`ServiceRootScanner.recordModuleDeps`（`module-deps`） |
| 17 | **字串值等於模組名的常數就是被呼叫端**：enum 常數（或 `static final String`）的字串值正規化後等於某個模組目錄名，而 `Type.NAME` 在**可部署模組**的呼叫點當參數出現，就畫 X → 該服務（`documented`）；自我引用與共用 client 函式庫裡的引用不畫 | TeaStore（`Service.PERSISTENCE("tools.descartes.teastore.persistence")`，7 條業務邊） | `java-generic.scm`（`name-constant`／`name-ref`）、`CodeGraphMerger.resolveModuleName` |
| 18 | **`String.format("http://%s/…", getenv("X", "d"))`**：`%s` 先用模組自己的環境（Dockerfile `ENV`、manifest env）填，再用 getenv 的預設值；Dockerfile 的 `ENV` 列（含 `\` 續行）進環境變數表，scope 是該 Dockerfile 的模組 | robot-shop（shipping → cart） | `java-generic.scm`（`url.env`）、`ConfigExtractor.extractDockerfileEnv` |
| 19 | **帶版本字尾的 Deployment 是某個 Service 的一個版本**：`X-v<數字>` 在旁邊有同名 Service `X`、或有另一個只差版本的 workload 時，節點名用 `X`（Istio bookinfo 慣例）；只剝 `-v\d+`，且只在這兩種證據下 | AICS（`core-service-v1` ＋ Service `core-service`） | `ConfigExtractor`（`k8s-service`）、`CodeGraphMerger.unversionedWorkload` |
| 20 | **環境變數在讀取者自己的 scope 解析**：同一個變數名可在多份 manifest 各宣告不同值；模組 X 的 getenv 先查 X 的 workload 自己的 env、X 的 workload 收到的 ConfigMap、X 的 Dockerfile，都沒有才查全 repo 表，而且只在全 repo 的值一致（或恰有一個是已知服務）時採用；scope 內多個不同值視為模糊、不畫 | AICS（四份 manifest 各自的 `MONGO_CLIENT_HOST`） | `CodeGraphMerger.resolveEnv` |
| 21 | **函式庫不是服務**：只有 `setup.py`／`pyproject.toml`、沒有 Dockerfile 與應用 manifest 的 Python 模組是函式庫，永遠不成為節點；它檔案裡的呼叫歸給 `import` 那個檔案（`from pkg.sub import …`，含函式庫內部的相對 import 傳遞）的服務；沒人 import 就不畫 | AICS（`aics_text_handler.main_text_extractor` 被三個服務 import） | `ServiceRootScanner`（`library`／`packages`）、`python-generic.scm`（`import`）、`CodeGraphMerger.importersOf` |
| 22 | **呼叫點才算使用（Python）**：`requests.get(url=req_url)` 這類變數 URL 沿同檔的賦值追到 `os.getenv(...)` 或 URL 字面值（最多三跳），邊的出處是發出請求的那一行、getenv 列讓位；模組裡**沒有任何活程式碼引用**的函式（遞移：只被死函式呼叫的也算死）內的呼叫，以及沒人引用的 `NAME = os.getenv(...)`，標 `unreferenced` 不畫邊——規則 9 的 Python 版 | AICS（user-service 有一整個沒人呼叫的 config-service communicator） | `python-web.scm`（`http-client.urlref`）、`TreeSitterExtractor.resolvePythonUrlRefs`／`markDeadPythonCode` |
| 23 | **共用設定的 key 只在有對應函式庫的 client 生效**：規則 3 把共用 `application.yml` 攤到每個 config client，但 `spring.rabbitmq.*` 只在宣告 AMQP／Rabbit artifact 的模組綁定、`security.oauth2.*` 要有 oauth2 artifact、`eureka.client.*` 要有 eureka、`spring.kafka.*`／`spring.data.mongodb.*`／`spring.redis.*` 同理（key 前綴 → artifact 正規表示式對照表）；pom 讀不到的模組一律保留 | piggymetrics（7 條錯邊：registry／gateway／monitoring／auth → rabbitmq、monitoring／registry／turbine → auth-service） | `CodeGraphMerger.consumesKey`（`KEY_LIBRARIES`） |

另外三個不算新規則的修正：`requests.request("GET", url)` 的第一個位置參數是 HTTP 動詞、URL 在第二個（原本把 `GET` 畫成節點；`python-web.scm`，Python 的 URL 字面值現在也要有 URL／路徑的形狀）；`.internal` 網域與 `169.254.*`（雲端 metadata server）視同 loopback，永遠不是目標（規則 8 的延伸，`CodeGraphMerger.isLoopback`）；Python 函式參數預設值 `host='redis-service…'` 進 `config` 列（規則 5 的 Python 版，`python-generic.scm`）。

**規則 15–23 的前後數字**（同一份 checkout；完整表由 `GeneralizationScoreTest` 寫在 `docs/generalization/scores.md`）：

| 專案 | 之前 P／R | 之後 P／R | 變動 |
|---|---|---|---|
| ecommerce | 1.00／0.54（20／37） | **1.00／1.00**（37／37） | 規則 15 ＋ 16：8 條 → cloud-config、9 條 → service-discovery |
| TeaStore | 1.00／0.46（6／13） | **1.00／1.00**（13／13） | 規則 17：7 條 enum 分派的業務邊，不多不少 |
| robot-shop | 1.00／0.86（18／21） | **1.00／0.90**（19／21） | 規則 18：shipping → cart；web → cart／ratings 由 `inferred` 升為 `documented`（Dockerfile ENV 給了主機） |
| piggymetrics | 0.83／1.00（34 對 7 錯） | **1.00／0.97**（33 對 0 錯） | 規則 23 去掉 7 條錯邊；同時去掉 `monitoring → registry`——參考邊集這一條我認為是錯的（monitoring 的 pom 只有 hystrix-dashboard、沒有 Eureka client，不會註冊），先不改 truth、留待裁決 |
| Bank of Anthos、ewolff、Online Boutique | 1.00／1.00 | 1.00／1.00 | **逐邊不變** |
| train-ticket（回歸） | 121 條 | 121 條 | **逐邊不變** |
| MicroDepGraph 7 專案 | 93／95＝0.98 | 93／95＝0.98 | 不變；工具多畫的邊從 35 → 55 條，新增的全是 `inferred` 的控制面邊（規則 15／16：Tap-And-Eat 7 條 → discoveryservice、spring-cloud-microservice 的 → cloud-config-server／cloud-eureka-server／zipkin）與 LakesideMutual 的 `customer-management-frontend → customer-self-service-backend`（它的 `.env` 寫 `localhost:8080`，8080 只有 self-service-backend 聽），每條都可以回到檔案 |
| AICS（私有 Istio polyrepo，49 條參考邊） | 0.42／0.10 | **0.89／0.63**（只算叢集內：0.87／0.74，業務邊 14／14） | 規則 19–22；剩下的 4 條錯邊是 static 目錄裡沒人 import 的腳本、信件內文與字串比較裡的 URL，剩下的漏邊是不在 k8s 裡的兩個程式（經 NodePort、SSH tunnel）與 Istio Gateway 的 6 條控制面邊 |

**沒做的**：Istio `VirtualService` 的路由（`istio-ingressgateway → 目的地`）是一條合理的通用規則，但 Online Boutique 與 train-ticket 的 repo 也附了 VirtualService，而它們的參考邊集沒有入口邊，加了會在這兩個專案出現新的「錯邊」——先不加，記在這裡。

第十條是回歸測試逼出來的（§5）：**只認「部署了這個 repo 自己模組」的 manifest 目錄**。repo 常附監控堆疊與選配元件的 manifest（train-ticket 的 prometheus/grafana/jaeger/EFK 共 60 個 workload、Bank of Anthos 的 pgpool operator 與 Cloud SQL populate job），第一版把它們全拉成節點；現在一個目錄要有至少一個 workload 對得上 repo 的模組，它的 workload 才進詞彙表。順帶修正佔位符的優先序：`${X_HOST:ts-x-service}` 要先用預設值（`documented`），最後才用變數名猜（`inferred`）。

### 3b. 2026-10-26 第二輪：三個沒看過的系統（hold-out）逼出的規則 24–35

規則 15–23 做完之後，拿三個**從頭到尾沒看過**的系統當 hold-out（`docs/generalization/holdout/`：open-telemetry/opentelemetry-demo、macrozheng/mall-swarm、DeathStarBench 的 hotelReservation；參考邊集在 `holdout/truth/`，計分 `holdout/score.py`）。結果是**規則 15–23 一條都沒轉移過去**：三系統合計 P 0.73／R 0.70（`holdout/scores-baseline.md` ＝ `scores-after.md`），24 條錯、28 條漏，每條的歸因在 `holdout/causes.tsv`。逐條追到形狀之後是下面 12 條；每條有單元測試（`GreenfieldGeneralizationTest` 24 → 31、`ExtractionGeneralizationTest` 12 → 17）。**注意：修完之後的數字已經不是 hold-out 結果**——規則是在這三個系統上歸納的；要再量泛化，得再找三個沒看過的。

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 24 | **Compose 的鍵名轉發與 `.env` 內插**：`environment:` 裡只寫鍵名的項目（`- CART_ADDR`、`CART_ADDR:`）是從專案 `.env`／該服務 `env_file:` 轉發的變數，值照 Compose 的規則內插（`${VAR}`、`${VAR:-d}`，`.env` 裡可以再引用別的變數，遞迴有上限）；`env_file:` 的每個鍵都是該服務的 env。這是現代 Compose 寫佈線的方式：位址全在 `.env`，服務只列鍵名，repo 裡**沒有別處**說哪個服務拿到哪個位址 | opentelemetry-demo（7 條漏邊：shipping → quote、load-generator → frontend-proxy、email／fraud-detection → flagd…，其中 Rust／Ruby 沒文法的那幾條只能畫 `inferred`） | `ConfigExtractor.extractComposeEnvironment`／`interpolate`／`readEnvFile` |
| 25 | **`links:`／`external_links:` 的別名是該服務對目標的稱呼**：`mysql:db` 讓這個容器用 `db` 連到 mysql，它的設定就寫 `jdbc:mysql://db`；別名登記成 `compose-alias`（限該服務的 scope），解析時先查讀取者自己宣告的，沒有才用全 repo 一致的那個；`external_links` 與 `links` 同等 | mall-swarm（5 條錯 ＋ 5 條漏：db → mysql、rabbit → rabbitmq、es → elasticsearch） | `ConfigExtractor.extractComposeDependsOn`、`CodeGraphMerger.composeAlias` |
| 26 | **連線 scheme 決定節點種類**：`jdbc:`／`r2dbc:`／`mongodb://`／`redis://`… 一律是資料庫、`amqp://`／`kafka://` 是 broker，不管主機叫什麼（`jdbc:mysql://db` 原本因為 `db` 沒有資料庫線索被標成 sync-http） | mall-swarm | `CodeGraphMerger.kindFromScheme` |
| 27 | **位址清單 key 與裸 FQDN 的 endpoint**：`spring.cloud.nacos.*.server-addr`、`spring.elasticsearch.uris`、`*.cluster-nodes`、`*.addresses`、`*.bootstrap-servers` 的值是 `host:port`（沒有 scheme），列為目標（清單取第一個；YAML 清單攤平的 `[0]` 不算 key 的一部分）；`*.endpoint` 的值若是帶點的裸 FQDN（`oss-cn-shenzhen.aliyuncs.com`）是外部服務，裸單字（`minio`）仍只是訊號 | mall-swarm（6 條 → nacos-registry、1 條 → elasticsearch、1 條外部） | `ConfigExtractor.isRelevant`、`CodeGraphMerger.syncTarget` |
| 28 | **Kotlin 用薄表面讀**：`.kt`／`.kts` 視為 JVM 語言的確定性 stack，但 `~/.m2` 裡沒有 bonede 的 Kotlin 文法，所以用 regex 讀 `System.getenv("X")`（含 `?:` 預設值）、URL 字面值、`@KafkaListener`／`@RabbitListener`，並在 ledger 與 stack 描述上寫明「薄表面、看不到變數主機的呼叫點」 | opentelemetry-demo（fraud-detection → kafka 由程式碼層畫出） | `StackDetector`（`THIN_LANGUAGES`）、`TreeSitterExtractor.extractThinSurface` |
| 29 | **OpenAPI／Swagger 中繼資料的 URL 不是呼叫**：字面值是 `new License()/ExternalDocumentation()/Contact()/Info()/Server()` 鏈上 `.url()`／`.termsOfService()`、springfox `ApiInfoBuilder.termsOfServiceUrl()/licenseUrl()`、`new Contact(name, url, mail)`、`@Info/@License/@Contact/@ExternalDocumentation(url=…)` 的參數時，標 `url-metadata`，同一行同值的 `url` 列標為不是位址——判斷的是 builder，不是網域黑名單 | mall-swarm（10 條假外部邊）、train-ticket（ts-common → github.com 也是 swagger 的 termsOfServiceUrl，順帶修掉） | `java-generic.scm`、`TreeSitterExtractor.dropMetadataUrls` |
| 30 | **訊息程式碼的邊是 service → broker**：`@RabbitListener(queues=…)`、`@KafkaListener(topics=…)`、`kafkaTemplate.send(topic)` 的目的地名字不是節點；broker 是這個服務設定裡的那一個（`spring.rabbitmq.host`、`KAFKA_ADDR`、compose 別名），找法：該服務已經連到的同族 queue 節點 → 部署裡唯一的同族 queue 節點 → 都沒有就當殘餘、不造節點；consumer 與 producer 同向（參考邊集的慣例）。async 列因此改到所有其他列合併完才處理 | mall-swarm（`mall.order.cancel → mall-portal` 錯邊） | `CodeGraphMerger.mergeAsync` |
| 31 | **測試 fixture 不是服務**（規則 2 的延伸）：檔名片段含 `test`／`tests`／`e2e` 的 Compose 檔（`compose.tests.yaml`）整份不讀；服務名符合測試目錄規則或以 `tests` 結尾（`frontendTests`）的不建節點、不算 depends_on 兩端；隱藏目錄（`.bingo/go.mod`、`.devcontainer/Dockerfile`）裡的建置標記是工具，不是服務根 | opentelemetry-demo（frontendtests）、hotelReservation（bingo 節點） | `ConfigExtractor.isTestCompose`、`ServiceRootScanner.isTestName`／`inHiddenDir` |
| 32 | **`key=value;` 連線字串的主機**：ADO.NET／Npgsql／ODBC 的 `Host=…;Username=…`、`Server=…;Database=…`（含 `tcp:` 前綴、`,port`）取 Host／Server／Data Source 當位址 | opentelemetry-demo（accounting → astronomy-db，C# 無文法，靠 compose env） | `ConfigExtractor.addressHost`（`CONNECTION_STRING_HOST`） |
| 33 | **保留 TLD 不是相依**：RFC 2606／6761 的 `.test`、`.example`、`.invalid`、`.localhost`、`example.com/net/org` 與 RFC 6762 的 `.local`（`svc.cluster.local` 除外）結尾的主機，不畫邊、不留殘餘、不出 ServiceEntry | opentelemetry-demo（測試用 VCR 卡匣的 `https://vcr.local/` 畫成節點 `vcr`） | `CodeGraphMerger.isReservedHost`、`ExternalHostDetector` |
| 34 | **Envoy 靜態設定是反向代理的上游表**（規則 6 的延伸）：`static_resources.clusters[].…socket_address`（`load_assignment` 或舊的 `hosts`）每個位址出一條 `url` 列，port 是佔位符時省略，讓 `${HOST}` 能單獨由 env 表填 | opentelemetry-demo（frontend-proxy → image-provider／flagd） | `ConfigExtractor.extractEnvoyClusters` |
| 35 | **`depends_on` 是啟動順序，分級處理**：宣告它的服務自己的 env **沒有任何位址**時，它是部署對佈線唯一的陳述，照畫、標 `inferred`（和其他「只有宣告、沒有使用點」的邊一致）；服務的 env **有位址**時，有位址背書的 depends_on 畫 `documented`（兩個宣告一致），沒有位址背書的**不畫**。出處寫成 `檔名#depends_on`，看圖的人分得出來 | opentelemetry-demo（5 條只有 depends_on 的錯邊） | `CodeGraphMerger.mergeOne`（compose-dependency） |

**規則 35 的證據**（`scratchpad/depends_audit.py`，17 個系統，含 MicroDepGraph 7 個）：只有 depends_on 的邊共 100 條——90 條對、5 條 variant、**5 條錯**；5 條錯全在 opentelemetry-demo，而且來源服務的 env 都有位址；90 條對的來源服務（spring-petclinic 8、spring-cloud-netflix 19、spring-cloud-microservice 20、hotelReservation 22、robot-shop 15、tap-and-eat 4、microservices-book 2）env 都沒有位址。所以「全部不畫」會丟 90 條（MicroDepGraph 一致度會從 93/95 掉到谷底），「全部照畫」留 5 條錯；上面的分級兩邊都保住。沒有加第四個信心等級：三級（observed／documented／inferred）是老師 10/2 定過的詞彙，depends_on-only 落在 inferred 的定義（宣告、無使用點）裡剛好。

另外四個不算新規則的修正：Compose env 裡帶點的主機保留整個網域（Compose 沒有 namespace）、在 workload 佈線裡畫成 `external`／`inferred`（規則 14 的延伸；原本 `https://local-llm.com` 被切成節點 `local-llm`）；設定檔名對服務的「去尾比對」原本漏掉單字服務（`other-prod` → `other` 不會被試到），修掉；MyBatis 的 `@MapperScan` 加進持久化標記（規則 9 的延伸；mall-swarm 全部用 MyBatis）；`ExternalHostDetector` 不再為標了 `unreferenced` 的 URL 出 ServiceEntry。

**hold-out 的前後數字**（`holdout/scores-baseline.md` → `holdout/scores-r2.md`）：

| 系統 | 之前 P／R | 之後 P／R | 說明 |
|---|---|---|---|
| opentelemetry-demo | 0.79／0.82（31 對 8 錯 7 漏） | **0.97／1.00**（38 對 1 錯 0 漏） | 剩的 1 條錯是 frontend → flagd（compose 給了 frontend `FLAGD_HOST`，標註者自己也標「truth 存疑」，不改）。**truth 補了 3 條 variant**（agent → frontend：agent 的 Dockerfile 也複製了 shared/tools.py、標註時只記了 mcp；agent → local-llm.com、jaeger → prometheus：agent／observability 覆蓋層漏標）——沿 10/23「計分後補」的先例，列在 truth 檔的註解裡；不補的話是 0.90／1.00 |
| mall-swarm | 0.43／0.50（12 對 16 錯 12 漏） | **1.00／0.96**（23 對 0 錯 1 漏） | 剩的 1 條漏是 mall-admin → minio（位址是 IP、沒有 manifest 叫 minio，靜態層無從對上） |
| hotel-reservation | 1.00／0.71 | 1.00／0.71 | 不變；9 條漏全是 Go 經 Consul `srv-*` 解析的呼叫，刻意不做（文件化的邊界）；只拿掉 `bingo` 這個雜訊節點 |
| **合計** | 0.73／0.70 | **0.99／0.89** | |

**回歸**（同一份 checkout 重跑，`docs/generalization/scores.md`、`external-agreement.md` 由測試重算）：七個歸納系統 P 全部 1.00、R 不變；Bank of Anthos 12 條逐邊不變；train-ticket 121 → 120（少的那條是 ts-common → github.com，SwaggerConfig 的 `termsOfServiceUrl`，規則 29 正確拿掉）；MicroDepGraph 93／95 ＝ 0.98 不變；AICS 0.89／0.63 逐邊不變。**等級變動**：83 條只有 depends_on 的邊從 `documented` 改成 `inferred`（規則 35；spring-petclinic 13、spring-cloud-netflix 25、spring-cloud-microservice 24、tap-and-eat 4、microservices-book 2、robot-shop 7×2 份、train-ticket 1），邊的有無不變。

### 3c. 2026-10-26 第三輪：第二組 hold-out（set C）逼出的規則 36–46

規則 24–35 做完之後，再拿兩個**沒看過**的系統當第二組 hold-out（`docs/generalization/holdout-c/`：nashtech-garage/yas——Spring Boot 3 ＋ Spring Cloud Gateway BFF ＋ Keycloak ＋ Kafka ＋ Next.js，compose 為主、附一份過時的部分 k8s manifest；aspnetrun/run-aspnetcore-microservices——.NET 8 全 C#，只有 compose；參考邊集在 `holdout-c/truth/`，計分 `holdout-c/score.py`）。規則 1–35 原封不動跑出來是 **yas 0.68／0.81、run-aspnetcore-microservices 0.00／0.00**（`holdout-c/scores-r2.md`）：.NET 那個系統 13 條畫出來的邊有 11 條**只差在名字**——compose 服務叫 `catalog.api`、`basket.api`，工具把每個含點的名字都在第一個點截斷；yas 則是 `http://api.yas.local/<svc>` 被截成一個假服務 `api`（15 條錯邊）、Maven 模組名 `backoffice-bff` 與 compose 服務名 `backoffice` 沒接上（8 條各算一錯一漏）、共用函式庫與測試專案被當服務。逐條追到形狀之後是下面 11 條；每條有單元測試（`GreenfieldGeneralizationTest` 31 → 38、`ExtractionGeneralizationTest` 17 → 22）。**同樣要注意：修完之後的數字已經不是 hold-out 結果。**

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 36 | **只剝真正的 DNS 字尾**：叢集字尾（`.svc.cluster.local`／`.cluster.local`／`.svc`）連同前面那一段 namespace 一起剝（`<svc>.<ns>.svc.cluster.local` → `<svc>`）；`<svc>.<ns>` 只在 `<ns>` 是 manifest 宣告過的 namespace（`metadata.namespace`／`kind: Namespace`，新 `k8s-namespace` 列）時剝；完全等於部署宣告過的名字（compose 服務 `catalog.api`）的帶點名字整個保留，`Basket.API` 目錄與 `basket.api` 服務以「標點換連字號」的模組拼法視為同名（規則 2 的延伸，不是相似度）；保留 TLD（規則 33）在任何截斷之前判斷，`api.yas.local` 整個丟掉而不是變成 `api`；其餘帶點的主機是外部網域，**永遠不截到第一段**。k8s manifest 裡的兩段式名字仍視為 `<svc>.<ns>`（叢集 DNS 短名），三段以上是網域 | run-aspnetcore-microservices（11 條只差名字的邊）、yas（15 條 → `api` 的假邊） | `ConfigExtractor.hostOf`／`CLUSTER_SUFFIXES`、`CodeGraphMerger.cleanHost`／`external`／`matchNode`（`hyphenated`） |
| 37 | **Compose 的 `hostname:` 是該服務的全域別名**（規則 25 的延伸）：`messagebroker` 宣告 `hostname: ecommerce-mq`，別的服務寫 `amqp://ecommerce-mq:5672`，目標是 `messagebroker`；別名登記成 scope 為 `*` 的 `compose-alias`，別名本身不是節點；`${VAR}` 形式先用 `.env` 內插 | run-aspnetcore-microservices（2 條假節點 `ecommerce-mq`） | `ConfigExtractor.extractComposeServices`、`CodeGraphMerger.composeAlias` |
| 38 | **YARP 的 `ReverseProxy.Clusters.*.Destinations.*.Address` 是閘道的上游表**（規則 6／34 的延伸），每條出一列 `url`；`appsettings*.json` 其餘以 `:` 串起的鍵（`ConnectionStrings:Database`、`GrpcSettings:DiscountUrl`）進 config 列，這是 .NET 程式碼讀它們的拼法，compose 覆蓋同一個鍵時拼成 `ConnectionStrings__Database`，合併時兩者等同 | run-aspnetcore-microservices（3 條 gateway 路由） | `ConfigExtractor.extractDotnetAppSettings` |
| 39 | **值的形狀決定節點種類**（規則 26 的延伸）：`key=value;` 連線字串（`Server=catalogdb;…`）與 `ConnectionStrings__X` 變數一律是資料庫、`amqp://` 是 broker，不管主機叫什麼（`catalogdb`、`messagebroker` 在名字線索表裡都不是）；env-address／workload-env 列帶 `kind` 欄；先畫成呼叫的邊（depends_on）在目標種類確定後改成 `db`／`async` | run-aspnetcore-microservices（6 條 data 邊） | `ConfigExtractor.kindHint`、`CodeGraphMerger.mergeWorkloadWiring`／`retypeEdgesByTargetKind` |
| 40 | **C# 用薄表面讀**（規則 28 的延伸）：`.cs` 從 LLM 層改為確定性的薄表面——`GetConnectionString("X")` → `ConnectionStrings__X`、`Configuration["A:B"]`／`GetValue<T>("A:B")`／`GetSection("A:B")` → `A__B`（`:` ↔ `__` 是 .NET 的設定慣例）、`GetEnvironmentVariable("X") ?? "d"`、URL 字面值；`obj/`、`bin/` 跳過；讀到的鍵在 compose 的 env 表解到主機就是 `documented`（呼叫點），沒讀到的只剩 compose 的 `inferred`；ledger 寫明「薄表面、看不到變數主機的呼叫點、MassTransit／gRPC 只能靠它讀的設定鍵」 | run-aspnetcore-microservices（8 條由 inferred 升為 documented）、Online Boutique cartservice／otel-demo accounting（等級同升，邊不變） | `StackDetector`（`THIN_LANGUAGES`）、`TreeSitterExtractor.extractThinSurface`（`CS_*`） |
| 41 | **JVM 函式庫模組不是服務**（規則 21 的 Java 版）：pom／gradle 模組沒有 Dockerfile、沒有 spring-boot-maven／shade／assembly／jib 外掛、不是 war／ear、`src/main` 下沒有任何 `main`／`@SpringBootApplication`，就是函式庫（`library_kind=jvm`）；它的 `application.properties` 是給自己測試用的，不畫邊、不成節點；部署檔點名它（workload 對得上、compose 有 build context）時以部署為準 | yas（`common-library → identity`；順帶 ts-common、mall-common、TeaStore 的 entities／registryclient 不再是孤立節點或殘餘） | `ServiceRootScanner.recordModuleDeps`／`hasMainMethod`、`CodeGraphMerger.indexMeta` |
| 42 | **測試樹的每一段都算**（規則 2／31 的延伸）：`src/it`（Maven Failsafe）、`src/integration-test`、`e2e`、`__tests__`、`automation-ui` 是測試樹，底下的設定檔與程式碼都不讀；模組路徑**任何一段**符合測試目錄規則就不是服務根（`automation-ui/backoffice` 的子模組剛好叫服務的名字） | yas（`backoffice → storefront`、`payment → api`、`recommendation／search → api` 的來源都在 src/it 或 automation-ui） | `SourceScanner.TEST_SEGMENTS`、`ServiceRootScanner.underTestDir`／`TEST_ROOT_DIRS` |
| 43 | **OpenAPI UI 的設定鍵不是呼叫**（規則 29 的設定檔版）：`springdoc.*`、`springfox.*`、`swagger.*`、`knife4j.*` 底下的 URL（`springdoc.oauthflow.authorization-url`）是瀏覽器端 Swagger UI 要開的網址，不進 config 列 | yas（`delivery → identity`：一個沒有任何 manifest 的模組，唯一的邊來自這種鍵） | `ConfigExtractor.isRelevant`（`OPENAPI_UI_PREFIXES`） |
| 44 | **compose 的 build 說了哪個模組是哪個服務**：`build: ./backoffice-bff`（context）或 `build: {context: ., dockerfile: Services/Basket/Basket.API/Dockerfile}`（Dockerfile 所在目錄）對到的 service-root 目錄，就叫那個 compose 服務名；模組 pom 自己的 `<artifactId>` 完全等於某個 compose 服務名也是同一件事。這是 repo 自己寫的對應，不是相似度猜測，所以不問人；有 k8s manifest 時 compose 名要能對到 workload（同名、模組拼法同名、或唯一的 `-字尾`：TeaStore 的 `webui` ＝ `teastore-webui`）才採用，對不到就照規則 11 用 k8s 拼法 | yas（8 條 `*-bff` 邊併回 `backoffice`／`storefront`）、run-aspnetcore-microservices（`Basket.API` ↔ `basket.api`）、ewolff/microservice（`microservice-demo-turbine-server` 與 `turbine` 兩種拼法併成一個） | `ConfigExtractor.extractComposeServices`（`context`／`dockerfile_dir`）、`ServiceRootScanner.ownArtifactId`、`CodeGraphMerger.indexMeta`（`composeBuildDirs`／`workloadForDeploymentName`） |
| 45 | **`*.url`／`*.uri`／`*.endpoint` 鍵下的裸單字，只在它是部署宣告過的名字時才是目標**（規則 5／27 的延伸）：`elasticsearch.url=elasticsearch` 畫到 compose 服務 `elasticsearch`；`feature.url=disabled` 什麼都不畫。「宣告過」＝圖的詞彙表或 compose 服務名（k8s manifest 存在時 compose 名不進詞彙表，但仍是宣告） | yas（`search → elasticsearch`） | `CodeGraphMerger.syncTarget`／`declaredName` |
| 46 | **nginx 的 `set $var "x"; proxy_pass http://$var`**：同一檔內 `set` 成字面值的變數在 `proxy_pass` 裡填回去（一層；設成非字面值或沒設的仍是佔位符） | yas（`nginx → search`、`nginx → elasticsearch`） | `ConfigExtractor.extractReverseProxy`（`NGINX_SET`／`NGINX_VAR`） |

另外一個不算新規則的修正：compose 服務的 `environment` 在來源服務不在詞彙表裡時（k8s manifest 是過時的部分集合，把 Keycloak 叫 `keycloak`，compose 叫 `identity`），用 depends_on 同一套 `resolveComposeService` 把 compose 名當來源（`identity → postgres`）；mall-swarm 因此多一條 variant 邊 `kibana → elasticsearch`。

**set C 的前後數字**（`holdout-c/scores-r2.md` → `holdout-c/scores-r3.md`，每條錯／漏的歸因與修它的規則號在 `holdout-c/causes.tsv`）：

| 系統 | 之前 P／R | 之後 P／R | 說明 |
|---|---|---|---|
| run-aspnetcore-microservices | 0.00／0.00（0 對 12 錯 11 漏） | **1.00／1.00**（11 對 0 錯 0 漏） | 規則 36（11 條名字）、37（2 條 `ecommerce-mq`）、38（3 條 YARP 路由現在來自 appsettings.json 而不是 depends_on）、39（6 條 data 邊的種類）、40（8 條升 documented）、44（`Basket.API` 目錄 ↔ `basket.api`） |
| yas | 0.68／0.81（62 對 29 錯 15 漏） | **0.83／0.96**（74 對 15 錯 3 漏） | 規則 36 去掉 2 條 → `api`（其餘 13 條 r2 已由規則 24 去掉）、44 併回 8 條 `*-bff` 邊（一錯一漏各 8）、41 去掉 `common-library → identity`、42 去掉 `backoffice → storefront`、43 去掉 `delivery → identity`、45 補 `search → elasticsearch`、46 補 `nginx → search／elasticsearch`、非規則修正補 `identity → postgres`。剩 15 條錯：10 條 `storefront → cart／customer／…` 與 `cart → media`、`rating → product` 是 compose 把位址塞給了不呼叫它的服務（規則 14 的 `inferred`；參考邊集作者自己也標「工具若從 manifest 畫出來算錯」）、2 條 `yas.public.url=http://storefront/…` 是瀏覽器導回網址、2 條來自 `application-dev.yaml` 的 `localhost:PORT`（規則 15 讀到 dev profile）；剩 3 條漏：`order → promotion` 要認得 nginx 的 `server_name`、`storefront-nextjs → storefront` 是 Next.js SSR（沒有文法）、`kafka-connect → postgres` 是 Debezium connector JSON（刻意不做） |
| **合計** | 0.60／0.70 | **0.85／0.97** | |

**刻意不做、記成邊界**：Debezium connector JSON（`database.hostname`，由腳本 PUT 進 kafka-connect）、Next.js SSR 的 fetch（TypeScript 沒有文法）、Keycloak 這類只有映像沒有程式碼的服務的 realm 設定、nginx `server_name` 當虛擬主機反查、dev／local profile 是否該排除（`application-dev.yaml` 的 `localhost:8085` 被規則 15 讀回 order）、C# 的 ProjectReference（`Ordering.Infrastructure` 這類類別庫的讀取歸給引用它的專案；現在靠 compose 的 `inferred`）。

**回歸**（同一份 checkout 重跑）：七個歸納系統 P 全部 1.00、R 不變、**邊逐條相同**（唯一差異：Online Boutique `cartservice → redis-cart` 由 inferred 升為 documented，規則 40 看到了 `Configuration["REDIS_ADDR"]`）；Bank of Anthos 12 條逐邊不變；train-ticket 120 條逐邊不變；MicroDepGraph 93／95 ＝ 0.98 不變（microservices-book 工具多畫的邊 4 → 2：規則 44 把 `microservice-demo-turbine-server`／`-zuul-server` 併進 compose 的 `turbine`／`zuul`）；第一組 hold-out 0.99／0.89 不變（otel-demo `accounting → astronomy-db` 升 documented、mall-swarm 多一條 variant `kibana → elasticsearch`）；AICS 0.89／0.63 逐邊不變。做的過程中有兩次差點退步，都在回歸裡被抓到並改掉：(1) TeaStore 模組 pom 的 `<artifactId>webui</artifactId>` 等於 compose 的 `webui`，第一版規則 44 把 k8s 的 `teastore-webui` 改名成 `webui`、多出 9 條兩種拼法的邊——改成「有 manifest 時 compose 名要能對到 workload」；(2) 第一版讓程式碼讀到的環境變數若解到外部網域就畫 external 邊，AICS 的 `SYSTEM_ADDRESS`（系統自己的對外網域，寄信用）變成 2 條假邊——改回「裸的 env 讀取只畫叢集內節點，外部主機要有帶 URL 的呼叫點」。

## 4. 修改後：對照參考邊集（程式計分）

> **2026-09-25 補**：這一節的參考邊集是**作者依專案文件與程式碼整理**的，所以「程式計分」只保證算得沒錯，**不保證答案是對的**（建構效度）。回應這個疑慮的三件事——對照**第三方發表**的邊集（7 個專案，一致度 0.96）、把每一條出處變成**打得開的檔案行**（92／92，順便抓到 2 條寫錯的出處）、**第二標註者**——在 `docs/generalization-external.md`。措辭也改了：以下的 P／R 應讀作「與**作者整理的**參考邊集的一致度」。

參考邊集逐條寫在 `docs/generalization/truth/<name>.tsv`（來源：README 段落、架構圖、或程式碼行號；分 business / data / external / control / variant 五類），`GeneralizationScoreTest` 讀它與探針的邊表算分、輸出 `docs/generalization/scores.md`。**表裡的數字是程式算的，不是人數的**；`variant`（只在某部署變體存在的邊）不進分子分母。

> **2026-09-26 重跑**：下表是**當日 checkout ＋ 規則 11–14** 的結果（`GeneralizationScoreTest` 產生 `docs/generalization/scores.md`）。與 9/22 那一輪的差異來自三件事：規則 11–14、TeaStore 上游改版（它現在有 k8s manifest，9/22 那次只有 1 條邊）、以及各 repo 這四天的變動。**precision 全部維持 1.00 或不變**。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control R | 9/22 的 R |
|---|---|---|---|---|---|---|---|---|---|---|
| ewolff | 5 | 5 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | — | 1.00 | 1.00 |
| ecommerce | 20 | 20 | 0 | 18 | **1.00** | 0.53 | 1.00 / 1.00 | — | 0.28 | 0.53 |
| piggymetrics | 41 | 34 | 7 | 0 | **0.83** | **1.00** | 0.63 / 1.00 | 0.67 / 1.00 | 1.00 | 1.00 |
| robot-shop | 18 | 18 | 0 | 3 | **1.00** | **0.86** | 1.00 / 0.40 | 1.00 / 1.00 | 1.00 | 0.86 |
| TeaStore | 6 | 6 | 0 | 7 | **1.00** | **0.46** | — / 0.00 | 1.00 / 1.00 | 1.00 | 0.08 |
| Online Boutique | 17 | 17 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | 1.00 | 0.13 |
| Bank of Anthos（回歸） | 12 | 12 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | 1.00 | 0.92 |

- **piggymetrics 的 7 條錯邊**全來自同一個原因：共用 `application.yml` 裡的 `user-info-uri: auth-service` 與 `rabbitmq.host` 被攤到**所有** config client，包括 registry、monitoring、turbine、gateway、auth 這幾個其實不當 OAuth resource server / pom 裡沒有 hystrix-stream、bus-amqp 的服務（真的用 RabbitMQ 的只有 account、statistics、notification、turbine 四個，看 pom 就知道）。工具目前無法從靜態檔判斷「這個 client 有沒有用到共用設定裡的這個 key」，這七條都標 `documented`，要靠 runtime 層才能降級。
- **ecommerce 的控制面邊**（每個服務→service-discovery、→cloud-config、→zipkin）全部漏掉，原因很誠實：repo 裡的 `application.yml` 只寫 `${SPRING_CONFIG_IMPORT:optional:configserver:http://localhost:9296}`，真正的主機名只存在 README 說「之後會放」的 k8s 目錄裡（目前不存在）；工具照規則把 `localhost` 預設值丟掉，沒有編造。
- **robot-shop 漏的 3 條**：`cart→catalogue`（Node）、`ratings→catalogue`（PHP）是沒有文法的語言；`shipping→cart` 是 `String.format("http://%s/shipping/", getenv("CART_ENDPOINT","cart"))`，host 由另一行的 getenv 預設值決定、路徑又點名的是自己，靜態層無法連。
- **TeaStore 是最誠實的失敗**：五個服務的所有 REST 呼叫都寫成 `loadBalanceRESTOperation(Service.PERSISTENCE, …)`，目標是 enum 常數，經自家 registry 動態解析；靜態層抓不到任何 host 字串，圖上就一條都沒畫（也一條都沒編）。這正是 runtime 層（Istio）存在的理由：這種系統要部署起來跑流量才有邊。節點、DB 邊（`persistence→teastore-db`，有 JPA 證據，`documented`）與 Kieker 變體的 RabbitMQ 邊都對。
- **Online Boutique 原本是「薄表面」的量化例子，2026-09-26 之後不再是**：9/22 那輪 16 條真邊只畫 2 條（R 0.13），歸因寫「13 條的來源是 Go/C#/Node，工具沒有文法」。**這個歸因只對了一半**：那些服務的呼叫目標其實寫在**各自 workload 的 manifest env** 裡（`PRODUCT_CATALOG_SERVICE_ADDR: productcatalogservice:3550`），而工具當時的規則是「服務位址一律不畫」。規則 14 把「寫在 workload 自己 env 裡的服務位址」畫成點線之後，**17／17 全中、零錯邊**。真正屬於「沒有文法」的損失比原本以為的小得多——語言決定的是「能不能看到程式碼層的呼叫」，而部署描述本來就把同一件事寫了一遍。

## 5. 回頭驗證：舊專案沒有退步（真 checkout 重跑，不只 fixture）

用新規則重跑 Bank of Anthos 與 train-ticket 的真實 checkout（`docs/generalization/bank-of-anthos.*`、`train-ticket.*`），和已 commit 的舊圖比：

- **Bank of Anthos**：11 條邊（6 業務 + 5 DB）**與 `docs/bank-of-anthos-greenfield-graph.mmd` 逐條相同**；計分 P 1.00 / R 0.92，唯一漏的是 `loadgenerator → frontend`（locust 的 host 來自命令列參數，程式碼裡沒有）。**2026-09-26：規則 14 之後這條也畫出來了**（`loadgenerator.yaml:82` 的 `FRONTEND_ADDR: "frontend:80"` 是寫在該 workload 自己 env 裡的字面位址），BoA 成為 12 條、R 1.00；`docs/bank-of-anthos-greenfield-graph.mmd` 是 9/22 的紀錄，**刻意不換**，最新的圖在 `docs/generalization/bank-of-anthos.mmd`。第一版新規則曾多出 `populate-*-db → db` 與 `pgpool-operator`（`extras/` 裡選配元件的 manifest），第十條規則後只剩 pgpool-operator 一個孤立節點（它和 accounts-db 的 HPA 變體同目錄，算該變體的真 workload）。
- **train-ticket**：舊圖 50 條服務邊**全部保留**，多 2 條真的（`ts-ui-dashboard → ts-gateway-service` 來自 nginx.conf 的 proxy_pass；`ts-admin-user-service → ts-register-service`），再多 **46 條 gateway 路由**（`lb://${ADMIN_ORDER_SERVICE_HOST:ts-admin-order-service}`，舊版連 `lb://` 帶佔位符都解不開）與 **21 條 DB 邊**（每個服務自己的 mysql/mongo，來自 manifest 佈線＋JPA 證據）。節點 100 個（54 服務 + 44 資料庫 + 2 外部），第一版曾是 114（多了 prometheus、grafana、EFK 等監控 workload，第十條規則後移除）。**`docs/train-ticket-greenfield-graph.mmd` 刻意不換**：它是 threshold-design §8 子圖實驗的固定輸入，換掉數字就得重跑；新圖放在 `docs/generalization/train-ticket.mmd`。
- 既有測試全過：`DependencyGraphTest` 71（petclinic runtime 合併、BoA greenfield fixture、train-ticket 路徑點名 fixture）等。
- 新增 `GreenfieldGeneralizationTest`（merger 層，每條規則一個最小 fixture，15 個）、`ExtractionGeneralizationTest`（extractor 層，temp 目錄，6 個）、`GeneralizationScoreTest`（計分，1 個）。
- 完整套件 `mvn -o test`：246 個，245 過，唯一失敗是需要叢集的 `McpToolkitCallToolTest`（本機沒有叢集，與本次無關）。
- 探針本身：`GreenfieldProbeTest`（比舊的 `StaticExtractionProbeTest` 多跑 db 提升、normalize、分層，並輸出 `.mmd` 與逐邊摘要 `.summary.md`，後者就是計分的輸入）。

## 6. 對「通用性」問題的誠實回答

1. **抽取層（tree-sitter + 設定檔）本來就通用**，這次六個專案 0 個語法錯誤；問題全在**合併層的假設**——第一版假設「模組目錄名＝部署名」、「設定只在 `application.yml`」、「compose 是某個目錄的設定」、「URL 字串出現就是呼叫」，這些在三個舊專案剛好都成立，所以之前看不出來。
2. 十四條規則都是**看到的形狀**而不是專案名；每條有對應測試；但它們的來源是十三個專案，第十四個很可能再逼出下一條——2026-09-25 的七個新專案就一次逼出了三條（§3 的 11–13），而第 14 條是追問「為什麼某個專案分數這麼低」問出來的。**方法本身可重複**：clone → 跑 probe → 對架構圖 → 每條差異追到一條規則或一個明確的語言/型態邊界。
3. 工具靜態層的三個明確邊界，這次量出來了：**沒有文法的語言**（Online Boutique 13/16、robot-shop 2/3 的漏邊）、**registry/enum 動態分派**（TeaStore 12/13）、**跨行拼接的 host**（robot-shop 1/3）。這三個都是 runtime 層要補的，而不是靜態層再加規則能解決的。
   > **2026-09-26 更正第一個邊界**：Online Boutique 那 13 條**不是**沒有文法造成的損失——它們的目標寫在各自 workload 的 manifest env 裡，工具當時的規則不畫服務位址。規則 14 之後 **17／17 全中**。真正屬於「沒有文法」的損失小得多：語言決定的是能不能看到**程式碼層的呼叫**，而部署描述常常把同一件事寫了一遍。robot-shop 與 TeaStore 的兩個邊界不變（一個是 Node/PHP 的呼叫、一個是 enum 分派，兩者都沒有寫進 manifest）。
4. 一個新的精確度來源被量出來：**「宣告但沒使用」**——ecommerce 25 條常數邊有 19 條沒人引用。這個規則對 train-ticket 這種「每個服務自己拼 URL」的專案沒有影響（它們的 URL 在呼叫點），只影響「常數檔複製到每個服務」的風格。

## 6b. 2026-09-25 的續篇

第 6 節第 2 點說「第七個專案很可能再逼出第十條」——實際上是**七個新專案逼出三條**（§3 的 11–13），而且這一次的參考邊集不是作者寫的（`docs/generalization-external.md`）。既有六個專案加 BoA、train-ticket 在同一份 checkout 上重跑，**節點與邊逐項不變**（唯一變動：piggymetrics 少一個沒有任何邊的雜訊節點）。

## 7. 待辦

- **TeaStore 上機器 A 跑 runtime**：runbook 在 `docs/teastore-runtime-runbook.md`，目標是「靜態 1/13 → runtime 13/13，工具與 TeaStore 都不改」。
- 第三輪 top-k / 路由 hold-out 改用這六個專案之一的 archive（原待辦；要機器 B 與 API 額度）。
- 再收新專案的方法已固定：clone → `GreenfieldProbeTest` → 寫 `truth/<name>.tsv` → `GeneralizationScoreTest` → 每條差異追到一條規則或一個邊界。
