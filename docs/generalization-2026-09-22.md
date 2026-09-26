# 泛化驗證：六個沒看過的專案（2026-09-22）

> 問題：我們在 petclinic / Bank of Anthos / train-ticket 上做的調校，對一個**全新、沒測試過**的專案有沒有效？
> 做法：挑六個形狀各異的開源微服務專案，只跑**靜態 greenfield 路徑**（tree-sitter + 設定檔 + 合併 + 正規化 + 分層；不接叢集、不接 DeepWiki、不叫 LLM），把工具畫的圖對照每個專案自己的架構說明或程式碼事實，算 precision / recall。
> 結論一句：**第一輪六個全部有問題**（一個整張圖崩掉、一個六成邊是假的、一個資料層全漏）；追出來的**十條通用規則**修完後，**Java 專案的業務邊 precision 1.0、recall 0.86–1.0**（程式計分，見 §4）；三個失敗的地方都能指出是哪一種語言或哪一種呼叫型態不在工具的靜態表面上，而且圖上沒有為此編造任何邊；舊的 Bank of Anthos 圖逐條不變、train-ticket 只多不少（§5）。

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

**2026-09-25 又追加三條**（由第三方資料集的對照逼出來的，細節與前後數字在 `docs/generalization-external.md` §1.6）：

| # | 規則 | 觸發它的專案 | 位置 |
|---|---|---|---|
| 11 | **Compose 的 `services` key 也是部署詞彙表**，與 k8s workload 同級，但**有 manifest 時完全不看 compose 名**（避免同一服務出現兩種拼法） | spring-petclinic、ewolff/microservice、Tap-And-Eat、spring-cloud-microservice | `ConfigExtractor.extractComposeServices`（新 `compose-service` 區段）、`CodeGraphMerger.indexMeta` |
| 12 | **也認 Compose 第 1 版檔案格式**（沒有 `services:`，頂層即服務，要有 `image`／`build` 才算）與 **`links:`**（v1 宣告依賴的方式，`服務:別名` 取冒號前） | Tap-And-Eat、spring-cloud-microservice（皆 2017 年的專案） | `ConfigExtractor.composeServices`、`extractComposeDependsOn` |
| 13 | **XML namespace 不是位址**：URL 字面值那一行或前兩行出現 `namespace`／`schemaLocation`／`xmlns`／`soapAction` 時不畫邊 | LakesideMutual（`@XmlSchema`、`setTargetNamespace`、`SoapActionCallback` 三種形態，畫出兩個沒人呼叫的外部主機） | `TreeSitterExtractor.dropNonAddressUrls` |

第十條是回歸測試逼出來的（§5）：**只認「部署了這個 repo 自己模組」的 manifest 目錄**。repo 常附監控堆疊與選配元件的 manifest（train-ticket 的 prometheus/grafana/jaeger/EFK 共 60 個 workload、Bank of Anthos 的 pgpool operator 與 Cloud SQL populate job），第一版把它們全拉成節點；現在一個目錄要有至少一個 workload 對得上 repo 的模組，它的 workload 才進詞彙表。順帶修正佔位符的優先序：`${X_HOST:ts-x-service}` 要先用預設值（`documented`），最後才用變數名猜（`inferred`）。

## 4. 修改後：對照參考邊集（程式計分）

> **2026-09-25 補**：這一節的參考邊集是**作者依專案文件與程式碼整理**的，所以「程式計分」只保證算得沒錯，**不保證答案是對的**（建構效度）。回應這個疑慮的三件事——對照**第三方發表**的邊集（7 個專案，一致度 0.96）、把每一條出處變成**打得開的檔案行**（92／92，順便抓到 2 條寫錯的出處）、**第二標註者**——在 `docs/generalization-external.md`。措辭也改了：以下的 P／R 應讀作「與**作者整理的**參考邊集的一致度」。

