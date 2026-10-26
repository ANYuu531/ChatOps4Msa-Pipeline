# Hold-out：靜態 greenfield 圖 vs 參考邊集（程式計分，after）

由 `docs/generalization/holdout/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.after.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hotel-reservation | 22 | 22 | 0 | 9 | 1.00 | 0.71 | — / 0.00 | 1.00 / 1.00 | — / 1.00 | — | 0 |
| mall-swarm | 28 | 12 | 16 | 12 | 0.43 | 0.50 | 0.12 / 1.00 | 0.80 / 0.40 | — / 0.50 | — / 0.50 | 11 |
| opentelemetry-demo | 39 | 31 | 8 | 7 | 0.79 | 0.82 | 0.64 / 0.93 | 1.00 / 0.83 | — / 0.71 | — | 32 |
| **合計** | 89 | 65 | 24 | 28 | 0.73 | 0.70 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## hotel-reservation

參考邊集：business 9、data 12、control 10（可計分 31）＋ variant 10。


漏（truth 有、工具沒畫，9 條）：

- frontend -> search  ·  services/frontend/server.go:64 initSearchClient("srv-search") → dialer.Dial 經 Consul 解析  ·  **缺文法**：Go 沒有 pattern；呼叫目標是 Consul 服務名 "srv-search"（dialer.Dial 經 registry 解析），程式碼與 manifest 都沒有主機名——與 TeaStore 的 registry 邊界同型
- frontend -> profile  ·  services/frontend/server.go:68 initProfileClient("srv-profile")  ·  **缺文法**：同上
- frontend -> recommendation  ·  services/frontend/server.go:72 initRecommendationClient("srv-recommendation")  ·  **缺文法**：同上
- frontend -> user  ·  services/frontend/server.go:76 initUserClient("srv-user")  ·  **缺文法**：同上
- frontend -> reservation  ·  services/frontend/server.go:80 initReservation("srv-reservation")  ·  **缺文法**：同上
- frontend -> review  ·  services/frontend/server.go:84 initReviewClient("srv-review")  ·  **缺文法**：同上
- frontend -> attractions  ·  services/frontend/server.go:88 initAttractionsClient("srv-attractions")  ·  **缺文法**：同上
- search -> geo  ·  services/search/server.go:70 initGeoClient("srv-geo")  ·  **缺文法**：同上
- search -> rate  ·  services/search/server.go:73 initRateClient("srv-rate")  ·  **缺文法**：同上

## mall-swarm

參考邊集：business 2、data 10、external 2、control 10（可計分 24）＋ variant 13。


錯（工具畫了、truth 沒有，16 條）：

- mall-admin -> db  (sync-http, documented)  code: config/admin/mall-admin-prod.yaml  ·  **規則邊界**：compose-app 的 `external_links: - mysql:db`（document/docker/docker-compose-app.yml:14）宣告 db 是 mysql 的別名；工具不讀 external_links，links 也只取冒號前的服務名、不登記別名，所以 prod 設定的 jdbc:mysql://db 變成獨立節點 db。另外 jdbc: URL 被標成 sync-http 而不是 db（bug）
- mall-admin -> github.com  (external, documented)  code: mall-admin/src/main/java/com/macro/mall/config/SpringDocConfig.java:32  ·  **bug**：SpringDocConfig.java:32 是 OpenAPI License().url(...)，文件中繼資料，不是呼叫；五個模組各兩條，共 10 條假外部邊
- mall-admin -> www.macrozheng.com  (external, documented)  code: mall-admin/src/main/java/com/macro/mall/config/SpringDocConfig.java:35  ·  **bug**：SpringDocConfig.java:35 ExternalDocumentation().url(...)，同上
- mall-auth -> github.com  (external, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/config/SpringDocConfig.java:31  ·  **bug**：同上
- mall-auth -> www.macrozheng.com  (external, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/config/SpringDocConfig.java:34  ·  **bug**：同上
- mall-demo -> db  (sync-http, documented)  code: config/demo/mall-demo-prod.yaml  ·  **規則邊界**：同上（且 mall-demo 未部署，truth 列 variant）
- mall-demo -> github.com  (external, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/config/SpringDocConfig.java:32  ·  **bug**：同上
- mall-demo -> www.macrozheng.com  (external, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/config/SpringDocConfig.java:35  ·  **bug**：同上
- mall-portal -> db  (sync-http, documented)  code: config/portal/mall-portal-prod.yaml  ·  **規則邊界**：同上
- mall-portal -> github.com  (external, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/config/SpringDocConfig.java:32  ·  **bug**：同上
- mall-portal -> rabbit  (async, documented)  code: config/portal/mall-portal-prod.yaml  ·  **規則邊界**：`external_links: - rabbitmq:rabbit`（docker-compose-app.yml:44）的別名，同 db
- mall-portal -> www.macrozheng.com  (external, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/config/SpringDocConfig.java:35  ·  **bug**：同上
- mall-search -> db  (sync-http, documented)  code: config/search/mall-search-prod.yaml  ·  **規則邊界**：同上
- mall-search -> github.com  (external, documented)  code: mall-search/src/main/java/com/macro/mall/search/config/SpringDocConfig.java:26  ·  **bug**：同上
- mall-search -> www.macrozheng.com  (external, documented)  code: mall-search/src/main/java/com/macro/mall/search/config/SpringDocConfig.java:29  ·  **bug**：同上
- mall.order.cancel -> mall-portal  (async, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderReceiver.java:16  ·  **bug**：@RabbitListener(queues="mall.order.cancel") 的 queue 名被當成 broker 節點、方向 queue→consumer；參考邊集慣例是 consumer → broker 主機（rabbitmq）

對的裡面有 4 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，12 條）：

- mall-admin -> mysql  ·  mall-admin/src/main/java/com/macro/mall/config/MyBatisConfig.java:13 @MapperScan（MyBatis）；config/admin/mall-admin-prod.yaml:15 jdbc:mysql://db:3306/mall（db＝compose-app external_links 的 mysql 別名，document/docker/docker-compose-app.yml:14）  ·  **規則邊界**：畫成 mall-admin -> db（別名未解析，見上）
- mall-admin -> minio  ·  mall-admin/src/main/java/com/macro/mall/controller/MinioController.java:47 MinioClient.builder().endpoint(ENDPOINT)（:32 ${minio.endpoint}；config/admin/mall-admin-prod.yaml:25 http://192.168.3.101:9090）  ·  **truth 存疑**：prod 設定 minio.endpoint 是 IP http://192.168.3.101:9090（unresolved 有列），沒有任何 manifest 叫 minio；參考邊集的節點名 minio 本來就對不上任何靜態字串
- mall-portal -> mysql  ·  mall-portal/src/main/java/com/macro/mall/portal/config/MyBatisConfig.java:13 @MapperScan；config/portal/mall-portal-prod.yaml:3 jdbc:mysql://db:3306/mall  ·  **規則邊界**：畫成 mall-portal -> db
- mall-portal -> rabbitmq  ·  mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderSender.java:25 amqpTemplate.convertAndSend ＋ CancelOrderReceiver.java:16 @RabbitListener；config/portal/mall-portal-prod.yaml:17 spring.rabbitmq.host: rabbit（rabbit＝rabbitmq 的別名，docker-compose-app.yml:44）  ·  **規則邊界**：畫成 mall-portal -> rabbit
- mall-search -> mysql  ·  mall-search/src/main/java/com/macro/mall/search/config/MyBatisConfig.java:11 @MapperScan；config/search/mall-search-prod.yaml:3 jdbc:mysql://db:3306/mall  ·  **規則邊界**：畫成 mall-search -> db
- mall-search -> elasticsearch  ·  mall-search/src/main/java/com/macro/mall/search/repository/EsProductRepository.java:12 ElasticsearchRepository；config/search/mall-search-prod.yaml:7 spring.elasticsearch.uris: es:9200（es＝elasticsearch 的別名）  ·  **規則邊界**：config/search/mall-search-prod.yaml:7 `spring.elasticsearch.uris: es:9200`：es 是 external_links 別名，且 `uris` 不在工具認得的位址鍵名裡；elasticsearch 節點是從 compose-env 的 ELK depends_on 來的
- mall-admin -> oss-cn-shenzhen.aliyuncs.com  ·  mall-admin/src/main/java/com/macro/mall/config/OssConfig.java:22 new OSSClient(ALIYUN_OSS_ENDPOINT,…)（config/admin/mall-admin-prod.yaml:3 與 mall-admin/src/main/resources/application.yml:61 aliyun.oss.endpoint: oss-cn-shenzhen.aliyuncs.com）  ·  **規則邊界**：aliyun.oss.endpoint 的值是裸 FQDN、沒有 scheme、鍵名是 endpoint 不是 host/url；工具的 addressHost 不收這個形狀（同檔 alipay.gatewayUrl 有 https:// 就畫到了）
- mall-admin -> nacos-registry  ·  mall-admin/src/main/resources/application-prod.yml:5 spring.cloud.nacos.discovery.server-addr: nacos-registry:8848（:7 config.server-addr 同）  ·  **框架慣例**：Spring Cloud Alibaba：spring.cloud.nacos.discovery.server-addr: nacos-registry:8848（host:port、無 scheme）；工具只認 eureka.*、spring.cloud.consul、spring.cloud.config.uri。mall-monitor 那條有 http:// 所以畫到了
- mall-auth -> nacos-registry  ·  mall-auth/src/main/resources/application-prod.yml:5 spring.cloud.nacos.discovery.server-addr: nacos-registry:8848  ·  **框架慣例**：同上
- mall-gateway -> nacos-registry  ·  mall-gateway/src/main/resources/application-prod.yml:5 spring.cloud.nacos.discovery.server-addr: nacos-registry:8848  ·  **框架慣例**：同上
- mall-portal -> nacos-registry  ·  mall-portal/src/main/resources/application-prod.yml:5 spring.cloud.nacos.discovery.server-addr: nacos-registry:8848  ·  **框架慣例**：同上
- mall-search -> nacos-registry  ·  mall-search/src/main/resources/application-prod.yml:5 spring.cloud.nacos.discovery.server-addr: nacos-registry:8848  ·  **框架慣例**：同上

工具另外畫到的 variant 邊（不計分，11 條）：

- kibana -> elasticsearch  (db, documented)  ·  document/docker/docker-compose-env.yml:76 links elasticsearch:es、:80 elasticsearch.hosts=http://es:9200（ELK 遙測；2026-10-23 計分後補）
- logstash -> elasticsearch  (db, documented)  ·  document/docker/docker-compose-env.yml:64 depends_on elasticsearch ＋ :66 links elasticsearch:es（ELK 遙測；2026-10-23 計分後補：標註時只列了服務→logstash，漏了 ELK 內部）
- mall-admin -> logstash  (sync-http, documented)  ·  config/admin/mall-admin-prod.yaml logstash.host: logstash（遙測）
- mall-demo -> logstash  (sync-http, documented)  ·  config/demo/mall-demo-prod.yaml logstash.host: logstash（遙測）
- mall-demo -> mall-admin  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignAdminService.java:13 @FeignClient("mall-admin")
- mall-demo -> mall-portal  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignPortalService.java:12 @FeignClient("mall-portal")
- mall-demo -> mall-search  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignSearchService.java:11 @FeignClient("mall-search")
- mall-gateway -> logstash  (sync-http, documented)  ·  config/gateway/mall-gateway-prod.yaml logstash.host: logstash（遙測）
- mall-gateway -> mall-demo  (sync-http, documented)  ·  mall-gateway/src/main/resources/application.yml:45 uri: lb://mall-demo（mall-demo 沒有 manifest）
- mall-portal -> logstash  (sync-http, documented)  ·  config/portal/mall-portal-prod.yaml logstash.host: logstash（遙測）
- mall-search -> logstash  (sync-http, documented)  ·  config/search/mall-search-prod.yaml logstash.host: logstash（遙測）

## opentelemetry-demo

參考邊集：business 15、data 6、control 17（可計分 38）＋ variant 51。


錯（工具畫了、truth 沒有，8 條）：

- agent -> product-catalog  (sync-http, documented)  code: compose.agent.yaml  ·  **規則邊界**：只有 compose.agent.yaml 的 depends_on，程式碼裡 agent 不呼叫 product-catalog（走 mcp → frontend API）。depends_on 是啟動順序，不是呼叫
- agent -> vcr  (sync-http, documented)  code: src/agent/src/agents/patch_vcr.py:47  ·  **bug**：src/agent/src/agents/patch_vcr.py:47 把錄製請求的 uri 改寫成假網址 https://vcr.local/（測試用 VCR 卡匣），不是對外呼叫；URL 字面值在測試輔助程式裡
- flagd-ui -> flagd  (sync-http, documented)  code: compose.yaml  ·  **規則邊界**：只有 depends_on；flagd-ui 用共用 volume 改 flagd 的 JSON 檔，沒有網路連線
- frontend -> flagd  (sync-http, documented)  code: compose.yaml  ·  **truth 存疑**：frontend 的 feature flag 由瀏覽器經 frontend-proxy /flagservice 連 flagd（src/frontend/pages/_app.tsx:35 註解），frontend 容器本身不連；但 compose.yaml 確實把 FLAGD_HOST 給了 frontend。工具靠的是 depends_on
- frontend -> image-provider  (sync-http, documented)  code: compose.yaml  ·  **規則邊界**：只有 depends_on（healthcheck 順序）；圖片由瀏覽器經 frontend-proxy /images/ 取
- frontend -> quote  (sync-http, documented)  code: compose.yaml  ·  **規則邊界**：只有 depends_on；quote 的呼叫端是 shipping
- frontendtests -> frontend  (sync-http, documented)  code: compose.tests.yaml  ·  **規則邊界**：來自 compose.tests.yaml 的測試容器；規則 2 把 *-tests 目錄排除，但 compose 覆蓋層裡的測試服務沒有同樣處理
- load-generator -> frontend  (sync-http, documented)  code: compose.yaml  ·  **規則邊界**：只有 depends_on；真正的目標是 LOCUST_HOST=http://frontend-proxy:8080（見漏的那條）

漏（truth 有、工具沒畫，7 條）：

- shipping -> quote  ·  src/shipping/src/shipping_service/quote.rs:69 client.post(quote_service_addr)（:52 QUOTE_ADDR，預設 http://quote:8090；.env:126）  ·  **缺文法**：Rust 沒有 pattern；compose 只寫 `- QUOTE_ADDR`（鍵名，值在 .env），工具的 compose environment 只收 K=V 形式（ConfigExtractor.extractComposeEnvironment），.env 的值又沒有來源服務
- accounting -> astronomy-db  ·  src/accounting/Consumer.cs:24 UseNpgsql(connectionString)（:22 DB_CONNECTION_STRING；compose.full.yaml:33 Host=${POSTGRES_HOST}）  ·  **框架慣例**：.NET Npgsql 連線字串 `Host=${POSTGRES_HOST};Username=…`（compose.full.yaml:33）不是 URL，而且 ${POSTGRES_HOST} 要從 .env 內插；C# 無文法
- frontend-proxy -> image-provider  ·  src/frontend-proxy/envoy.tmpl.yaml:68 /images/ → cluster image-provider（:223 ${IMAGE_PROVIDER_HOST}）  ·  **框架慣例**：Envoy 設定（envoy.tmpl.yaml 的 route → cluster → socket_address）沒有解析器；工具只認 nginx/Apache 的 proxy_pass。畫到的 4 條 frontend-proxy 邊全來自 depends_on
- frontend-proxy -> flagd  ·  src/frontend-proxy/envoy.tmpl.yaml:72 /flagservice/ → cluster flagservice（:236 ${FLAGD_HOST}；瀏覽器端的 feature flag 走這條）  ·  **框架慣例**：同上（Envoy cluster flagservice → ${FLAGD_HOST}）
- load-generator -> frontend-proxy  ·  .env:106 LOCUST_HOST=http://${FRONTEND_PROXY_ADDR}（locust 直接讀 LOCUST_HOST 當 host；compose.yaml:438 傳入；.env:90 FRONTEND_PROXY_ADDR=frontend-proxy:8080）  ·  **規則邊界**：compose 用 `- LOCUST_HOST` 鍵名轉發，值 http://${FRONTEND_PROXY_ADDR} 在 .env，需要兩層內插；.env 讀進來的列 source 是 null（unresolved 列出 `null => http://frontend-proxy @ .env`）
- email -> flagd  ·  src/email/email_server.rb:23 config.host = ENV.fetch("FLAGD_HOST")  ·  **缺文法**：Ruby 沒有 pattern；compose 只給 `- FLAGD_HOST` 鍵名；email 沒有 depends_on flagd
- fraud-detection -> flagd  ·  src/fraud-detection/src/main/kotlin/frauddetection/main.kt:35 FlagdProvider(options)（FLAGD_HOST env；compose.full.yaml）  ·  **缺文法**：Kotlin（.kt）不在 java pattern 的副檔名裡，雖然 build.gradle 讓它被判成 java/spring；compose.full.yaml 只給 `- FLAGD_HOST` 鍵名；沒有 depends_on flagd

