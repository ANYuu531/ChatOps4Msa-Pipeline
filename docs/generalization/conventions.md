# 四個慣例：61 條規則其實在回答的四個問題（2026-11-03）

一條相依邊 A → B 成立，要回答四個問題：

| 慣例 | 問題 | 依據（官方文件；連結待查核） |
|---|---|---|
| ① 名字表 | 誰能被連？部署宣告了哪些名字、別名，哪個名字是哪個服務 | Docker Compose 網路（服務名、`aliases`、`hostname`、`links` 都是 DNS 名）<https://docs.docker.com/compose/how-tos/networking/>；Kubernetes Service 的 DNS 與 selector <https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/>、<https://kubernetes.io/docs/concepts/services-networking/service/>；保留網域 RFC 2606／6761 |
| ② 位址形狀 | 什麼值是位址？ | URI 語法 RFC 3986；JDBC URL、ADO.NET 連線字串；Twelve-Factor「backing service 以 URL 或定位字串寫在設定裡」<https://12factor.net/backing-services> |
| ③ 歸屬 | 這個值送到誰手上？ | Compose `environment`／`env_file` <https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/>；k8s `env`／`envFrom`／`configMapKeyRef` <https://kubernetes.io/docs/tasks/configure-pod-container/configure-pod-configmap/>；Spring Boot 外部化設定（環境變數的寬鬆綁定）<https://docs.spring.io/spring-boot/reference/features/external-config.html>；ASP.NET Core 設定（`__` 對應 `:`）<https://learn.microsoft.com/en-us/aspnet/core/fundamentals/configuration/> |
| ④ 使用 | 程式有沒有在呼叫點用它？決定畫不畫、畫成哪個等級 | 自己的方法：靜態分析區分宣告與使用 |

另有**格式轉接器**：讀得懂某種檔案或語言，本身不判斷相依。

## 61 條規則的歸屬

每條只歸到一個主要的慣例；有幾條同時碰到兩個，以它改變結果的那一步為準。

| 歸屬 | 條數 | 規則 |
|---|---|---|
| ① 名字表 | 18 | 1 workload 名清單、2 模組目錄正規化、8 localhost 不是節點／Feign bean id、10 只認部署自己模組的目錄、11 Compose 服務名、15 localhost:PORT 以 port 找回擁有者、19 版本字尾、21 Python 函式庫不是服務、25 links／network 別名、31 測試 fixture、33 保留網域、36 只剝真正的 DNS 字尾、37 Compose hostname、41 JVM 函式庫、42 測試樹、44 build context 對應模組、54 namespace、59 Service selector |
| ② 位址形狀 | 9 | 4 佔位符與預設值、5 裸主機名、26 scheme 決定種類、27 位址清單鍵、32 key=value 連線字串、39 形狀決定種類、45 *.url 下的裸單字、55 一個值多個位址、58 .NET Options 鍵與任意區段的連線字串 |
| ③ 歸屬 | 15 | 3 Config Server 倉庫、6 反向代理上游、7 Compose environment、14 workload 自己的 env 與共用來源、18 Dockerfile ENV、20 讀取者自己的範圍、23 共用設定鍵要有對應函式庫、24 鍵名轉發與 .env 內插、34 Envoy、38 YARP、50 模組裡的 dotenv、53 命令列、56 入口路由與掛載歸屬、60 掛載的設定檔、61 Ocelot |
| ④ 使用 | 13 | 9 沒人引用的 URL 常數、13 XML namespace、16 隱含註冊中心、17 等於模組名的常數、22 Python 死碼、29 OpenAPI 中繼資料、30 訊息程式碼的邊是服務 → broker、35 depends_on 分級、43 OpenAPI UI 設定鍵、47 呼叫點的變數綁定、48 環境變數讀取的預設值、49 註冊中心名稱、51 瀏覽器端腳本 |
| 格式轉接器 | 6 | 12 Compose v1 格式、28 Kotlin、40 C#、46 nginx 變數、52 Helm 渲染、57 多文件 YAML |

## 同一個慣例被逐格式各寫一次的地方