參考邊集逐條寫在 `docs/generalization/truth/<name>.tsv`（來源：README 段落、架構圖、或程式碼行號；分 business / data / external / control / variant 五類），`GeneralizationScoreTest` 讀它與探針的邊表算分、輸出 `docs/generalization/scores.md`。**表裡的數字是程式算的，不是人數的**；`variant`（只在某部署變體存在的邊）不進分子分母。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control R |
|---|---|---|---|---|---|---|---|---|---|
| ewolff | 5 | 5 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | — | 1.00 |
| ecommerce | 20 | 20 | 0 | 18 | **1.00** | 0.53 | 1.00 / 1.00 | — | 0.28 |
| piggymetrics | 41 | 34 | 7 | 0 | **0.83** | **1.00** | 0.63 / 1.00 | 0.67 / 1.00 | 1.00 |
| robot-shop | 18 | 18 | 0 | 3 | **1.00** | **0.86** | 1.00 / 0.40 | 1.00 / 1.00 | 1.00 |
| TeaStore | 1 | 1 | 0 | 12 | 1.00 | **0.08** | — / 0.00 | 1.00 / 1.00 | 0.00 |
| Online Boutique | 2 | 2 | 0 | 14 | 1.00 | **0.13** | 1.00 / 0.07 | 1.00 / 1.00 | 0.00 |
| Bank of Anthos（回歸） | 11 | 11 | 0 | 1 | **1.00** | 0.92 | 1.00 / 1.00 | 1.00 / 1.00 | 0.00 |

- **piggymetrics 的 7 條錯邊**全來自同一個原因：共用 `application.yml` 裡的 `user-info-uri: auth-service` 與 `rabbitmq.host` 被攤到**所有** config client，包括 registry、monitoring、turbine、gateway、auth 這幾個其實不當 OAuth resource server / pom 裡沒有 hystrix-stream、bus-amqp 的服務（真的用 RabbitMQ 的只有 account、statistics、notification、turbine 四個，看 pom 就知道）。工具目前無法從靜態檔判斷「這個 client 有沒有用到共用設定裡的這個 key」，這七條都標 `documented`，要靠 runtime 層才能降級。
- **ecommerce 的控制面邊**（每個服務→service-discovery、→cloud-config、→zipkin）全部漏掉，原因很誠實：repo 裡的 `application.yml` 只寫 `${SPRING_CONFIG_IMPORT:optional:configserver:http://localhost:9296}`，真正的主機名只存在 README 說「之後會放」的 k8s 目錄裡（目前不存在）；工具照規則把 `localhost` 預設值丟掉，沒有編造。
- **robot-shop 漏的 3 條**：`cart→catalogue`（Node）、`ratings→catalogue`（PHP）是沒有文法的語言；`shipping→cart` 是 `String.format("http://%s/shipping/", getenv("CART_ENDPOINT","cart"))`，host 由另一行的 getenv 預設值決定、路徑又點名的是自己，靜態層無法連。
- **TeaStore 是最誠實的失敗**：五個服務的所有 REST 呼叫都寫成 `loadBalanceRESTOperation(Service.PERSISTENCE, …)`，目標是 enum 常數，經自家 registry 動態解析；靜態層抓不到任何 host 字串，圖上就一條都沒畫（也一條都沒編）。這正是 runtime 層（Istio）存在的理由：這種系統要部署起來跑流量才有邊。節點、DB 邊（`persistence→teastore-db`，有 JPA 證據，`documented`）與 Kieker 變體的 RabbitMQ 邊都對。
- **Online Boutique 是「薄表面」的量化例子**：16 條真邊裡，來源是 Python/Java 的只有 2 條（recommendation→productcatalog、loadgenerator→frontend），工具抓到前者；後者的 host 來自 locust 的 `--host` 參數不在程式碼裡。`cart→redis-cart` 是靠 manifest 佈線（C# 程式碼看不到）推出的 `inferred` 邊。剩下 13 條的來源都是 Go/C#/Node，工具的偵測器已經把這些檔案標成「LLM tier、本次跳過」。

## 5. 回頭驗證：舊專案沒有退步（真 checkout 重跑，不只 fixture）

用新規則重跑 Bank of Anthos 與 train-ticket 的真實 checkout（`docs/generalization/bank-of-anthos.*`、`train-ticket.*`），和已 commit 的舊圖比：

