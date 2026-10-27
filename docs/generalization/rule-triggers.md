# 46 條規則在 20 個系統上各觸發了幾次（2026-10-27）

回答「這不就是把例子列舉一遍嗎？」：每條規則都是在 1–3 個系統上歸納出來的；若它在很多沒參與歸納的系統上也會改變結果，它抓到的是業界的慣例，不是那個系統的特例。表的每一格是 `RuleTrace` 在該系統的靜態層分析中記到的**不同事物數**（邊、節點、名稱、檔案；同一個東西重複碰到算一次），由 `GreenfieldProbeTest` 以 tag `trace` 跑在與 r3／現行輸出同一份 checkout 上得到（邊逐條相同，見下方），原始檔是各目錄下的 `<name>.trace.rules.json`，表由 `docs/generalization/rule_triggers.py` 產生。

計數的位置是規則**改變結果**的地方：畫出／不畫／改等級一條邊、建立／合併／壓掉一個節點、解開一個名稱；純解析的規則（第 12 條 Compose v1、第 28／40 條薄表面）數它解析的檔案或服務。每條規則對應的程式位置與 token 形狀列在文末。

robot-shop 有兩個 checkout（歸納用的 2024 版與 MicroDepGraph 對齊的 2019 版），兩欄分開列、「觸發系統數」只算一次，所以欄數是 21、系統數是 20。

## 全表