2026-11-03 的邊審查（279 條，`mining/README.md`「邊審查第二版」）確定錯的 11 條，全部出在同一個慣例只在部分格式上實作：

- **③ 共用來源（7 條）**：第 14 條說「共用 ConfigMap 注入的服務位址不畫」，但只套在 ConfigMap 的服務種類；資料庫與 broker 照畫，Compose 的 `env_file`（第 24 條：每個鍵都是該服務的）完全沒套。
- **④ URL 字串（4 條）**：第 47 條規定 JS／TS／Go／PHP 的 URL 字面值只在呼叫點才算，Java／C#／Kotlin／Python 的查詢檔卻抓「任何地方」的 `http(s)://` 字串，再用第 9、13、29、43 條逐個排除；漏網的是 RFC 連結、ProblemDetails type、測試帳號 claim、Swagger 授權連結。

## 統一成一份實作之後（第 14、47 條放寬，不加新編號）

**③：值是誰選的。** 寫在 workload 自己 env 區塊的、`configMapKeyRef` 指定的、Compose 以鍵名轉發的，是為這個 workload 選的，算它的（服務位址畫成僅推測，同第 14 條）。整包注入的——`envFrom` ConfigMap、`env_file`——沒有替誰選鍵；workload 讀了這個鍵（模組的程式碼或自己的設定檔提到鍵名、或框架有文件寫明的綁定：Spring Boot 的 `SPRING_*`、ASP.NET Core 的 `A__B`），才把其中的**資料庫與 broker** 算給它；服務位址與外部主機仍交給程式碼層在呼叫點判斷，因為「讀了」不等於「呼叫了」（AICS 的 `SYSTEM_ADDRESS` 是系統自己的網域，只拿來組 email 連結）。實作：`ConfigExtractor.recordKeyReads`（`key-read` 列）、`CodeGraphMerger.mergeWorkloadWiring`。

**④：URL 字面值只在使用處才是位址，所有文法一樣。** 使用處是有呼叫點列（HTTP client、gRPC、Feign、producer／consumer、ORM）的那一行或前後兩行，或是一行呼叫了各生態系文件化的 client API（`TreeSitterExtractor.CLIENT_CALL`：RestTemplate、WebClient、HttpClient、requests、httpx、urllib、feedparser、Apache HttpClient、Serilog sink…）。字面值存在名字裡時（`ORDER_API = "http://…"`、`builder = fromHttpUrl("http://…")`、`@Value("http://${X}") String uri`），沿同一檔的賦值追三步，名字（含 JavaBeans 的 `getX()`）出現在同模組的使用處就算。實作：`TreeSitterExtractor.keepUrlLiteralsAtUseSites`。

`CLIENT_CALL` 是詞彙表，不是規則：它列的是「哪些呼叫會發出連線」，新增一個 client 函式庫是加一個詞，不改判斷。

### 量測

| 量測 | 結果 |
|---|---|
| 23 個回歸系統逐邊 | 21 個不變；spring-cloud-netflix 少 2 條 → github.com（Swagger `ApiInfo` 的說明網址，第 29 條漏掉的建構子） |
| AICS | P 0.90 → **0.97**、R 0.76 不變（少了 3 條錯的外部邊） |
| hold-out B／C／D | 不變（回歸系統內） |
| 邊審查確定錯的 11 條 | **11 條全部消失** |
| 100 個語料系統 | 21 個系統改變，邊 +16／−137 |
| 測試 | 新增 `ConventionsTest` 4 個（審查找到的錯邊各取最小形）；改 2 個既有測試的夾具（見下） |

語料 −137 條逐群查證：**約 121 條是錯邊**（hoangtien 一個 ConfigMap 給 14 個服務的 35 條、uid4oe 兩個 repo 共用 `.env` 的 27 條、Swagger 授權與聯絡人／RFC／ProblemDetails／claim／CORS／JWT issuer／圖片網址 38 條、mostafacs 與 ebosas 共用 env_file 的 14 條、沒人用的 TikTok 設定 3 條、booking-expressjs 兩個資料庫容器互連 2 條、matecloud 的範本與註解 2 條）；**13 條是真的邊被拿掉**，3 條不確定（mostafacs 的 gateway → authentication-server 等）。被拿掉的真邊有四種原因，都是方法的邊界，不是規則寫錯：

