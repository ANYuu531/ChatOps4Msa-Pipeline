# 56 條規則在 22 個系統上各觸發了幾次（2026-10-27，第五輪後）

回答「這不就是把例子列舉一遍嗎？」：每條規則都是在 1–3 個系統上歸納出來的；若它在很多沒參與歸納的系統上也會改變結果，它抓到的是業界的慣例，不是那個系統的特例。表的每一格是 `RuleTrace` 在該系統的靜態層分析中記到的**不同事物數**（邊、節點、名稱、檔案；同一個東西重複碰到算一次），由 `GreenfieldProbeTest` 以 tag `trace` 跑在與 r3／現行輸出同一份 checkout 上得到（邊逐條相同，見下方），原始檔是各目錄下的 `<name>.trace.rules.json`，表由 `docs/generalization/rule_triggers.py` 產生。

計數的位置是規則**改變結果**的地方：畫出／不畫／改等級一條邊、建立／合併／壓掉一個節點、解開一個名稱；純解析的規則（第 12 條 Compose v1、第 28／40 條 Kotlin／C# 檔案、第 52 條渲染的 template）數它解析的檔案或服務。每條規則對應的程式位置與 token 形狀列在文末。

robot-shop 有兩個 checkout（歸納用的 2024 版與 MicroDepGraph 對齊的 2019 版），兩欄分開列、「觸發系統數」只算一次，所以欄數是 23、系統數是 22。

## 全表