- **Bank of Anthos**：11 條邊（6 業務 + 5 DB）**與 `docs/bank-of-anthos-greenfield-graph.mmd` 逐條相同**；計分 P 1.00 / R 0.92，唯一漏的是 `loadgenerator → frontend`（locust 的 host 來自命令列參數，程式碼裡沒有）。第一版新規則曾多出 `populate-*-db → db` 與 `pgpool-operator`（`extras/` 裡選配元件的 manifest），第十條規則後只剩 pgpool-operator 一個孤立節點（它和 accounts-db 的 HPA 變體同目錄，算該變體的真 workload）。
- **train-ticket**：舊圖 50 條服務邊**全部保留**，多 2 條真的（`ts-ui-dashboard → ts-gateway-service` 來自 nginx.conf 的 proxy_pass；`ts-admin-user-service → ts-register-service`），再多 **46 條 gateway 路由**（`lb://${ADMIN_ORDER_SERVICE_HOST:ts-admin-order-service}`，舊版連 `lb://` 帶佔位符都解不開）與 **21 條 DB 邊**（每個服務自己的 mysql/mongo，來自 manifest 佈線＋JPA 證據）。節點 100 個（54 服務 + 44 資料庫 + 2 外部），第一版曾是 114（多了 prometheus、grafana、EFK 等監控 workload，第十條規則後移除）。**`docs/train-ticket-greenfield-graph.mmd` 刻意不換**：它是 threshold-design §8 子圖實驗的固定輸入，換掉數字就得重跑；新圖放在 `docs/generalization/train-ticket.mmd`。
- 既有測試全過：`DependencyGraphTest` 71（petclinic runtime 合併、BoA greenfield fixture、train-ticket 路徑點名 fixture）等。
- 新增 `GreenfieldGeneralizationTest`（merger 層，每條規則一個最小 fixture，15 個）、`ExtractionGeneralizationTest`（extractor 層，temp 目錄，6 個）、`GeneralizationScoreTest`（計分，1 個）。
- 完整套件 `mvn -o test`：246 個，245 過，唯一失敗是需要叢集的 `McpToolkitCallToolTest`（本機沒有叢集，與本次無關）。
- 探針本身：`GreenfieldProbeTest`（比舊的 `StaticExtractionProbeTest` 多跑 db 提升、normalize、分層，並輸出 `.mmd` 與逐邊摘要 `.summary.md`，後者就是計分的輸入）。

## 6. 對「通用性」問題的誠實回答

1. **抽取層（tree-sitter + 設定檔）本來就通用**，這次六個專案 0 個語法錯誤；問題全在**合併層的假設**——第一版假設「模組目錄名＝部署名」、「設定只在 `application.yml`」、「compose 是某個目錄的設定」、「URL 字串出現就是呼叫」，這些在三個舊專案剛好都成立，所以之前看不出來。
2. 九條規則都是**看到的形狀**而不是專案名；每條有對應測試；但它們的來源是六個專案，第七個專案很可能再逼出第十條。**方法本身可重複**：clone → 跑 probe → 對架構圖 → 每條差異追到一條規則或一個明確的語言/型態邊界。
3. 工具靜態層的三個明確邊界，這次量出來了：**沒有文法的語言**（Online Boutique 13/16、robot-shop 2/3 的漏邊）、**registry/enum 動態分派**（TeaStore 12/13）、**跨行拼接的 host**（robot-shop 1/3）。這三個都是 runtime 層要補的，而不是靜態層再加規則能解決的。
4. 一個新的精確度來源被量出來：**「宣告但沒使用」**——ecommerce 25 條常數邊有 19 條沒人引用。這個規則對 train-ticket 這種「每個服務自己拼 URL」的專案沒有影響（它們的 URL 在呼叫點），只影響「常數檔複製到每個服務」的風格。

## 6b. 2026-09-25 的續篇

第 6 節第 2 點說「第七個專案很可能再逼出第十條」——實際上是**七個新專案逼出三條**（§3 的 11–13），而且這一次的參考邊集不是作者寫的（`docs/generalization-external.md`）。既有六個專案加 BoA、train-ticket 在同一份 checkout 上重跑，**節點與邊逐項不變**（唯一變動：piggymetrics 少一個沒有任何邊的雜訊節點）。

## 7. 待辦

- **TeaStore 上機器 A 跑 runtime**：runbook 在 `docs/teastore-runtime-runbook.md`，目標是「靜態 1/13 → runtime 13/13，工具與 TeaStore 都不改」。
- 第三輪 top-k / 路由 hold-out 改用這六個專案之一的 archive（原待辦；要機器 B 與 API 額度）。
- 再收新專案的方法已固定：clone → `GreenfieldProbeTest` → 寫 `truth/<name>.tsv` → `GeneralizationScoreTest` → 每條差異追到一條規則或一個邊界。