1. Compose 服務只寫 `image:`、沒有 `build:`，程式碼目錄連不到服務名，讀了鍵也算不到它身上（uid4oe 6 條）。
2. 字面值經過自己寫的函式參數才到 client（`scrapeFromPlatform("Amazon", "https://amazon.com/…")`，4 條）。
3. 第三方映像讀自己的環境變數（nacos 讀 `MYSQL_SERVICE_HOST`），repo 裡看不到讀取（1 條）。
4. 詞彙表沒有的 client（`JwkProviderBuilder(URL(…))`，1 條）；ebosas 的 server → redis（build context 是整個 repo，1 條）。

+16 條全在 SaiUpadhyayula：`configMapKeyRef` 逐鍵指定的服務位址，現在和 env 區塊同等畫成僅推測（api-gateway → product-service、keycloak，loki／tempo 為遙測）。

### 改了夾具的既有測試

- `DependencyGraphTest` 的 Bank of Anthos 夾具補上 `key-read` 列（Spring 的 `SPRING_DATASOURCE_URL` 綁定）；真實流程中由 `ConfigExtractor` 產生，手寫的 ledger 夾具沒有。另加一個測試：同一個 ConfigMap 給了不讀它的 frontend，不畫。
- `ExtractionGeneralizationTest` 的 URL 常數夾具：原本以 `return AppConstant.PRODUCT_URL + "/1"` 當「有引用」，那不是呼叫；改成 `restTemplate.getForObject(AppConstant.PRODUCT_URL + "/1", …)`。

## 慣例①、②也統一之後（2026-11-03 晚）

**② 位址形狀：一個類別 `AddressShape`。** 擷取器與合併器原本各有一份：兩份 loopback 集合（合併器多了 `::1` 與 `.internal`）、兩份保留網域判斷（只有合併器認 `example.com`）、兩份 scheme → 種類表、三個主機解析器，以及**六份**「這個鍵存的是位址」的字詞表（環境變數、Spring 設定鍵、命令列旗標、.NET 鍵、佔位符名稱、Spring 設定檔的相關鍵過濾），彼此不一致——`FOO_URL=bar` 在環境變數是主機、`foo.url: bar` 在設定檔不是；`spring.zipkin.base-url` 因為以 `-url` 結尾而不被認得。現在：

- 鍵：拆成字（`.`、`:`、`__`、`_`、`-`、camelCase 一律拆），看最後一個字或最後兩個字合起來：host 類（host、hostname、addr、address、server、serviceid…）、清單類（bootstrap-servers、server-addr、uris、brokers…）、URL 類（url、uri、endpoint）、連線字串類。**不是目標的鍵**：誰可以連進來或自己的對外位址（CORS origins、AllowedHosts、redirect／callback／logout URI、site_url、public／canonical／advertised）與 JSON 的註解鍵（`"//…"`、`_comment`）。
- 值：URI（RFC 3986；`external:pulsar://h` 這類標籤取 `://` 前最後一個名字當 scheme）、JDBC／R2DBC、`key=value;` 連線字串、`host:port`、同一層的 host 鍵＋port 鍵（`{"host": "localhost", "port": 5432}`，DB_HOST＋DB_PORT 的同一件事）。host 類鍵的值本身也要像主機（`prefer-ip-address: true` 不是）；URL 類鍵下沒有 scheme 的值是相對參照（RFC 3986 §4.2），不是主機，只有 endpoint 鍵接受裸 FQDN；非網路 scheme（`vault://`、`file:`、`classpath:`）沒有主機；範例佔位（`xxx.servicebus.windows.net`、`your-server`）比照保留網域。
- 檔案種類不再決定讀不讀：任何 YAML／JSON 設定檔（工具清單 package.json、tsconfig、launchSettings 等與測試設定除外）的位址都是候選；擁有者依③（唯一掛載它的容器，或它所在的模組，且擁有者要是部署宣告的元件），目標依①只連宣告過的名字或 port 的擁有者，不建新節點、不畫外部主機。這把第 60 條（掛載檔）推廣到模組自己的設定檔。