| # | 規則（首句） | 歸納自 | Bank of Anthos | ewolff k8s | ecommerce | piggymetrics | TeaStore | Online Boutique | robot-shop (2024) | train-ticket | spring-petclinic | microservices-book | Tap-And-Eat | spring-cloud-netflix | spring-cloud-microservice | LakesideMutual | robot-shop (2019) | opentelemetry-demo | mall-swarm | hotelReservation | yas | run-aspnetcore-microservices | travels | skywalking-showcase | AICS (private) | 觸發系統數 | 合計 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | manifest 的 workload 名稱（Deployment、Sta… | ewolff、TeaStore、Online Boutique | 11 | 7 | 0 | 0 | 14 | 13 | 12 | 78 | 0 | 0 | 0 | 0 | 0 | 12 | 13 | 0 | 0 | 0 | 23 | 0 | 16 | 0 | 22 | 10/22 | 221 |
| 2 | 模組目錄名正規化 | TeaStore、Online Boutique、ewolff | 0 | 1 | 0 | 0 | 15 | 1 | 0 | 2 | 1 | 1 | 16 | 0 | 10 | 0 | 0 | 1 | 0 | 0 | 0 | 6 | 3 | 2 | 21 | 13/22 | 80 |
| 3 | Spring Cloud Config 倉庫版面 | piggymetrics | 0 | 0 | 8 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 10 | 0 | 9 | 0 | 0 | 0 | 20 | 0 | 0 | 0 | 0 | 0 | 0 | 5/22 | 89 |
| 4 | 佔位符依序查環境變數、屬性表、${key:default} 的預設值，最後… | piggymetrics、ewolff、robot-shop | 1 | 2 | 1 | 1 | 0 | 0 | 6 | 126 | 0 | 2 | 0 | 0 | 2 | 1 | 6 | 13 | 2 | 0 | 4 | 0 | 0 | 1 | 0 | 13/22 | 168 |
| 5 | 裸主機名也是目標 | piggymetrics、TeaStore、robot-shop | 0 | 0 | 0 | 9 | 9 | 0 | 7 | 4 | 0 | 0 | 0 | 0 | 6 | 4 | 7 | 34 | 10 | 0 | 12 | 0 | 1 | 18 | 6 | 12/22 | 127 |
| 6 | 反向代理設定是入口的邊清單 | ewolff、robot-shop | 0 | 3 | 0 | 0 | 0 | 0 | 6 | 1 | 0 | 0 | 0 | 0 | 0 | 1 | 6 | 0 | 0 | 0 | 23 | 0 | 0 | 0 | 0 | 5/22 | 40 |
| 7 | Compose 描述的是服務之間的連線設定，不是某個服務的設定 | TeaStore、robot-shop、ecommerce | 0 | 0 | 0 | 0 | 25 | 0 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 20 | 0 | 1 | 1 | 13 | 18 | 8 | 0 | 99 | 0 | 9/22 | 189 |
| 8 | 不是目標的東西不畫 | ecommerce、piggymetrics、TeaStore | 4 | 1 | 28 | 10 | 1 | 0 | 0 | 8 | 0 | 9 | 1 | 0 | 7 | 12 | 0 | 16 | 27 | 0 | 19 | 9 | 0 | 6 | 2 | 16/22 | 160 |
| 9 | 宣告不等於使用 | ecommerce、TeaStore | 8 | 4 | 29 | 4 | 58 | 0 | 2 | 48 | 6 | 4 | 5 | 0 | 1 | 30 | 0 | 0 | 4 | 0 | 77 | 0 | 0 | 2 | 0 | 15/22 | 282 |
| 10 | 只認「部署了這個 repo 自己模組」的 manifest 目錄 | train-ticket、Bank of Anthos | 5 | 0 | 21 | 0 | 0 | 1 | 1 | 13 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 6 | 51 | 0 | 0 | 0 | 0 | 4 | 8/22 | 103 |
| 11 | Compose 的 services 也是部署名稱清單，與 Kuberne… | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 0 | 0 | 0 | 14 | 1 | 0 | 1 | 1 | 11 | 10 | 8 | 9 | 17 | 1 | 1 | 35 | 1 | 1 | 1 | 11 | 0 | 81 | 0 | 16/22 | 204 |
| 12 | 也認 Compose 第 1 版格式（沒有 services:，頂層即服務… | Tap-And-Eat、spring-cloud-microservice | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 5 | 28 | 27 | 1 | 0 | 0 | 16 | 0 | 0 | 0 | 0 | 0 | 0 | 6/22 | 82 |
| 13 | XML namespace 不是位址 | LakesideMutual | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 3 |
| 14 | workload 自己環境變數裡的服務位址是宣告，畫成僅推測；共用 Con… | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 10 | 0 | 0 | 0 | 5 | 16 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 20 | 0 | 76 | 0 | 0 | 50 | 2 | 15 | 75 | 42 | 11/22 | 312 |
| 15 | loopback 位址以 port 讀回擁有者 | ecommerce | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 0 | 17 | 13 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 5/22 | 48 |
| 16 | Spring Cloud 的隱含註冊中心 | ecommerce | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3/22 | 20 |
| 17 | 字串值等於模組名的常數就是被呼叫端 | TeaStore | 0 | 0 | 0 | 0 | 7 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 7 |
| 18 | String.format("http://%s/…", getenv("… | robot-shop | 0 | 0 | 0 | 0 | 1 | 0 | 10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 3/22 | 21 |
| 19 | 帶版本字尾的 Deployment 是某個 Service 的一個版本 | AICS（私有系統） | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 10 | 0 | 9 | 3/22 | 20 |
| 20 | 環境變數在讀取者自己的範圍解析 | AICS | 6 | 0 | 0 | 0 | 0 | 16 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 8 | 31 | 0 | 0 | 1 | 8 | 12 | 1 | 22 | 10/22 | 114 |
| 21 | 函式庫不是服務 | AICS | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 1/22 | 7 |
| 22 | 呼叫點才算使用（Python） | AICS | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 11 | 4/22 | 21 |
| 23 | 共用設定的 key 只在有對應函式庫的 client 生效 | piggymetrics | 0 | 0 | 0 | 24 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 24 |
| 24 | Compose 的鍵名轉發與 .env 內插 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 94 | 0 | 0 | 59 | 0 | 0 | 0 | 0 | 2/22 | 153 |
| 25 | links 與 external_links 的別名是該服務對目標的稱呼 | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 14 | 0 | 0 | 0 | 0 | 80 | 0 | 2/22 | 94 |
| 26 | 連線 scheme 決定節點種類 | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0/22 | 0 |
| 27 | 位址清單 key 與裸 FQDN 的 endpoint | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 8 | 0 | 3 | 0 | 0 | 0 | 0 | 3/22 | 14 |
| 28 | Kotlin | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 3 |
| 29 | OpenAPI／Swagger 中繼資料的 URL 不是呼叫 | mall-swarm、train-ticket | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3/22 | 4 |
| 30 | 訊息程式碼的邊是服務 → broker | mall-swarm | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 3 | 0 | 0 | 0 | 0 | 2/22 | 4 |
| 31 | 測試 fixture 不是服務（規則 2 的延伸） | opentelemetry-demo、hotelReservation | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 2 | 0 | 4/22 | 5 |
| 32 | key=value; 連線字串的主機 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 2 | 0 | 1 | 0 | 3/22 | 4 |
| 33 | 保留 TLD 不是相依 | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 2/22 | 2 |
| 34 | Envoy 靜態設定是反向代理的上游表（規則 6 的延伸） | opentelemetry-demo | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 12 |
| 35 | depends_on 是啟動順序，分級處理 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 0 | 0 | 0 | 8 | 0 | 0 | 13 | 1 | 13 | 5 | 4 | 26 | 26 | 12 | 12 | 69 | 16 | 22 | 6 | 11 | 0 | 99 | 0 | 15/22 | 343 |
| 36 | 只剝真正的 DNS 字尾 | run-aspnetcore-microservices、yas | 2 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 2 | 0 | 0 | 10 | 4 | 10 | 8 | 14 | 9/22 | 54 |
| 37 | Compose 的 hostname: 是該服務的全域別名（規則 25 的… | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 11 | 0 | 2 | 0 | 0 | 0 | 2/22 | 13 |
| 38 | YARP 的 ReverseProxy.Clusters.*.Destin… | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 1/22 | 12 |
| 39 | 值的形狀決定節點種類（規則 26 的延伸） | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 0 | 1 | 0 | 2/22 | 8 |
| 40 | C# | run-aspnetcore-microservices | 0 | 0 | 0 | 0 | 0 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 | 146 | 0 | 0 | 0 | 3/22 | 166 |
| 41 | JVM 函式庫模組不是服務（規則 21 的 Java 版） | yas | 0 | 0 | 0 | 0 | 14 | 0 | 0 | 27 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 4 | 0 | 3 | 0 | 0 | 0 | 0 | 5/22 | 54 |
| 42 | 測試樹的每一段都算（規則 2、31 的延伸） | ts）也是測試 | 0 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 0 | 0 | 119 | 0 | 0 | 0 | 1 | 4/22 | 132 |
| 43 | OpenAPI UI 的設定鍵不是呼叫（規則 29 的設定檔版） | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 18 | 0 | 6 | 0 | 0 | 0 | 0 | 3/22 | 25 |
| 44 | compose 的 build 說了哪個模組是哪個服務 | yas、run-aspnetcore-microservices、ewolff/microservice | 0 | 0 | 0 | 1 | 12 | 0 | 15 | 1 | 18 | 6 | 10 | 7 | 16 | 3 | 1 | 8 | 12 | 0 | 17 | 5 | 1 | 12 | 0 | 16/22 | 145 |
| 45 | *.url／*.uri／*.endpoint 鍵下的裸單字只在它是部署宣告… | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 2/22 | 3 |
| 46 | nginx 的 set $var "x"; proxy_pass http… | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 1/22 | 5 |
| 47 | 呼叫點的變數沿同檔綁定解析，所有文法共用（規則 22 的通用化） | robot-shop、skywalking-showcase、AICS | 2 | 0 | 0 | 0 | 0 | 3 | 5 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 6 | 9 | 0 | 0 | 151 | 0 | 0 | 4 | 37 | 8/22 | 218 |
| 48 | 環境變數讀取的預設值 | robot-shop | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1/22 | 5 |
| 49 | 註冊中心名稱列出、不猜 | hotelReservation | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 | 0 | 1/22 | 9 |
| 50 | 模組裡的 dotenv 檔（.env、.env.<mode>；旁邊沒有 c… | yas | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 1/22 | 2 |
| 51 | HTML 頁面以 <script src> 載入的腳本在瀏覽器執行 | AICS | 0 | 0 | 0 | 6 | 0 | 0 | 8 | 75 | 11 | 0 | 0 | 0 | 0 | 0 | 8 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 57 | 6/22 | 166 |
| 52 | Helm chart 先渲染再讀 | skywalking-showcase、yas、hotelReservation | 0 | 0 | 0 | 0 | 14 | 21 | 28 | 14 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 58 | 89 | 0 | 0 | 57 | 0 | 7/22 | 281 |
| 53 | 容器的命令列也是佈線 | skywalking-showcase | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 3 | 0 | 0 | 18 | 0 | 3/22 | 23 |
| 54 | namespace 決定身分 | travels | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 6 | 7 | 0 | 2/22 | 13 |
| 55 | 一個值列出多個位址 | travels | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 32 | 0 | 4 | 3 | 0 | 4/22 | 41 |
| 56 | 入口路由 | AICS、skywalking-showcase、Online Boutique | 2 | 0 | 0 | 0 | 0 | 3 | 2 | 77 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 12 | 6/22 | 101 |

欄的分組：歸納＝Bank of Anthos、ewolff k8s、ecommerce、piggymetrics、TeaStore、Online Boutique、robot-shop (2024)；回歸＝train-ticket；MicroDepGraph＝spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-netflix、spring-cloud-microservice、LakesideMutual、robot-shop (2019)；hold-out B＝opentelemetry-demo、mall-swarm、hotelReservation；hold-out C＝yas、run-aspnetcore-microservices；hold-out D＝travels、skywalking-showcase；私有＝AICS (private)。

## 摘要

- 在 **≥5 個系統**上觸發：23 條（1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、35、36、41、44、47、51、52、56）
- 在 **2–4 個系統**上觸發：21 條（16、18、19、22、24、25、27、29、30、31、32、33、37、39、40、42、43、45、53、54、55）
- 只在 **1 個系統**上觸發：11 條（13、17、21、23、28、34、38、46、48、49、50）
- 觸發的系統**全部是歸納它的系統**（沒有轉移到別處）：11 條（13、17、21、23、28、34、38、46、48、49、50）
- **從未觸發**：1 條（26）

## 每條規則：觸發的系統與其中非歸納系統

| # | 歸納自 | 觸發系統 | 其中非歸納系統 | 例子（系統: token） |
|---|---|---|---|---|
| 1 | ewolff、TeaStore、Online Boutique | 10：bank-of-anthos、ewolff-k8s、teastore、online-boutique、robot-shop、train-ticket、lakeside-mutual、yas、travels、aics | 7：bank-of-anthos、robot-shop、train-ticket、lakeside-mutual、yas、travels、aics | bank-of-anthos: not-preadded ledgermonolith；bank-of-anthos: node ledgerwriter；bank-of-anthos: node accounts-db |
| 2 | TeaStore、Online Boutique、ewolff | 13：ewolff-k8s、teastore、online-boutique、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、opentelemetry-demo、run-aspnetcore-microservices、travels、skywalking-showcase、aics | 10：train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、opentelemetry-demo、run-aspnetcore-microservices、travels、skywalking-showcase、aics | ewolff-k8s: aggregator microservice-kubernetes-demo；teastore: test-dir e2e-tests；teastore: name tools.descartes.teastore.dockermemoryconfigurator=tools-descartes-teastore-dockermemoryconfigurator |
| 3 | piggymetrics | 5：ecommerce、piggymetrics、tap-and-eat、spring-cloud-microservice、mall-swarm | 4：ecommerce、tap-and-eat、spring-cloud-microservice、mall-swarm | ecommerce: config-client product-service；ecommerce: config-client api-gateway；ecommerce: config-client shipping-service |
| 4 | piggymetrics、ewolff、robot-shop | 13：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、robot-shop、train-ticket、microservices-book、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、skywalking-showcase | 10：bank-of-anthos、ecommerce、train-ticket、microservices-book、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、skywalking-showcase | bank-of-anthos: table BALANCES_API_ADDR=balancereader；ewolff-k8s: default catalog.service.host=catalog；ewolff-k8s: default customer.service.host=customer |
| 5 | piggymetrics、TeaStore、robot-shop | 12：piggymetrics、teastore、robot-shop、train-ticket、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、travels、skywalking-showcase、aics | 9：train-ticket、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、travels、skywalking-showcase、aics | piggymetrics: key zuul.routes.account-service.serviceid=account-service；piggymetrics: key zuul.routes.statistics-service.serviceid=statistics-service；piggymetrics: key zuul.routes.notification-service.serviceid=notification-service |
| 6 | ewolff、robot-shop | 5：ewolff-k8s、robot-shop、train-ticket、lakeside-mutual、yas | 3：train-ticket、lakeside-mutual、yas | ewolff-k8s: url http://order:8080/；ewolff-k8s: url http://catalog:8080/；ewolff-k8s: url http://customer:8080/ |
| 7 | TeaStore、robot-shop、ecommerce | 9：teastore、robot-shop、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | 7：lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | teastore: env persistence.REGISTRY_HOST=teastore-registry；teastore: env persistence.DB_HOST=teastore-db；teastore: env auth.REGISTRY_HOST=teastore-registry |
| 8 | ecommerce、piggymetrics、TeaStore | 16：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、teastore、train-ticket、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、run-aspnetcore-microservices、skywalking-showcase、aics | 13：bank-of-anthos、ewolff-k8s、train-ticket、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、run-aspnetcore-microservices、skywalking-showcase、aics | bank-of-anthos: loopback metadata.google.internal；bank-of-anthos: config-signal url；bank-of-anthos: config-signal server.port |
| 9 | ecommerce、TeaStore | 15：bank-of-anthos、ewolff-k8s、ecommerce、piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、mall-swarm、yas、skywalking-showcase | 13：bank-of-anthos、ewolff-k8s、piggymetrics、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-microservice、lakeside-mutual、mall-swarm、yas、skywalking-showcase | bank-of-anthos: jpa src/ledger/balancereader/src/main/java/anthos/samples/bankofanthos/balancereader/TransactionRepository.java；bank-of-anthos: jpa src/ledger/balancereader/src/main/java/anthos/samples/bankofanthos/balancereader/Transaction.java；bank-of-anthos: jpa src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/TransactionRepository.java |
| 10 | train-ticket、Bank of Anthos | 8：bank-of-anthos、ecommerce、online-boutique、robot-shop、train-ticket、mall-swarm、hotel-reservation、aics | 6：ecommerce、online-boutique、robot-shop、mall-swarm、hotel-reservation、aics | bank-of-anthos: drop populate-ledger-db (src/ledger/ledger-db/k8s/components/init-db-job)；bank-of-anthos: drop populate-accounts-db (src/accounts/accounts-db/k8s/components/init-db-job)；bank-of-anthos: drop populate-ledger-db (extras/cloudsql/populate-jobs) |
| 11 | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 16：piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | 12：piggymetrics、teastore、robot-shop、train-ticket、spring-cloud-netflix、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | piggymetrics: node rabbitmq；piggymetrics: node config；piggymetrics: node registry |
| 12 | Tap-And-Eat、spring-cloud-microservice | 6：microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、mall-swarm | 4：microservices-book、spring-cloud-netflix、lakeside-mutual、mall-swarm | microservices-book: links customer->eureka；microservices-book: links catalog->eureka；microservices-book: links order->eureka |
| 13 | LakesideMutual | 1：lakeside-mutual | 0：— | lakeside-mutual: drop http://lm.com/ccore；lakeside-mutual: drop http://lm.com/ccore/GetCustomerRequest；lakeside-mutual: drop http://lakesdemutual.com/interfaces/xsd |
| 14 | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 11：bank-of-anthos、teastore、online-boutique、robot-shop、lakeside-mutual、opentelemetry-demo、yas、run-aspnetcore-microservices、travels、skywalking-showcase、aics | 7：robot-shop、opentelemetry-demo、yas、run-aspnetcore-microservices、travels、skywalking-showcase、aics | bank-of-anthos: skip-configmap ledgerwriter->balancereader；bank-of-anthos: skip-configmap ledgerwriter->transactionhistory；bank-of-anthos: skip-configmap ledgerwriter->contacts |
| 15 | ecommerce | 5：ecommerce、tap-and-eat、spring-cloud-microservice、lakeside-mutual、yas | 4：tap-and-eat、spring-cloud-microservice、lakeside-mutual、yas | ecommerce: configserver-prefix optional:configserver:http://localhost:9296；ecommerce: edge product-service->cloud-config :9296；ecommerce: edge api-gateway->cloud-config :9296 |
| 16 | ecommerce | 3：ecommerce、spring-petclinic、spring-cloud-netflix | 2：spring-petclinic、spring-cloud-netflix | ecommerce: edge product-service->service-discovery；ecommerce: edge cloud-config->service-discovery；ecommerce: edge api-gateway->service-discovery |
| 17 | TeaStore | 1：teastore | 0：— | teastore: edge teastore-webui->teastore-persistence；teastore: edge teastore-webui->teastore-recommender；teastore: edge teastore-webui->teastore-auth |
| 18 | robot-shop | 3：teastore、robot-shop、yas | 2：teastore、yas | teastore: dockerfile-env RABBITMQ_HOST=unset；robot-shop: dockerfile-env CATALOGUE_HOST=catalogue；robot-shop: dockerfile-env USER_HOST=user |
| 19 | AICS（私有系統） | 3：train-ticket、travels、aics | 2：train-ticket、travels | train-ticket: version ts-voucher-service-v2=ts-voucher-service；travels: version travels=travels.travel-portal；travels: version mysqldb-v1=mysqldb |
| 20 | AICS | 10：bank-of-anthos、online-boutique、robot-shop、lakeside-mutual、opentelemetry-demo、yas、run-aspnetcore-microservices、travels、skywalking-showcase、aics | 9：bank-of-anthos、online-boutique、robot-shop、lakeside-mutual、opentelemetry-demo、yas、run-aspnetcore-microservices、travels、skywalking-showcase | bank-of-anthos: scoped BALANCES_API_ADDR=balancereader @ledgerwriter；bank-of-anthos: scoped TRANSACTIONS_API_ADDR=ledgerwriter @frontend；bank-of-anthos: scoped USERSERVICE_API_ADDR=userservice @frontend |
| 21 | AICS | 1：aics | 0：— | aics: lib aics-toolkit-package；aics: lib aics-text-handler-package；aics: attribute AICS-toolkit-package/aics_toolkit/redis_handler.py=twitter-crawler-service |
| 22 | AICS | 4：bank-of-anthos、train-ticket、opentelemetry-demo、aics | 3：bank-of-anthos、train-ticket、opentelemetry-demo | bank-of-anthos: dead src/frontend/frontend.py:420；bank-of-anthos: dead src/frontend/frontend.py:421；train-ticket: dead build_upload_image.py:33 |
| 23 | piggymetrics | 1：piggymetrics | 0：— | piggymetrics: keep statistics-service eureka.client.；piggymetrics: keep account-service eureka.client.；piggymetrics: keep notification-service eureka.client. |
| 24 | opentelemetry-demo | 2：opentelemetry-demo、yas | 1：yas | opentelemetry-demo: forward otel-ebpf-profiler.OTEL_COLLECTOR_HOST=otel-collector；opentelemetry-demo: forward ad.FLAGD_HOST=flagd；opentelemetry-demo: interp ad.OTEL_EXPORTER_OTLP_ENDPOINT=otel-collector |
| 25 | mall-swarm | 2：mall-swarm、skywalking-showcase | 1：skywalking-showcase | mall-swarm: alias mall-admin:db=mysql；mall-swarm: alias mall-search:es=elasticsearch；mall-swarm: alias mall-search:db=mysql |
| 26 | mall-swarm | 0：— | 0：— |  |
| 27 | mall-swarm | 3：train-ticket、mall-swarm、yas | 2：train-ticket、yas | train-ticket: first-of-list spring.cloud.nacos.discovery.server-addr；train-ticket: list spring.cloud.nacos.discovery.server-addr=${NACOS_ADDRS:nacos-0.nacos-headless.default.svc.cluster.local；train-ticket: list spring.cloud.nacos.discovery.server-addr=${NACOS_ADDRS:127.0.0.1:8848} |
| 28 | opentelemetry-demo | 1：opentelemetry-demo | 0：— | opentelemetry-demo: kt-file src/fraud-detection/build.gradle.kts；opentelemetry-demo: kt-file src/fraud-detection/settings.gradle.kts；opentelemetry-demo: kt-file src/fraud-detection/src/main/kotlin/frauddetection/main.kt |
| 29 | mall-swarm、train-ticket | 3：train-ticket、spring-cloud-netflix、mall-swarm | 1：spring-cloud-netflix | train-ticket: drop https://github.com/FudanSELab/train-ticket；spring-cloud-netflix: drop https://github.com/yidongnan；mall-swarm: drop https://github.com/macrozheng/mall-learning |
| 30 | mall-swarm | 2：mall-swarm、yas | 1：yas | mall-swarm: edge mall-portal->rabbitmq；yas: edge webhook->kafka；yas: edge recommendation->kafka |
| 31 | opentelemetry-demo、hotelReservation | 4：bank-of-anthos、opentelemetry-demo、hotel-reservation、skywalking-showcase | 2：bank-of-anthos、skywalking-showcase | bank-of-anthos: hidden .github/workflows/ui-tests/Dockerfile；opentelemetry-demo: skip-compose compose.tests.yaml；hotel-reservation: hidden .bingo/go.mod |
| 32 | opentelemetry-demo | 3：opentelemetry-demo、run-aspnetcore-microservices、skywalking-showcase | 2：run-aspnetcore-microservices、skywalking-showcase | opentelemetry-demo: connstr DB_CONNECTION_STRING；run-aspnetcore-microservices: connstr ConnectionStrings__Database；run-aspnetcore-microservices: connstr connectionstrings:database |
| 33 | opentelemetry-demo | 2：opentelemetry-demo、yas | 1：yas | opentelemetry-demo: drop vcr.local；yas: drop api.yas.local |
| 34 | opentelemetry-demo | 1：opentelemetry-demo | 0：— | opentelemetry-demo: url http://${OTEL_COLLECTOR_HOST}；opentelemetry-demo: url http://${FRONTEND_HOST}；opentelemetry-demo: url http://${IMAGE_PROVIDER_HOST} |
| 35 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 15：piggymetrics、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | 7：piggymetrics、train-ticket、mall-swarm、hotel-reservation、yas、run-aspnetcore-microservices、skywalking-showcase | piggymetrics: inferred registry->config；piggymetrics: inferred gateway->config；piggymetrics: inferred auth-service->config |
| 36 | run-aspnetcore-microservices、yas | 9：bank-of-anthos、robot-shop、train-ticket、opentelemetry-demo、yas、run-aspnetcore-microservices、travels、skywalking-showcase、aics | 7：bank-of-anthos、robot-shop、train-ticket、opentelemetry-demo、travels、skywalking-showcase、aics | bank-of-anthos: keep-dotted www.apache.org；bank-of-anthos: keep-dotted kopf.on.startup() async def startup(**_)；robot-shop: cluster-suffix web.robot-shop.svc.cluster.local |
| 37 | run-aspnetcore-microservices | 2：hotel-reservation、run-aspnetcore-microservices | 1：hotel-reservation | hotel-reservation: hostname user-memcached=memcached-rate；hotel-reservation: hostname user-memcached=memcached-profile；hotel-reservation: hostname user-memcached=memcached-reserve |
| 38 | run-aspnetcore-microservices | 1：run-aspnetcore-microservices | 0：— | run-aspnetcore-microservices: key ApiSettings:GatewayAddress；run-aspnetcore-microservices: yarp http://catalog.api:8080；run-aspnetcore-microservices: yarp http://ordering.api:8080 |
| 39 | run-aspnetcore-microservices | 2：run-aspnetcore-microservices、skywalking-showcase | 1：skywalking-showcase | run-aspnetcore-microservices: kind basketdb=db；run-aspnetcore-microservices: kind distributedcache=db；run-aspnetcore-microservices: kind catalogdb=db |
| 40 | run-aspnetcore-microservices | 3：online-boutique、opentelemetry-demo、run-aspnetcore-microservices | 2：online-boutique、opentelemetry-demo | online-boutique: cs-file src/cartservice/src/cartstore/ICartStore.cs；online-boutique: cs-file src/cartservice/src/cartstore/SpannerCartStore.cs；online-boutique: cs-file src/cartservice/src/cartstore/AlloyDBCartStore.cs |
| 41 | yas | 5：teastore、train-ticket、spring-cloud-microservice、mall-swarm、yas | 4：teastore、train-ticket、spring-cloud-microservice、mall-swarm | teastore: lib tools-descartes-teastore-kieker-probes；teastore: lib tools-descartes-teastore-registryclient；teastore: lib tools-descartes-teastore-entities |
| 42 | ts）也是測試 | 4：online-boutique、opentelemetry-demo、yas、aics | 4：online-boutique、opentelemetry-demo、yas、aics | online-boutique: ignore /src/frontend/validator/validator_test.go/；online-boutique: ignore /src/frontend/money/money_test.go/；online-boutique: ignore /src/productcatalogservice/product_catalog_test.go/ |
| 43 | yas | 3：train-ticket、mall-swarm、yas | 2：train-ticket、mall-swarm | train-ticket: drop swagger.controllerpackage；mall-swarm: drop springdoc.swagger-ui.path；mall-swarm: drop springdoc.swagger-ui.enabled |
| 44 | yas、run-aspnetcore-microservices、ewolff/microservice | 16：piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、microservices-book、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、yas、run-aspnetcore-microservices、travels、skywalking-showcase | 13：piggymetrics、teastore、robot-shop、train-ticket、spring-petclinic、tap-and-eat、spring-cloud-netflix、spring-cloud-microservice、lakeside-mutual、opentelemetry-demo、mall-swarm、travels、skywalking-showcase | piggymetrics: map mongodb=auth-mongodb；teastore: image tools-descartes-teastore-webui=webui；teastore: map tools-descartes-teastore-webui=teastore-webui |
| 45 | yas | 2：opentelemetry-demo、yas | 1：opentelemetry-demo | opentelemetry-demo: accept mcp_endpoint=mcp；opentelemetry-demo: accept agent_endpoint=agent；yas: accept elasticsearch.url=elasticsearch |
| 46 | yas | 1：yas | 0：— | yas: set-var http://search；yas: set-var http://webhook；yas: set-var http://recommendation |
| 47 | robot-shop、skywalking-showcase、AICS | 8：bank-of-anthos、online-boutique、robot-shop、spring-cloud-microservice、opentelemetry-demo、yas、skywalking-showcase、aics | 5：bank-of-anthos、online-boutique、spring-cloud-microservice、opentelemetry-demo、yas | bank-of-anthos: urlref src/frontend/frontend.py:691 metadata_url；bank-of-anthos: urlref src/frontend/frontend.py:707 metadata_url；online-boutique: urlref src/frontend/main.go:229 addr |
| 48 | robot-shop | 1：robot-shop | 0：— | robot-shop: default CATALOGUE_URL=catalogue；robot-shop: usesite MONGO_URL=mongodb；robot-shop: usesite REDIS_HOST=redis |
| 49 | hotelReservation | 1：hotel-reservation | 0：— | hotel-reservation: listed services => srv-review；hotel-reservation: listed services => srv-attractions；hotel-reservation: listed services => srv-search |
| 50 | yas | 1：yas | 0：— | yas: dotenv storefront/.env.production API_BASE_PATH=storefront；yas: dotenv storefront/.env.development API_BASE_PATH=storefront |
| 51 | AICS | 6：piggymetrics、robot-shop、train-ticket、spring-petclinic、travels、aics | 5：piggymetrics、robot-shop、train-ticket、spring-petclinic、travels | piggymetrics: browser gateway/src/main/resources/static/js/main.js:871；piggymetrics: browser gateway/src/main/resources/static/js/login.js:1；piggymetrics: browser gateway/src/main/resources/static/js/lib/jquery.min.js:2 |
| 52 | skywalking-showcase、yas、hotelReservation | 7：teastore、online-boutique、robot-shop、train-ticket、hotel-reservation、yas、skywalking-showcase | 4：teastore、online-boutique、robot-shop、train-ticket | teastore: render examples/helm/templates/auth-service.yaml；teastore: render examples/helm/templates/auth-statefulset.yaml；teastore: render examples/helm/templates/db-service.yaml |
| 53 | skywalking-showcase | 3：mall-swarm、yas、skywalking-showcase | 2：mall-swarm、yas | mall-swarm: compose mysql.command --character-set-server=utf8mb4；mall-swarm: bare-undeclared mysql utf8mb4；yas: compose search.entrypoint elasticsearch:9200=elasticsearch |
| 54 | travels | 2：travels、skywalking-showcase | 1：skywalking-showcase | travels: applied travel_agency.yaml -n travel-agency；travels: applied travel_portal.yaml -n travel-portal；travels: applied travel_control.yaml -n travel-control |
| 55 | travels | 4：train-ticket、yas、travels、skywalking-showcase | 3：train-ticket、yas、skywalking-showcase | train-ticket: list containers.input.conf github；train-ticket: list containers.input.conf elasticsearch-logging；yas: list application.yaml opentelemetry-collector |
| 56 | AICS、skywalking-showcase、Online Boutique | 6：bank-of-anthos、online-boutique、robot-shop、train-ticket、skywalking-showcase、aics | 3：bank-of-anthos、robot-shop、train-ticket | bank-of-anthos: istio frontend-gateway -> frontend；bank-of-anthos: edge istio-ingressgateway->frontend；online-boutique: istio asm-ingress/asm-ingressgateway -> frontend |

## 怎麼讀（2026-10-27，第五輪後：56 條規則、22 個系統）

**慣例（23 條，≥5 個系統）**：1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、35、36、41、44、47、51、52、56。第四輪的 20 條全部仍在 ≥5 個系統上觸發，
第五輪新增的規則有 4 條直接進來：第 47 條（呼叫點變數沿同檔綁定解析，取代第 22 條的 Python 專用解析器）在 8 個系統上改變結果，其中 5 個不是歸納它的；
第 51 條（HTML 載入的腳本是瀏覽器程式碼）在 AICS 歸納，piggymetrics、robot-shop、train-ticket、spring-petclinic、travels 的 static/ 裡都有同樣的頁面與腳本；
第 52 條（Helm 先渲染）在 7 個系統上渲染了 chart；第 56 條（入口路由）在 Bank of Anthos、Online Boutique、robot-shop、train-ticket 上都讀到綁 Gateway 的 VirtualService。
第 44 條（部署名對模組）加了 image 名與 package 名之後由 8 個系統擴大到 16 個。

**部分轉移（21 條，2–4 個系統）**：16、18、19、22、24、25、27、29、30、31、32、33、37、39、40、42、43、45、53、54、55。每一條都至少在一個**非**歸納系統上觸發；
第五輪的 53（命令列位址 → mall-swarm、yas）、54（namespace → skywalking-showcase 的 Helm namespace）、55（多位址值 → train-ticket、yas、skywalking-showcase）都是。
第 22 條在第五輪只剩死碼那一半，所以由 5 個系統降為 4 個。

**只在歸納它的系統上觸發（11 條）**：13、17、21、23、28、34、38、46、48、49、50。第四輪的 10 條中，第 25 條（Compose 別名）因為加上 network aliases 而在 skywalking-showcase 觸發、離開這一組；
第 28 條（Kotlin）改為有文法之後仍只有 opentelemetry-demo 有 .kt。新增的 3 條：48（環境變數讀取的預設值，robot-shop）、49（註冊中心名稱，hotelReservation）、50（模組 dotenv，yas）——
它們是某種寫法在語料裡只出現一次（Consul 名稱經參數傳到 dial、Next.js 的 .env.production、PHP 的 getenv ?: URL），要再有一個系統才知道是慣例還是特例。

**從未觸發（1 條）**：第 26 條（連線 scheme 決定節點種類），理由同前：語料裡 scheme 說的與主機名說的一致。

**計數位置的取捨**：同上一版，另外——第 28／40 條數的是以文法（或薄表面）讀的 .kt／.cs 檔；第 47 條數解開的呼叫點；第 52 條數渲染的 template 檔與「渲染的 chart 不是詞彙表時被擋下的主機」；
第 25 條的 network alias 一個別名算一次（不是每個讀者一次）；第 55 條在抽取時數被拆開的值，所以 train-ticket 的 fluentd ConfigMap 也算進去（它沒有改變任何邊）。

## 附錄用精簡版（規則、歸納自、觸發系統數、合計命中）

| # | 規則（首句） | 歸納自 | 觸發系統數 | 合計 |
|---|---|---|---|---|
| 1 | manifest 的 workload 名稱（Deployment、Sta… | ewolff、TeaStore、Online Boutique | 10/22 | 221 |
| 2 | 模組目錄名正規化 | TeaStore、Online Boutique、ewolff | 13/22 | 80 |
| 3 | Spring Cloud Config 倉庫版面 | piggymetrics | 5/22 | 89 |
| 4 | 佔位符依序查環境變數、屬性表、${key:default} 的預設值，最後… | piggymetrics、ewolff、robot-shop | 13/22 | 168 |
| 5 | 裸主機名也是目標 | piggymetrics、TeaStore、robot-shop | 12/22 | 127 |
| 6 | 反向代理設定是入口的邊清單 | ewolff、robot-shop | 5/22 | 40 |
| 7 | Compose 描述的是服務之間的連線設定，不是某個服務的設定 | TeaStore、robot-shop、ecommerce | 9/22 | 189 |
| 8 | 不是目標的東西不畫 | ecommerce、piggymetrics、TeaStore | 16/22 | 160 |
| 9 | 宣告不等於使用 | ecommerce、TeaStore | 15/22 | 282 |
| 10 | 只認「部署了這個 repo 自己模組」的 manifest 目錄 | train-ticket、Bank of Anthos | 8/22 | 103 |
| 11 | Compose 的 services 也是部署名稱清單，與 Kuberne… | spring-petclinic、microservices-book、Tap-And-Eat、spring-cloud-microservice | 16/22 | 204 |
| 12 | 也認 Compose 第 1 版格式（沒有 services:，頂層即服務… | Tap-And-Eat、spring-cloud-microservice | 6/22 | 82 |
| 13 | XML namespace 不是位址 | LakesideMutual | 1/22 | 3 |
| 14 | workload 自己環境變數裡的服務位址是宣告，畫成僅推測；共用 Con… | Online Boutique、Bank of Anthos、TeaStore、LakesideMutual | 11/22 | 312 |
| 15 | loopback 位址以 port 讀回擁有者 | ecommerce | 5/22 | 48 |
| 16 | Spring Cloud 的隱含註冊中心 | ecommerce | 3/22 | 20 |
| 17 | 字串值等於模組名的常數就是被呼叫端 | TeaStore | 1/22 | 7 |
| 18 | String.format("http://%s/…", getenv("… | robot-shop | 3/22 | 21 |
| 19 | 帶版本字尾的 Deployment 是某個 Service 的一個版本 | AICS（私有系統） | 3/22 | 20 |
| 20 | 環境變數在讀取者自己的範圍解析 | AICS | 10/22 | 114 |
| 21 | 函式庫不是服務 | AICS | 1/22 | 7 |
| 22 | 呼叫點才算使用（Python） | AICS | 4/22 | 21 |
| 23 | 共用設定的 key 只在有對應函式庫的 client 生效 | piggymetrics | 1/22 | 24 |
| 24 | Compose 的鍵名轉發與 .env 內插 | opentelemetry-demo | 2/22 | 153 |
| 25 | links 與 external_links 的別名是該服務對目標的稱呼 | mall-swarm | 2/22 | 94 |
| 26 | 連線 scheme 決定節點種類 | mall-swarm | 0/22 | 0 |
| 27 | 位址清單 key 與裸 FQDN 的 endpoint | mall-swarm | 3/22 | 14 |
| 28 | Kotlin | opentelemetry-demo | 1/22 | 3 |
| 29 | OpenAPI／Swagger 中繼資料的 URL 不是呼叫 | mall-swarm、train-ticket | 3/22 | 4 |
| 30 | 訊息程式碼的邊是服務 → broker | mall-swarm | 2/22 | 4 |
| 31 | 測試 fixture 不是服務（規則 2 的延伸） | opentelemetry-demo、hotelReservation | 4/22 | 5 |
| 32 | key=value; 連線字串的主機 | opentelemetry-demo | 3/22 | 4 |
| 33 | 保留 TLD 不是相依 | opentelemetry-demo | 2/22 | 2 |
| 34 | Envoy 靜態設定是反向代理的上游表（規則 6 的延伸） | opentelemetry-demo | 1/22 | 12 |
| 35 | depends_on 是啟動順序，分級處理 | opentelemetry-demo（錯邊）、MicroDepGraph 7 系統（保住 90 條） | 15/22 | 343 |
| 36 | 只剝真正的 DNS 字尾 | run-aspnetcore-microservices、yas | 9/22 | 54 |
| 37 | Compose 的 hostname: 是該服務的全域別名（規則 25 的… | run-aspnetcore-microservices | 2/22 | 13 |
| 38 | YARP 的 ReverseProxy.Clusters.*.Destin… | run-aspnetcore-microservices | 1/22 | 12 |
| 39 | 值的形狀決定節點種類（規則 26 的延伸） | run-aspnetcore-microservices | 2/22 | 8 |
| 40 | C# | run-aspnetcore-microservices | 3/22 | 166 |
| 41 | JVM 函式庫模組不是服務（規則 21 的 Java 版） | yas | 5/22 | 54 |
| 42 | 測試樹的每一段都算（規則 2、31 的延伸） | ts）也是測試 | 4/22 | 132 |
| 43 | OpenAPI UI 的設定鍵不是呼叫（規則 29 的設定檔版） | yas | 3/22 | 25 |
| 44 | compose 的 build 說了哪個模組是哪個服務 | yas、run-aspnetcore-microservices、ewolff/microservice | 16/22 | 145 |
| 45 | *.url／*.uri／*.endpoint 鍵下的裸單字只在它是部署宣告… | yas | 2/22 | 3 |
| 46 | nginx 的 set $var "x"; proxy_pass http… | yas | 1/22 | 5 |
| 47 | 呼叫點的變數沿同檔綁定解析，所有文法共用（規則 22 的通用化） | robot-shop、skywalking-showcase、AICS | 8/22 | 218 |
| 48 | 環境變數讀取的預設值 | robot-shop | 1/22 | 5 |
| 49 | 註冊中心名稱列出、不猜 | hotelReservation | 1/22 | 9 |
| 50 | 模組裡的 dotenv 檔（.env、.env.<mode>；旁邊沒有 c… | yas | 1/22 | 2 |
| 51 | HTML 頁面以 <script src> 載入的腳本在瀏覽器執行 | AICS | 6/22 | 166 |
| 52 | Helm chart 先渲染再讀 | skywalking-showcase、yas、hotelReservation | 7/22 | 281 |
| 53 | 容器的命令列也是佈線 | skywalking-showcase | 3/22 | 23 |
| 54 | namespace 決定身分 | travels | 2/22 | 13 |
| 55 | 一個值列出多個位址 | travels | 4/22 | 41 |
| 56 | 入口路由 | AICS、skywalking-showcase、Online Boutique | 6/22 | 101 |