工具另外畫到的 variant 邊（不計分，32 條）：

- accounting -> otel-collector  (sync-http, documented)  ·  compose.full.yaml accounting.environment（遙測）
- ad -> otel-collector  (sync-http, documented)  ·  compose.yaml ad.environment OTEL_EXPORTER_OTLP_ENDPOINT=http://${OTEL_COLLECTOR_HOST}:…（遙測）
- agent -> flagd  (sync-http, documented)  ·  src/agent/src/agents/feature_flags.py:16（compose.agent.yaml）
- agent -> mcp  (sync-http, documented)  ·  src/agent/src/agents/agents.py:45 http://{MCP_ENDPOINT}:{MCP_PORT}/mcp（compose.agent.yaml）
- agent -> otel-collector  (sync-http, documented)  ·  compose.agent.yaml TRACELOOP_BASE_URL（遙測）
- cart -> opamp-server  (sync-http, documented)  ·  compose.observability.yaml:207 OPAMP_SERVER_ENDPOINT（src/cart/src/Program.cs:103）
- cart -> otel-collector  (sync-http, documented)  ·  compose.yaml cart.environment OTEL_EXPORTER_OTLP_ENDPOINT（.env:48，遙測）
- chatbot -> agent  (sync-http, documented)  ·  src/chatbot/src/chat_interface/chat_interface.py:151 http://{AGENT_ENDPOINT}:{AGENT_PORT}/prompt（compose.agent.yaml）
- chatbot -> opamp-server  (sync-http, documented)  ·  src/chatbot/src/opamp.py:136 OPAMP_SERVER_ENDPOINT（compose.agent.yaml）
- checkout -> otel-collector  (sync-http, documented)  ·  compose.yaml checkout.environment（遙測）
- currency -> otel-collector  (sync-http, documented)  ·  compose.yaml currency.environment（遙測）
- email -> otel-collector  (sync-http, documented)  ·  compose.yaml email.environment（遙測）
- flagd-ui -> otel-collector  (sync-http, documented)  ·  compose.yaml flagd-ui.environment（遙測）
- fraud-detection -> otel-collector  (sync-http, documented)  ·  compose.full.yaml fraud-detection.environment（遙測）
- frontend -> otel-collector  (sync-http, documented)  ·  compose.yaml frontend.environment（遙測）
- frontend-proxy -> chatbot  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:90 /chatbot/（compose.agent.yaml）
- frontend-proxy -> grafana  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:56（compose.observability.yaml）
- frontend-proxy -> jaeger  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:52（compose.observability.yaml）
- frontend-proxy -> opamp-server  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:60（compose.observability.yaml）
- image-provider -> otel-collector  (sync-http, documented)  ·  compose.yaml image-provider.environment OTEL_COLLECTOR_HOST（遙測）
- load-generator -> agent  (sync-http, documented)  ·  src/load-generator/locustfile.py:138 AGENT_ENDPOINT（compose.agent.yaml 才給）
- mcp -> frontend  (sync-http, documented)  ·  src/shared/tools.py:42 http://{BASE_URL}/api/data（:16 BASE_URL=APPLICATION_ENDPOINT=frontend:8080；Dockerfile 把 tools.py 複製進 mcp；compose.agent.yaml）—— 2026-10-23 計分後補：工具從 depends_on 畫到，標註時漏了 shared/ 這支共用檔
- otel-collector -> jaeger  (sync-http, documented)  ·  src/otel-collector/otelcol-config-observability.yml:21 endpoint jaeger:4317
- otel-collector -> opamp-server  (sync-http, documented)  ·  src/otel-collector/otelcol-config-observability.yml:50
- otel-collector -> opensearch  (sync-http, documented)  ·  src/otel-collector/otelcol-config-observability.yml:36
- otel-ebpf-profiler -> otel-collector  (sync-http, documented)  ·  compose.profiling.yaml otel-ebpf-profiler（遙測；2026-10-23 計分後補：profiling 覆蓋層標註時漏）
- payment -> otel-collector  (sync-http, documented)  ·  compose.yaml payment.environment（遙測）
- product-catalog -> opamp-server  (sync-http, documented)  ·  compose.observability.yaml:215 OPAMP_SERVER_ENDPOINT
- product-catalog -> otel-collector  (sync-http, documented)  ·  compose.yaml product-catalog.environment（遙測）
- quote -> otel-collector  (sync-http, documented)  ·  compose.yaml quote.environment（遙測）
- recommendation -> otel-collector  (sync-http, documented)  ·  compose.yaml recommendation.environment（遙測）
- shipping -> otel-collector  (sync-http, documented)  ·  compose.yaml shipping.environment（遙測）