**① 名字表：一個解析函式 `resolveTarget`。** 合併器原本有約十條解析路徑（depends_on、入口路由、env 讀取、env 清單、呼叫點、`@Value`、一般同步呼叫、佈線、掛載檔…），各自串一次「別名 → loopback → 外部 → 對節點 → 建新節點」，順序與寬嚴不同：`localhost:PORT` 以 port 找擁有者（第 15 條）只在一般同步呼叫做、別名只有部分路徑查、比對有的嚴格有的寬鬆。現在固定順序：保留網域 → 別名 → loopback → 外部 → 宣告過的名字 → 新名字，呼叫端只決定「可不可以到外部」「可不可以建新節點」。

- loopback 的界線：部署描述檔的環境變數在容器裡讀，`localhost` 是容器自己；模組自己的設定檔與程式碼預設值在開發者機器上也會讀，`localhost:PORT` 是在那個 port 上的服務——先找模組自己宣告的 `server.port`（同一層），沒有才找 Compose `ports:` 發佈到主機的那一個（新讀的 `compose-port` 列）；零個或多個擁有者就不畫。

### 量測（全部對照只統一③④時）

| 量測 | 只統一③④ | ①②③④都統一 |
|---|---|---|
| 23 個回歸系統 | 21 個不變 | 15 個不變；差異逐條查證（下） |
| 7 個歸納系統 P／R | 1.00／1.00 | **0.93**／1.00（ecommerce 多 10 條各服務 → zipkin-container：`spring.zipkin.base-url` 現在認得了，是真的遙測，但這組參考邊集合 9/22 寫成時還沒有「遙測列 variant」的慣例；2026-11-03 晚依檔頭慣例補列 variant 後 P 回到 1.00，見下一節） |
| hold-out B（3 系統） | 0.99／0.99 | 0.98／0.99（多 1 條 mall-mbg → mysql：MyBatis 程式碼產生器的設定，mall-mbg 因第 41 條「有 main 就不是函式庫」的例外仍是節點） |
| hold-out C | 0.85／0.97 | **0.87**／0.97 |
| hold-out D | 0.71／1.00 | 0.71／1.00 |
| hold-out E（**已見過**） | 0／8 | **8／8**，P 0.73（3 條 → otelcol 是送遙測給 OTel Collector，參考邊集合檔頭的慣例會列 variant 但當初漏列；未改參考邊集合） |
| AICS | 0.97／0.76 | 0.97／0.76 |
| MicroDepGraph | 93／95 | 93／95 |
| 100 個語料系統 | — | 對只統一③④：44 個系統改變，+260／−40；固定種子抽 40 條新增邊 35 條正確（go-zero 的 etcd／RocketMQ／Redis、各種 `localhost:PORT` 開發設定、`lb:http://` gateway 路由）；錯的 5 條：來源歸到目錄名 3 條、給瀏覽器的連結網址 1 條、port 對錯服務 1 條。拿掉的 40 條多是垃圾（`-> optional`、`-> true`、`-> lb`、佔位主機、`*.domain.com,http`） |
| 測試 | 349 | 352（`ConventionsTest` 7 個） |

回歸差異：spring-cloud-microservice 的 gateway → uaa-service 改連到同一服務的既有節點 cloud-uaa-service（寬鬆比對統一）；yas 多 recommendation／webhook → postgres（參考邊集合本來就列 variant）；petclinic 多 prometheus → 各服務（遙測）；lakeside-mutual 多 risk-management-client → risk-management-server（client 經 gRPC 連 server）；Tap-And-Eat 的 Compose 檔說 configserver 發佈 8888、DiscoveryService 的 server.port 也是 8888——「同一層先」讓 `localhost:8888/eureka` 仍對到 discovery。

**E 的 8/8 不是轉移結果**：E 是在探勘時抽的 hold-out，它的缺口（模組 JSON 設定檔裡的 `localhost` 加 port）在上一輪已經被我看過、寫進論文當「探勘的盲點」。統一①②之後補上了，但要量轉移，得另外抽一個沒看過的系統。