| # | 規則（首句） | 歸納自 | Bank of Anthos | ewolff k8s | ecommerce | piggymetrics | TeaStore | Online Boutique | robot-shop (2024) | train-ticket | spring-petclinic | microservices-book | Tap-And-Eat | spring-cloud-netflix | spring-cloud-microservice | LakesideMutual | robot-shop (2019) | opentelemetry-demo | mall-swarm | hotelReservation | yas | run-aspnetcore-microservices | AICS (private) | 觸發系統數 | 合計 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | manifest 的 workload 名稱（Deployment、Sta… | ewolff、TeaStore、Online Boutique | 11 | 7 | 0 | 0 | 14 | 13 | 13 | 78 | 0 | 0 | 0 | 0 | 0 | 12 | 13 | 0 | 0 | 0 | 23 | 0 | 22 | 9/20 | 206 |
| 2 | 模組目錄名正規化 | TeaStore、Online Boutique、ewolff | 0 | 1 | 0 | 0 | 15 | 1 | 0 | 2 | 1 | 1 | 16 | 0 | 10 | 0 | 0 | 1 | 0 | 0 | 0 | 6 | 21 | 11/20 | 75 |
| 3 | Spring Cloud Config 倉庫版面 | piggymetrics | 0 | 0 | 8 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 6 | 0 | 8 | 0 | 0 | 0 | 20 | 0 | 0 | 0 | 0 | 5/20 | 84 |
| 4 | 佔位符依序查環境變數、屬性表、${key:default} 的預設值，最後… | piggymetrics、ewolff、robot-shop | 1 | 2 | 1 | 1 | 0 | 0 | 6 | 126 | 0 | 2 | 0 | 0 | 2 | 1 | 6 | 13 | 2 | 0 | 4 | 0 | 0 | 12/20 | 167 |
| 5 | 裸主機名也是目標 | piggymetrics、TeaStore、robot-shop | 0 | 0 | 0 | 9 | 9 | 0 | 7 | 3 | 0 | 0 | 0 | 0 | 6 | 2 | 7 | 34 | 8 | 0 | 11 | 0 | 6 | 10/20 | 102 |
| 6 | 反向代理設定是入口的邊清單 | ewolff、robot-shop | 0 | 3 | 0 | 0 | 0 | 0 | 6 | 1 | 0 | 0 | 0 | 0 | 0 | 1 | 6 | 0 | 0 | 0 | 23 | 0 | 0 | 5/20 | 40 |
| 7 | Compose 描述的是服務之間的連線設定，不是某個服務的設定 | TeaStore、robot-shop、ecommerce | 0 | 0 | 0 | 0 | 25 | 0 | 5 | 0 | 0 | 0 | 1 | 0 | 0 | 20 | 0 | 1 | 1 | 13 | 18 | 8 | 0 | 9/20 | 92 |
| 8 | 不是目標的東西不畫 | ecommerce、piggymetrics、TeaStore | 3 | 1 | 28 | 10 | 0 | 0 | 0 | 7 | 0 | 9 | 1 | 0 | 7 | 10 | 0 | 16 | 27 | 0 | 18 | 9 | 1 | 14/20 | 147 |
| 9 | 宣告不等於使用 | ecommerce、TeaStore | 8 | 4 | 29 | 4 | 58 | 0 | 2 | 48 | 6 | 4 | 5 | 0 | 1 | 29 | 0 | 0 | 4 | 0 | 77 | 0 | 0 | 14/20 | 279 |
| 10 | 只認「部署了這個 repo 自己模組」的 manifest 目錄 | train-ticket、Bank of Anthos | 5 | 0 | 21 | 0 | 0 | 1 | 1 | 13 | 0 | 0 | 5 | 0 | 0 | 0 | 1 | 0 | 6 | 51 | 0 | 0 | 4 | 9/20 | 108 |
| 11 | Compose 的 services 也是部署名稱清單，與 Kuberne… | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 0 | 0 | 0 | 14 | 1 | 0 | 1 | 1 | 19 | 10 | 0 | 9 | 25 | 1 | 1 | 36 | 1 | 1 | 1 | 11 | 0 | 14/20 | 132 |
| 12 | 也認 Compose 第 1 版格式（沒有 services:，頂層即服務… | Tap-And-Eat、spring-cloud-microservice | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 5 | 28 | 27 | 1 | 0 | 0 | 16 | 0 | 0 | 0 | 0 | 6/20 | 82 |
| 13 | XML namespace 不是位址 | LakesideMutual | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/20 | 3 |
| 14 | workload 自己環境變數裡的服務位址是宣告，畫成僅推測；共用 Con… | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 10 | 0 | 0 | 0 | 5 | 16 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 20 | 0 | 76 | 0 | 0 | 48 | 2 | 42 | 9/20 | 220 |
| 15 | loopback 位址以 port 讀回擁有者 | ecommerce | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 0 | 17 | 13 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 5/20 | 48 |
| 16 | Spring Cloud 的隱含註冊中心 | ecommerce | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3/20 | 20 |
| 17 | 字串值等於模組名的常數就是被呼叫端 | TeaStore | 0 | 0 | 0 | 0 | 7 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/20 | 7 |
| 18 | String.format("http://%s/…", getenv("… | robot-shop | 0 | 0 | 0 | 0 | 1 | 0 | 10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 0 | 2/20 | 20 |
| 19 | 帶版本字尾的 Deployment 是某個 Service 的一個版本 | AICS（私有系統） | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9 | 2/20 | 10 |
| 20 | 環境變數在讀取者自己的範圍解析 | AICS | 6 | 0 | 0 | 0 | 0 | 2 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 8 | 23 | 0 | 0 | 0 | 8 | 22 | 6/20 | 77 |
| 21 | 函式庫不是服務 | AICS | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 1/20 | 7 |
| 22 | 呼叫點才算使用（Python） | AICS | 4 | 0 | 0 | 0 | 0 | 0 | 1 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 11 | 0 | 0 | 0 | 0 | 43 | 5/20 | 66 |
| 23 | 共用設定的 key 只在有對應函式庫的 client 生效 | piggymetrics | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/20 | 24 |
| 24 | Compose 的鍵名轉發與 .env 內插 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 94 | 0 | 0 | 59 | 0 | 0 | 2/20 | 153 |
| 25 | links 與 external_links 的別名是該服務對目標的稱呼 | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 14 | 0 | 0 | 0 | 0 | 1/20 | 14 |
| 26 | 連線 scheme 決定節點種類 | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0/20 | 0 |
| 27 | 位址清單 key 與裸 FQDN 的 endpoint | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 8 | 0 | 3 | 0 | 0 | 3/20 | 14 |
| 28 | Kotlin 以薄表面讀 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 1/20 | 3 |
| 29 | OpenAPI／Swagger 中繼資料的 URL 不是呼叫 | mall-swarm、train-ticket | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 3/20 | 4 |
| 30 | 訊息程式碼的邊是服務 → broker | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 3 | 0 | 0 | 2/20 | 4 |
| 31 | 測試 fixture 不是服務（規則 2 的延伸） | opentelemetry-demo、hotelReservation | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 3/20 | 3 |
| 32 | key=value; 連線字串的主機 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 2 | 0 | 2/20 | 3 |
| 33 | 保留 TLD 不是相依 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 | 0 | 2/20 | 2 |
| 34 | Envoy 靜態設定是反向代理的上游表（規則 6 的延伸） | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 0 | 0 | 1/20 | 12 |
| 35 | depends_on 是啟動順序，分級處理 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 0 | 0 | 0 | 8 | 0 | 0 | 13 | 1 | 13 | 5 | 4 | 26 | 26 | 12 | 12 | 69 | 16 | 22 | 6 | 11 | 0 | 14/20 | 244 |
| 36 | 只剝真正的 DNS 字尾 | run-aspnetcore-microservices、yas | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 2 | 0 | 0 | 1 | 1 | 13 | 6/20 | 20 |
| 37 | Compose 的 hostname: 是該服務的全域別名（規則 25 的… | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 11 | 0 | 2 | 0 | 2/20 | 13 |
| 38 | YARP 的 ReverseProxy.Clusters.*.Destin… | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 1/20 | 12 |
| 39 | 值的形狀決定節點種類（規則 26 的延伸） | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 0 | 1/20 | 7 |
| 40 | C# 以薄表面讀（規則 28 的延伸） | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 146 | 0 | 3/20 | 166 |
| 41 | JVM 函式庫模組不是服務（規則 21 的 Java 版） | yas | 0 | 0 | 0 | 0 | 14 | 0 | 0 | 27 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 4 | 0 | 3 | 0 | 0 | 5/20 | 54 |
| 42 | 測試樹的每一段都算（規則 2、31 的延伸） | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 0 | 0 | 119 | 0 | 0 | 2/20 | 123 |
| 43 | OpenAPI UI 的設定鍵不是呼叫（規則 29 的設定檔版） | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 18 | 0 | 6 | 0 | 0 | 3/20 | 25 |
| 44 | compose 的 build 說了哪個模組是哪個服務 | yas、run-aspnetcore-microservices、ewolff/microservice | 0 | 0 | 0 | 1 | 12 | 0 | 11 | 0 | 2 | 6 | 0 | 0 | 0 | 2 | 1 | 0 | 0 | 0 | 13 | 5 | 0 | 8/20 | 53 |
| 45 | *.url／*.uri／*.endpoint 鍵下的裸單字只在它是部署宣告… | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 1 | 0 | 0 | 2/20 | 3 |
| 46 | nginx 的 set $var "x"; proxy_pass http… | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 1/20 | 5 |

欄的分組：歸納＝Bank of Anthos、ewolff k8s、ecommerce、piggymetrics、TeaStore、Online Boutique、robot-shop (2024)；回歸＝train-ticket；MicroDepGraph＝spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-netflix、spring-cloud-microservice、LakesideMutual、robot-shop (2019)；hold-out B＝opentelemetry-demo、mall-swarm、hotelReservation；hold-out C＝yas、run-aspnetcore-microservices；私有＝AICS (private)。

## 摘要

- 在 **≥5 個系統**上觸發：20 條（1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、22、35、36、41、44）
- 在 **2–4 個系統**上觸發：15 條（16、18、19、24、27、29、30、31、32、33、37、40、42、43、45）
- 只在 **1 個系統**上觸發：10 條（13、17、21、23、25、28、34、38、39、46）
- 觸發的系統**全部是歸納它的系統**（沒有轉移到別處）：10 條（13、17、21、23、25、28、34、38、39、46）
- **從未觸發**：1 條（26）

## 每條規則：觸發的系統與其中非歸納系統

| # | 歸納自 | 觸發系統 | 其中非歸納系統 | 例子（系統: token） |
|---|---|---|---|---|
| 1 | ewolff、TeaStore、Online Boutique | 9：bank-of-anthos、ewolff-k8s、teastore、online-boutique、robot-shop、train-ticket、lakeside-mutual、yas、aics | 6：bank-of-anthos、robot-shop、train-ticket、lakeside-mutual、yas、aics | bank-of-anthos: not-preadded ledgermonolith；bank-of-anthos: node ledgerwriter；bank-of-anthos: node accounts-db |
| 2 | TeaStore、Online Boutique、ewolff | 11：ewolff-k8s、teastore、online-boutique、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、opentelemetry-demo、run-aspnetcore-microservices、aics | 8：train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、opentelemetry-demo、run-aspnetcore-microservices、aics | ewolff-k8s: aggregator microservice-kubernetes-demo；teastore: test-dir e2e-tests；teastore: name tools.descartes.teastore.dockermemoryconfigurator=tools-descartes-teastore-dockermemoryconfigurator |
| 3 | piggymetrics | 5：ecommerce、piggymetrics、tap-and-eat、spring-cloud-microservice、mall-swarm | 4：ecommerce、tap-and-eat、spring-cloud-microservice、mall-swarm | ecommerce: config-client product-service；ecommerce: config-client api-gateway；ecommerce: config-client shipping-service |
| 4 | piggymetrics、ewolff、robot-shop | 12：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、robot-shop、train-ticket、microservices-book、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas | 9：bank-of-anthos、ecommerce、train-ticket、microservices-book、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas | bank-of-anthos: table BALANCES_API_ADDR=balancereader；ewolff-k8s: default catalog.service.host=catalog；ewolff-k8s: default customer.service.host=customer |
| 5 | piggymetrics、TeaStore、robot-shop | 10：piggymetrics、teastore、robot-shop、train-ticket、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、aics | 7：train-ticket、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、aics | piggymetrics: key zuul.routes.account-service.serviceid=account-service；piggymetrics: key zuul.routes.statistics-service.serviceid=statistics-service；piggymetrics: key zuul.routes.notification-service.serviceid=notification-service |
| 6 | ewolff、robot-shop | 5：ewolff-k8s、robot-shop、train-ticket、lakeside-mutual、yas | 3：train-ticket、lakeside-mutual、yas | ewolff-k8s: url http://order:8080/；ewolff-k8s: url http://catalog:8080/；ewolff-k8s: url http://customer:8080/ |
| 7 | TeaStore、robot-shop、ecommerce | 9：teastore、robot-shop、tap-and-eat、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | 7：tap-and-eat、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | teastore: env persistence.REGISTRY_HOST=teastore-registry；teastore: env persistence.DB_HOST=teastore-db；teastore: env auth.REGISTRY_HOST=teastore-registry |
| 8 | ecommerce、piggymetrics、TeaStore | 14：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、train-ticket、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、run-aspnetcore-microservices、aics | 12：bank-of-anthos、ewolff-k8s、train-ticket、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、run-aspnetcore-microservices、aics | bank-of-anthos: loopback metadata.google.internal；bank-of-anthos: config-signal server.port；bank-of-anthos: config-signal spring.datasource.platform |
| 9 | ecommerce、TeaStore | 14：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、mall-swarm、yas | 12：bank-of-anthos、ewolff-k8s、piggymetrics、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、mall-swarm、yas | bank-of-anthos: jpa src/ledger/balancereader/src/main/java/anthos/samples/bankofanthos/balancereader/TransactionRepository.java；bank-of-anthos: jpa src/ledger/balancereader/src/main/java/anthos/samples/bankofanthos/balancereader/Transaction.java；bank-of-anthos: jpa src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/TransactionRepository.java |
| 10 | train-ticket、Bank of Anthos | 9：bank-of-anthos、ecommerce、online-boutique、robot-shop、train-ticket、tap-and-eat、mall-swarm、hotel-reservation、aics | 7：ecommerce、online-boutique、robot-shop、tap-and-eat、mall-swarm、hotel-reservation、aics | bank-of-anthos: drop populate-ledger-db (src/ledger/ledger-db/k8s/components/init-db-job)；bank-of-anthos: drop populate-accounts-db (src/accounts/accounts-db/k8s/components/init-db-job)；bank-of-anthos: drop populate-ledger-db (extras/cloudsql/populate-jobs) |
| 11 | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 14：piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、microservices-book、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | 11：piggymetrics、teastore、robot-shop、train-ticket、spring-cloud-netflix、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | piggymetrics: node rabbitmq；piggymetrics: node config；piggymetrics: node registry |
| 12 | Tap-And-Eat、spring-cloud-microservice | 6：microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、mall-swarm | 4：microservices-book、spring-cloud-netflix、lakeside-mutual、mall-swarm | microservices-book: links customer->eureka；microservices-book: links catalog->eureka；microservices-book: links order->eureka |
| 13 | LakesideMutual | 1：lakeside-mutual | 0：— | lakeside-mutual: drop http://lm.com/ccore；lakeside-mutual: drop http://lm.com/ccore/GetCustomerRequest；lakeside-mutual: drop http://lakesdemutual.com/interfaces/xsd |
| 14 | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 9：bank-of-anthos、teastore、online-boutique、robot-shop、lakeside-mutual、opentelemetry-demo、yas、run-aspnetcore-microservices、aics | 5：robot-shop、opentelemetry-demo、yas、run-aspnetcore-microservices、aics | bank-of-anthos: skip-configmap ledgerwriter->balancereader；bank-of-anthos: skip-configmap ledgerwriter->transactionhistory；bank-of-anthos: skip-configmap ledgerwriter->contacts |
| 15 | ecommerce | 5：ecommerce、tap-and-eat、spring-cloud-microservice、lakeside-mutual、yas | 4：tap-and-eat、spring-cloud-microservice、lakeside-mutual、yas | ecommerce: configserver-prefix optional:configserver:http://localhost:9296；ecommerce: edge product-service->cloud-config :9296；ecommerce: edge api-gateway->cloud-config :9296 |
| 16 | ecommerce | 3：ecommerce、spring-petclinic、spring-cloud-netflix | 2：spring-petclinic、spring-cloud-netflix | ecommerce: edge product-service->service-discovery；ecommerce: edge cloud-config->service-discovery；ecommerce: edge api-gateway->service-discovery |
| 17 | TeaStore | 1：teastore | 0：— | teastore: edge teastore-webui->teastore-persistence；teastore: edge teastore-webui->teastore-recommender；teastore: edge teastore-webui->teastore-auth |
| 18 | robot-shop | 2：teastore、robot-shop | 1：teastore | teastore: dockerfile-env RABBITMQ_HOST=unset；robot-shop: dockerfile-env CATALOGUE_HOST=catalogue；robot-shop: dockerfile-env USER_HOST=user |
| 19 | AICS（私有系統） | 2：train-ticket、aics | 1：train-ticket | train-ticket: version ts-voucher-service-v2=ts-voucher-service；aics: version user-service-v1=user-service；aics: version twitter-crawler-service-v1=twitter-crawler-service |
| 20 | AICS | 6：bank-of-anthos、online-boutique、robot-shop、opentelemetry-demo、run-aspnetcore-microservices、aics | 5：bank-of-anthos、online-boutique、robot-shop、opentelemetry-demo、run-aspnetcore-microservices | bank-of-anthos: scoped BALANCES_API_ADDR=balancereader @ledgerwriter；bank-of-anthos: scoped TRANSACTIONS_API_ADDR=ledgerwriter @frontend；bank-of-anthos: scoped USERSERVICE_API_ADDR=userservice @frontend |
| 21 | AICS | 1：aics | 0：— | aics: lib aics-toolkit-package；aics: lib aics-text-handler-package；aics: attribute AICS-toolkit-package/aics_toolkit/redis_handler.py=twitter-crawler-service |
| 22 | AICS | 5：bank-of-anthos、robot-shop、train-ticket、opentelemetry-demo、aics | 4：bank-of-anthos、robot-shop、train-ticket、opentelemetry-demo | bank-of-anthos: urlref src/frontend/frontend.py:691 metadata_url；bank-of-anthos: urlref src/frontend/frontend.py:707 metadata_url；bank-of-anthos: dead src/frontend/frontend.py:420 |
| 23 | piggymetrics | 1：piggymetrics | 0：— | piggymetrics: keep statistics-service eureka.client.；piggymetrics: keep account-service eureka.client.；piggymetrics: keep notification-service eureka.client. |
| 24 | opentelemetry-demo | 2：opentelemetry-demo、yas | 1：yas | opentelemetry-demo: forward otel-ebpf-profiler.OTEL_COLLECTOR_HOST=otel-collector；opentelemetry-demo: forward ad.FLAGD_HOST=flagd；opentelemetry-demo: interp ad.OTEL_EXPORTER_OTLP_ENDPOINT=otel-collector |
| 25 | mall-swarm | 1：mall-swarm | 0：— | mall-swarm: alias mall-admin:db=mysql；mall-swarm: alias mall-search:es=elasticsearch；mall-swarm: alias mall-search:db=mysql |
| 26 | mall-swarm | 0：— | 0：— |  |
| 27 | mall-swarm | 3：train-ticket、mall-swarm、yas | 2：train-ticket、yas | train-ticket: first-of-list spring.cloud.nacos.discovery.server-addr；train-ticket: list spring.cloud.nacos.discovery.server-addr=${NACOS_ADDRS:nacos-0.nacos-headless.default.svc.cluster.local；train-ticket: list spring.cloud.nacos.discovery.server-addr=${NACOS_ADDRS:127.0.0.1:8848} |
| 28 | opentelemetry-demo | 1：opentelemetry-demo | 0：— | opentelemetry-demo: kt-file src/fraud-detection/build.gradle.kts；opentelemetry-demo: kt-file src/fraud-detection/settings.gradle.kts；opentelemetry-demo: kt-file src/fraud-detection/src/main/kotlin/frauddetection/main.kt |
| 29 | mall-swarm、train-ticket | 3：train-ticket、spring-cloud-netflix、mall-swarm | 1：spring-cloud-netflix | train-ticket: drop https://github.com/FudanSELab/train-ticket；spring-cloud-netflix: drop https://github.com/yidongnan；mall-swarm: drop https://github.com/macrozheng/mall-learning |
| 30 | mall-swarm | 2：mall-swarm、yas | 1：yas | mall-swarm: edge mall-portal->rabbitmq；yas: edge webhook->kafka；yas: edge recommendation->kafka |
| 31 | opentelemetry-demo、hotelReservation | 3：bank-of-anthos、opentelemetry-demo、hotel-reservation | 1：bank-of-anthos | bank-of-anthos: hidden .github/workflows/ui-tests/Dockerfile；opentelemetry-demo: skip-compose compose.tests.yaml；hotel-reservation: hidden .bingo/go.mod |
| 32 | opentelemetry-demo | 2：opentelemetry-demo、run-aspnetcore-microservices | 1：run-aspnetcore-microservices | opentelemetry-demo: connstr DB_CONNECTION_STRING；run-aspnetcore-microservices: connstr ConnectionStrings__Database；run-aspnetcore-microservices: connstr connectionstrings:database |
| 33 | opentelemetry-demo | 2：opentelemetry-demo、yas | 1：yas | opentelemetry-demo: drop vcr.local；yas: drop api.yas.local |
| 34 | opentelemetry-demo | 1：opentelemetry-demo | 0：— | opentelemetry-demo: url http://${OTEL_COLLECTOR_HOST}；opentelemetry-demo: url http://${FRONTEND_HOST}；opentelemetry-demo: url http://${IMAGE_PROVIDER_HOST} |
| 35 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 14：piggymetrics、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | 6：piggymetrics、train-ticket、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices | piggymetrics: inferred registry->config；piggymetrics: inferred gateway->config；piggymetrics: inferred auth-service->config |
| 36 | run-aspnetcore-microservices、yas | 6：bank-of-anthos、robot-shop、opentelemetry-demo、yas、run-aspnetcore-microservices、aics | 4：bank-of-anthos、robot-shop、opentelemetry-demo、aics | bank-of-anthos: keep-dotted www.apache.org；bank-of-anthos: keep-dotted kopf.on.startup() async def startup(**_)；robot-shop@2019: ns-strip github.com |
| 37 | run-aspnetcore-microservices | 2：hotel-reservation、run-aspnetcore-microservices | 1：hotel-reservation | hotel-reservation: hostname user-memcached=memcached-rate；hotel-reservation: hostname user-memcached=memcached-profile；hotel-reservation: hostname user-memcached=memcached-reserve |
| 38 | run-aspnetcore-microservices | 1：run-aspnetcore-microservices | 0：— | run-aspnetcore-microservices: key ApiSettings:GatewayAddress；run-aspnetcore-microservices: yarp http://catalog.api:8080；run-aspnetcore-microservices: yarp http://ordering.api:8080 |
| 39 | run-aspnetcore-microservices | 1：run-aspnetcore-microservices | 0：— | run-aspnetcore-microservices: kind basketdb=db；run-aspnetcore-microservices: kind distributedcache=db；run-aspnetcore-microservices: kind catalogdb=db |
| 40 | run-aspnetcore-microservices | 3：online-boutique、opentelemetry-demo、run-aspnetcore-microservices | 2：online-boutique、opentelemetry-demo | online-boutique: cs-file src/cartservice/src/cartstore/ICartStore.cs；online-boutique: cs-file src/cartservice/src/cartstore/SpannerCartStore.cs；online-boutique: cs-file src/cartservice/src/cartstore/AlloyDBCartStore.cs |
| 41 | yas | 5：teastore、train-ticket、spring-cloud-microservice、mall-swarm、yas | 4：teastore、train-ticket、spring-cloud-microservice、mall-swarm | teastore: lib tools-descartes-teastore-kieker-probes；teastore: lib tools-descartes-teastore-registryclient；teastore: lib tools-descartes-teastore-entities |
| 42 | yas | 2：opentelemetry-demo、yas | 1：opentelemetry-demo | opentelemetry-demo: ignore /src/frontend/cypress/e2e/Checkout.cy.ts/；opentelemetry-demo: ignore /src/frontend/cypress/e2e/ProductDetail.cy.ts/；opentelemetry-demo: ignore /src/frontend/cypress/e2e/Home.cy.ts/ |
| 43 | yas | 3：train-ticket、mall-swarm、yas | 2：train-ticket、mall-swarm | train-ticket: drop swagger.controllerpackage；mall-swarm: drop springdoc.swagger-ui.path；mall-swarm: drop springdoc.swagger-ui.enabled |
| 44 | yas、run-aspnetcore-microservices、ewolff/microservice | 8：piggymetrics、teastore、robot-shop、spring-petclinic、microservices-book、lakeside-mutual、yas、run-aspnetcore-microservices | 5：piggymetrics、teastore、robot-shop、spring-petclinic、lakeside-mutual | piggymetrics: map mongodb=auth-mongodb；teastore: artifact tools-descartes-teastore-webui=webui；teastore: map tools-descartes-teastore-webui=teastore-webui |
| 45 | yas | 2：opentelemetry-demo、yas | 1：opentelemetry-demo | opentelemetry-demo: accept mcp_endpoint=mcp；opentelemetry-demo: accept agent_endpoint=agent；yas: accept elasticsearch.url=elasticsearch |
| 46 | yas | 1：yas | 0：— | yas: set-var http://search；yas: set-var http://webhook；yas: set-var http://recommendation |

## 怎麼讀（2026-10-27 這一跑）

**慣例（20 條，≥5 個系統）**：1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、22、35、36、41、44。這些都離開了歸納它的系統：
manifest 與 Compose 的名稱清單（1、11）、模組目錄正規化（2）、佔位符解析（4）、裸主機名（5）、反向代理設定（6）、Compose 佈線（7）、
「不是目標的不畫」（8）、「宣告≠使用」（9）、只認部署了自己模組的 manifest 目錄（10）、Compose v1 與 links（12）、
workload 自己 env 裡的服務位址（14）、depends_on 分級（35）各在 9–14 個系統上改變結果。值得點名的是在一個系統上歸納、卻轉移得很遠的幾條：
第 20 條（環境變數在讀取者自己的範圍解析）在私有系統 AICS 上歸納，Bank of Anthos、Online Boutique、robot-shop、opentelemetry-demo、run-aspnetcore-microservices 都用到；
第 22 條（Python 的呼叫點才算使用、死碼不畫）也來自 AICS，在 Bank of Anthos、robot-shop、train-ticket、opentelemetry-demo 上觸發；
第 15 條（loopback 以 port 讀回擁有者）在 ecommerce 歸納，Tap-And-Eat、spring-cloud-microservice、LakesideMutual、yas 都有同樣的寫法；
第 41 條（JVM 函式庫模組不是服務）在 yas 歸納，TeaStore、train-ticket、spring-cloud-microservice、mall-swarm 各有 1–3 個這樣的模組。

**部分轉移（15 條，2–4 個系統）**：16、18、19、24、27、29、30、31、32、33、37、40、42、43、45。每一條都至少在一個**非**歸納系統上觸發
（16 隱含 Eureka → spring-petclinic、spring-cloud-netflix；18 String.format＋Dockerfile ENV → TeaStore；19 版本字尾 → train-ticket；
24 Compose 鍵名轉發 → yas；27 位址清單 → train-ticket、yas；29 OpenAPI 中繼資料 → spring-cloud-netflix；30 訊息邊 → yas；31 測試 fixture → Bank of Anthos；
32 連線字串 → run-aspnetcore-microservices；33 保留 TLD → yas；37 Compose hostname → hotelReservation；40 C# 薄表面 → Online Boutique、opentelemetry-demo；
42 測試樹 → opentelemetry-demo；43 OpenAPI UI 設定鍵 → train-ticket、mall-swarm；45 位址鍵下的裸單字 → opentelemetry-demo）。這些是某種技術或寫法的慣例，語料裡用到那種技術的系統本來就只有兩三個。

**只在歸納它的系統上觸發（10 條）**：13、17、21、23、25、28、34、38、39、46。其中 7 條綁在語料裡只出現一次的技術——Kotlin（28）、Envoy（34）、YARP 與 appsettings（38）、
.NET 連線字串的形狀（39）、nginx 的 set $var（46）、SOAP／JAXB 的 XML namespace（13）、沒有 Dockerfile 的 Python 套件庫（21）；這份記錄分不出「技術只出現一次」與「專案特例」，
要再有一個用同樣技術的系統才知道。另外 3 條是真正的單一系統寫法：TeaStore 以 enum 常數經自家 registry 派送（17）、piggymetrics 的共用設定檔要靠建置檔判斷誰綁得到（23）、
mall-swarm 的 `links: mysql:db` 別名（25；Tap-And-Eat 等系統的 links 沒有別名）。它們沒有錯，但確實是為一個系統寫的。

**從未觸發（1 條）**：第 26 條（連線 scheme 決定節點種類）。在全部 20 個系統上，jdbc:／mongodb://／amqp:// 指到的主機名稱本身就帶著資料庫或 broker 的判定
（mysql、*-mongodb、*-db、rabbitmq…），scheme 說的與名稱說的一致，所以沒有一次改變結果；在歸納它的 mall-swarm 上，`jdbc:mysql://db` 的 `db` 先被第 25 條解成 `mysql`，
第 26 條就用不到了。它只在「主機名沒有任何提示、又沒有別名」時才有作用（單元測試的 fixture 就是這種），在真實語料裡是一條從未用到的保險。

**計數位置的取捨**（規則編號有歧義處，依附錄的措辭對應）：第 9 條把 JPA 標記列與 JAX-RS `@Path` 列也算進去（附錄把這兩句寫在同一條），所以它的命中數偏大；
第 1 與第 11 條以名稱清單的來源分（manifest → 1，Compose → 11）；第 5、27、45 條以設定鍵的字尾分；第 26 與第 39 條以「scheme 寫在目標裡」與「值的形狀（kindHint、ConnectionStrings、事後改型）」分；
第 21 與第 41 條以 `library_kind` 分；第 2、31、42 條的測試排除以各自新增的名單分；第 14 條只數服務類的 inferred 邊與共用 ConfigMap 的略過，
資料庫／broker 的 workload 佈線邊（2026-09-21 Bank of Anthos，早於編號）沒有規則號、不計；第 44 條只數改了名字的對應與被拒的 Compose 名；第 33 條在 ExternalHostDetector 的那一處 probe 不會經過。

## 附錄用精簡版（規則、歸納自、觸發系統數、合計命中）

| # | 規則（首句） | 歸納自 | 觸發系統數 | 合計 |
|---|---|---|---|---|
| 1 | manifest 的 workload 名稱（Deployment、Sta… | ewolff、TeaStore、Online Boutique | 9/20 | 206 |
| 2 | 模組目錄名正規化 | TeaStore、Online Boutique、ewolff | 11/20 | 75 |
| 3 | Spring Cloud Config 倉庫版面 | piggymetrics | 5/20 | 84 |
| 4 | 佔位符依序查環境變數、屬性表、${key:default} 的預設值，最後… | piggymetrics、ewolff、robot-shop | 12/20 | 167 |
| 5 | 裸主機名也是目標 | piggymetrics、TeaStore、robot-shop | 10/20 | 102 |
| 6 | 反向代理設定是入口的邊清單 | ewolff、robot-shop | 5/20 | 40 |
| 7 | Compose 描述的是服務之間的連線設定，不是某個服務的設定 | TeaStore、robot-shop、ecommerce | 9/20 | 92 |
| 8 | 不是目標的東西不畫 | ecommerce、piggymetrics、TeaStore | 14/20 | 147 |
| 9 | 宣告不等於使用 | ecommerce、TeaStore | 14/20 | 279 |
| 10 | 只認「部署了這個 repo 自己模組」的 manifest 目錄 | train-ticket、Bank of Anthos | 9/20 | 108 |
| 11 | Compose 的 services 也是部署名稱清單，與 Kuberne… | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 14/20 | 132 |
| 12 | 也認 Compose 第 1 版格式（沒有 services:，頂層即服務… | Tap-And-Eat、spring-cloud-microservice | 6/20 | 82 |
| 13 | XML namespace 不是位址 | LakesideMutual | 1/20 | 3 |
| 14 | workload 自己環境變數裡的服務位址是宣告，畫成僅推測；共用 Con… | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 9/20 | 220 |
| 15 | loopback 位址以 port 讀回擁有者 | ecommerce | 5/20 | 48 |
| 16 | Spring Cloud 的隱含註冊中心 | ecommerce | 3/20 | 20 |
| 17 | 字串值等於模組名的常數就是被呼叫端 | TeaStore | 1/20 | 7 |
| 18 | String.format("http://%s/…", getenv("… | robot-shop | 2/20 | 20 |
| 19 | 帶版本字尾的 Deployment 是某個 Service 的一個版本 | AICS（私有系統） | 2/20 | 10 |
| 20 | 環境變數在讀取者自己的範圍解析 | AICS | 6/20 | 77 |
| 21 | 函式庫不是服務 | AICS | 1/20 | 7 |
| 22 | 呼叫點才算使用（Python） | AICS | 5/20 | 66 |
| 23 | 共用設定的 key 只在有對應函式庫的 client 生效 | piggymetrics | 1/20 | 24 |
| 24 | Compose 的鍵名轉發與 .env 內插 | opentelemetry-demo | 2/20 | 153 |
| 25 | links 與 external_links 的別名是該服務對目標的稱呼 | mall-swarm | 1/20 | 14 |
| 26 | 連線 scheme 決定節點種類 | mall-swarm | 0/20 | 0 |
| 27 | 位址清單 key 與裸 FQDN 的 endpoint | mall-swarm | 3/20 | 14 |
| 28 | Kotlin 以薄表面讀 | opentelemetry-demo | 1/20 | 3 |
| 29 | OpenAPI／Swagger 中繼資料的 URL 不是呼叫 | mall-swarm、train-ticket | 3/20 | 4 |
| 30 | 訊息程式碼的邊是服務 → broker | mall-swarm | 2/20 | 4 |
| 31 | 測試 fixture 不是服務（規則 2 的延伸） | opentelemetry-demo、hotelReservation | 3/20 | 3 |
| 32 | key=value; 連線字串的主機 | opentelemetry-demo | 2/20 | 3 |
| 33 | 保留 TLD 不是相依 | opentelemetry-demo | 2/20 | 2 |
| 34 | Envoy 靜態設定是反向代理的上游表（規則 6 的延伸） | opentelemetry-demo | 1/20 | 12 |
| 35 | depends_on 是啟動順序，分級處理 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 14/20 | 244 |
| 36 | 只剝真正的 DNS 字尾 | run-aspnetcore-microservices、yas | 6/20 | 20 |
| 37 | Compose 的 hostname: 是該服務的全域別名（規則 25 的… | run-aspnetcore-microservices | 2/20 | 13 |
| 38 | YARP 的 ReverseProxy.Clusters.*.Destin… | run-aspnetcore-microservices | 1/20 | 12 |
| 39 | 值的形狀決定節點種類（規則 26 的延伸） | run-aspnetcore-microservices | 1/20 | 7 |
| 40 | C# 以薄表面讀（規則 28 的延伸） | run-aspnetcore-microservices | 3/20 | 166 |
| 41 | JVM 函式庫模組不是服務（規則 21 的 Java 版） | yas | 5/20 | 54 |
| 42 | 測試樹的每一段都算（規則 2、31 的延伸） | yas | 2/20 | 123 |
| 43 | OpenAPI UI 的設定鍵不是呼叫（規則 29 的設定檔版） | yas | 3/20 | 25 |
| 44 | compose 的 build 說了哪個模組是哪個服務 | yas、run-aspnetcore-microservices、ewolff/microservice | 8/20 | 53 |
| 45 | *.url／*.uri／*.endpoint 鍵下的裸單字只在它是部署宣告… | yas | 2/20 | 3 |
| 46 | nginx 的 set $var "x"; proxy_pass http… | yas | 1/20 | 5 |