已知仍會錯的：來源歸屬退回到目錄名（`resources`、`quickstart`）、給瀏覽器的連結網址（`Url:Website`、`SpaBaseUrl`，靠鍵名分不出來）、第 41 條的 main 例外留下的工具模組。

## `depends_on` 是啟動順序；補遙測 variant；smtplib（2026-11-03 夜）

**`depends_on` 的慣例。** Compose 的 `depends_on` 說的是「先啟動它」，不是「我呼叫它」。三種結局：（a）來源自己的位址也指名同一個目標 → 兩個宣告一致，呼叫邊 documented；（b）來源是**這個 repo 自己的程式碼跑的容器**（compose 有 `build:`，或某個模組目錄對得上它，含第 44 條的映像名等式）而且**哪裡都沒寫位址** → 作者對自己程式碼需求的唯一陳述，呼叫邊 inferred（hotelReservation 的 Go 服務：位址在沒有模組擁有的根目錄 config.json；Compose v1 時代）；（c）其他——**第三方映像**（它的接線在映像裡、讀不到，工具沒有依據主張一條呼叫：skywalking-showcase 的 node-exporter 等 oap 啟動但從不連它、curl 容器等 exporter）、或來源的位址指名別人卻沒指名它 → 保留為**啟動順序關係**（邊型別 `startup`，最後才畫、不蓋掉程式碼說的呼叫），摘要檔另列一區「`~>`」，不進任何計分、覆蓋率也不算。`links` 不同：v1 格式沒有 link 就連不到名字，所以 link 一律照舊（沒位址＝唯一接線陳述、inferred）。這取代第 35 條「沒位址就畫」的做法：舊做法 61 條對、9 條錯，錯的 8 條全在 skywalking-showcase 的第三方映像。

**先試過更純的版本**（`depends_on` 一律不當呼叫）：hotel-reservation 22 條、robot-shop 5 條、MicroDepGraph 組 6 條真邊消失——它們的程式碼證據要嘛在沒人擁有的共用設定檔，要嘛模組對不上 Helm 的名字（robot-shop 的 cart 在 Helm 詞彙下「not-preadded」），`depends_on` 一直在替這些缺口撐著。所以留下（b）這一款，並把它寫成「作者對自己容器的陳述」而不是「沒位址就畫」。

**②補一條**：`http:${PROMETHEUS_ADDR}` 這種有 scheme 沒有 `//` 的值是 opaque URI（RFC 3986 §3），沒有主機；只限 web scheme，`mongodb:27017` 仍是叫 mongodb 的主機加 port。E 原本多畫 `jaeger -> http`。

**④補 Python 標準函式庫的 SMTP client**：`smtplib.SMTP(` 進呼叫詞彙；配合②在程式碼裡的新形狀——裸主機名字面值放在 host 形狀的名字下（`smtp_server = 'smtp.gmail.com'`），由 `AddressShape.keyKind` 判名字、再走 url 列所以④照樣要求到達呼叫點。AICS 補上 mail-service → smtp.gmail.com。

**參考邊集合補列**（依各檔頭既有的「遙測列 variant」慣例，更正紀錄在 `truth/README.md`）：ecommerce 10 條 `→ zipkin-container`、E 3 條 `→ otelcol`。

### 量測（對照「①②③④都統一」那一欄）

| 量測 | 都統一 | ＋本節 |
|---|---|---|
| 23 個回歸系統 | — | 對 r7 基準 23 個逐邊不變（最後一次）；對統一後的輸出，只差 skywalking-showcase −16（7 條錯邊、9 條 variant／真邊改列啟動順序）與 AICS ＋smtp |
| 7 個歸納系統 | 0.93／1.00 | **1.00／1.00**（補列 variant 後） |
| hold-out B | 0.98／0.99 | 不變 |
| hold-out C | 0.87／0.97 | 不變 |
| hold-out D | 0.71／1.00 | **0.87**／1.00（skywalking-showcase P 0.44 → 0.73，錯邊 10 → 3：loadgen → app 是自家映像的 depends_on、songs → gateway 沒人注入的 RestTemplate bean、executejob 名字對不上） |
| hold-out E（已見過） | 8/8，P 0.73 | **1.00／1.00** |
| AICS | 0.97／0.76 | 0.97／**0.78**；只算叢集內 0.97／0.94 |
| MicroDepGraph | 93／95 | 不變 |
| 啟動順序關係 | — | skywalking-showcase 27、opentelemetry-demo 5、yas 1 條，各自列在摘要檔的 startup order 區 |
| 測試 | 352 | 354（`ConventionsTest` 9 個），只有既有的 MCP 連線測試失敗 |

輸出：`holdout*/…r7.*`、`scores-r7.md`、7 個歸納系統與 external 的 summary／mmd／rules 已覆蓋，`regress.sh` 基準改 r7；AICS 在 `~/Documents/aics-eval/out/2026-11-03-r7`。

## 試過、退回：服務自己 env 區塊裡的服務位址交給程式碼層決定（2026-11-03 夜）

想法：慣例③對整包 ConfigMap／env_file 已經要求「程式碼讀了才算、且只畫 db/broker」，把同一個要求延伸到服務自己的 env 區塊——程式碼讀得到的容器，服務類目標由程式碼層畫（呼叫點＋env 表＝documented），描述檔只替第三方映像畫。目標是 yas 那 10 條「交到手上但從不呼叫」的錯邊。

量到的代價：yas −16 條真邊（cart → product、rating → customer／order、order → cart／customer／product／tax…）、TeaStore −5（→ registry）、opentelemetry-demo −4、Bank of Anthos 與 Online Boutique 各 −1（loadgenerator → frontend）、lakeside −1；C 的 P 0.87 → 0.97 但 R 0.97 → 0.78，歸納組 R 1.00 → 0.95。原因都一樣：這些邊程式碼確實有用，但程式碼層跟不到——Spring `@ConfigurationProperties(prefix)` 綁到 record 的欄位、再經 `serviceUrlConfig.product()` 進 `fromUriString(...)`；TeaStore 的 registry 讀法；負載產生器讀 env。描述檔的 inferred 邊一直在替這個缺口撐著，跟 `depends_on` 的情形一樣。

結論：退回（程式碼已還原，留註解）。要讓這條延伸成立，先決條件是慣例④多一個 Spring 綁定轉接器：`@ConfigurationProperties(prefix)` ＋ 欄位 ↔ 屬性鍵 ↔ 環境變數的寬鬆綁定（`yas.services.product` ↔ `YAS_SERVICES_PRODUCT`），呼叫點經存取子追到屬性。做到之後 yas 的錯邊（storefront 綁了 `yas` 整個 map 但沒人用）與真邊（cart 的 `product()` 有進呼叫點）才分得開。

## 慣例④補三個綁定轉接器，③的延伸正式啟用（2026-11-04）

上一節退回的延伸（程式碼讀得到的容器，自己 env 區塊裡的服務位址交給程式碼層畫）掉的 27 條真邊，逐條追到是哪種寫法讓④追不到，補了三個轉接器——都是「值從哪來」的新路徑，不是新規則：

1. **Spring 的三段式綁定**（`TreeSitterExtractor.bindConfigurationProperties`）：環境變數 `YAS_SERVICES_PRODUCT` ↔ 屬性鍵 `yas.services.product`（寬鬆綁定，合併器 `lookupPlaceholder` 補查大寫底線形）↔ `@ConfigurationProperties(prefix = "yas.services") record ServiceUrlConfig(String product)` 的欄位 ↔ 呼叫點的存取子 `fromUriString(serviceUrlConfig.product())`。綁定類別可以在共用函式庫模組，所以全 repo 收集；同名類別同 prefix 的欄位聯集（yas 每個模組各有一份 `ServiceUrlConfig`）。`Map` 欄位的 `services().get("cart")` 是 `prefix.services.cart`。存取子要在使用處（呼叫點列或呼叫詞彙 ±2 行；`fromUriString`／`fromHttpUrl` 進詞彙），才成為帶 `${鍵}` 的 config 列，走 `@Value` 同一條解析路徑。
2. **映像的命令列與啟動腳本**（`ConfigExtractor.extractDockerfileEnv`）：Dockerfile 的 `ENTRYPOINT`／`CMD` 和同目錄的 `*.sh` 裡的 URL（佔位符保留）是 url 列，歸 Dockerfile 所在的模組；合併器用該模組 workload 的 env 表填佔位符。Online Boutique／Bank of Anthos 的 `locust --host="http://${FRONTEND_ADDR}"` 由此畫出。port 的佔位符（`:${REGISTRY_PORT}`）表裡沒有就去掉，主機照解。
3. **FROM 鏈**：`dockerfile-from` 列（每個 Dockerfile 建在哪個映像上）＋ `image-build` 列（建置腳本的 `docker build -t 映像 目錄`、Compose 的 `build:`＋`image:`）→ 基底映像目錄的列（命令列、啟動腳本）分派給每個建在它上面、而且有部署的服務（`baseImageChildrenOf`）。TeaStore 的 `dockerbase/start.sh` 把 `http://${REGISTRY_HOST}:${REGISTRY_PORT}/…` 寫進 Tomcat 的 context.xml，五個服務各自以自己的 `REGISTRY_HOST` 解成 teastore-registry；all-in-one 映像也建在基底上但沒有 manifest 跑它，不繼承。JNDI 本身沒做：只讀 web.xml 拿到的是 `localhost:8080` 預設值，五個服務都在 8080，解不出來。

**③的延伸**（`mergeWorkloadWiring` 的 `code-decides`）：程式碼讀得到的容器——「讀得到」＝某個模組目錄對得上它、而且文法真的解析過那個目錄的檔案（`parsed-dir` 列；Rust、Ruby 模組沒有，視同第三方映像）——自己 env 區塊裡的服務類位址不再由描述檔畫，由程式碼層畫；db／broker 仍由描述檔畫（框架從鍵綁定、不用程式碼）。先用建置標記檔猜語言是錯的（標記檔常是 Dockerfile，Java 模組被當成不可讀），改成看解析紀錄。

### 量測（對照上一節「都統一＋depends_on」）

| 量測 | 之前 | 現在 |
|---|---|---|
| 7 個歸納系統 | 1.00／1.00 | 1.00／1.00（TeaStore 5 條 → registry 改由 start.sh 出處、documented） |
| hold-out B | 0.98／0.99 | 0.98／0.97（otel-demo 漏 load-generator → frontend-proxy：`LOCUST_HOST` 是 locust 框架自己讀；fraud-detection → flagd：flagd SDK 自己讀） |
| hold-out C | 0.87／0.97 | **0.98／0.98**（yas 錯 13 → 2、漏 3 → 2；多對 order → promotion；剩的錯邊是 `application-dev.yaml` 的 payment／order，模組設定檔那一路這次沒動） |
| hold-out D | 0.87／1.00 | 不變 |
| hold-out E（已見過） | 1.00／1.00 | 不變 |
| AICS | 0.97／0.78 | 不變 |
| MicroDepGraph | 93／95 | 不變（lakeside 少的 risk-management-server → policy-management-backend-queue 本來就是多畫的） |
| 回歸 23 系統 | — | 對 r7 基準：teastore 5 條換出處、yas −10 錯邊＋1 真邊、otel-demo −6（4 條 variant、2 條如上） |
| 測試 | 354 | 357（`ConventionsTest` 12 個） |

**還會掉的，寫成界線**：套件或 SDK 自己讀 env（locust 的 `LOCUST_HOST`、OpenFeature 的 `FLAGD_HOST`）——要補就得維護一份「套件→它讀哪個 env」的表，跟 db／broker 的框架綁定是同一類，但目前只有這兩個案例，不做。

輸出：`holdout*/…r8.*`、`scores-r8.md`、歸納與 external 的 summary／mmd／rules 已覆蓋，`regress.sh` 基準改 r8；AICS 在 `~/Documents/aics-eval/out/2026-11-04-r8`。
