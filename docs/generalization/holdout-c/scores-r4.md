# Hold-out set C：靜態 greenfield 圖 vs 參考邊集（程式計分，r4）

由 `docs/generalization/holdout-c/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r4.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| run-aspnetcore-microservices | 11 | 11 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 | — | 0 |
| yas | 89 | 74 | 15 | 3 | 0.83 | 0.96 | 0.53 / 0.89 | 1.00 / 0.95 | — / 1.00 | — | 31 |
| **合計** | 100 | 85 | 15 | 3 | 0.85 | 0.97 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## run-aspnetcore-microservices

參考邊集：business 2、data 6、control 3（可計分 11）＋ variant 0。


全對。


## yas

參考邊集：business 19、data 19、control 39（可計分 77）＋ variant 40。


錯（工具畫了、truth 沒有，15 條）：

- backoffice -> order  (sync-http, inferred)  code: backoffice-bff/src/main/resources/application-dev.yaml
- cart -> media  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:190 YAS_SERVICES_MEDIA 傳給 cart，cart 程式碼不呼叫 media　→ [r3 仍在] 規則 14 的邊界
- payment -> storefront  (sync-http, documented)  code: payment/src/main/resources/application.properties  ·  **規則邊界**：payment/src/it/resources/application.properties:11（main 的 :17 同）yas.public.url=http://storefront/complete-payment 是付款完成後把瀏覽器導回 storefront 的網址，不是 payment 發出的呼叫；鍵名以 .url 結尾就被收　→ [r3 仍在] yas.public.url 是瀏覽器導回網址；main 的 application.properties 也有同一行，規則 42 只拿掉 src/it 那份
- payment-paypal -> storefront  (sync-http, documented)  code: payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34  ·  **規則邊界**：payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34 @Value("${yas.public.url}/capture") 是交給 PayPal 的 return_url／cancel_url（瀏覽器導回），同上；且 payment-paypal 只在 Helm 有　→ [r3 仍在] 同上（@Value 的 return_url）
- rating -> product  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:220 YAS_SERVICES_PRODUCT 傳給 rating，rating 程式碼不呼叫 product　→ [r3 仍在] 規則 14 的邊界
- storefront -> cart  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> customer  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> inventory  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> location  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> order  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> payment  (sync-http, inferred)  code: storefront-bff/src/main/resources/application-dev.yaml
- storefront -> promotion  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> rating  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> sampledata  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界
- storefront -> tax  (sync-http, inferred)  code: docker-compose.yml  ·  **truth 存疑**：docker-compose.yml:66–75 把 YAS_SERVICES_* 傳給 storefront（-bff），application.yaml 也宣告 yas.services.*，但 BFF 程式碼沒有任何 RestClient／WebClient 呼叫（只有 Gateway 路由）；規則 14 把 workload 自己 env 裡的服務位址畫成 inferred。參考邊集作者自註「工具若從 manifest 畫出來算錯」　→ [r3 仍在] 規則 14 的邊界

對的裡面有 21 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，3 條）：

- order -> promotion  ·  order/src/main/java/com/yas/order/service/PromotionService.java:34 restClient.post()（:29 serviceUrlConfig.promotion()；compose 沒傳 YAS_SERVICES_PROMOTION，值停在 order/src/main/resources/application.properties:18 http://api.yas.local/promotion ＝ nginx 的 server_name）  ·  **規則邊界**：compose 連鍵名都沒傳，值停在 order/src/main/resources/application.properties:18 http://api.yas.local/promotion；要畫到得知道 api.yas.local 是 nginx 的 server_name 再沿 nginx 的 /promotion/ 路由走，工具沒有 server_name 規則　→ [r3 仍在] 要認得 nginx server_name 當虛擬主機；刻意不做
- storefront-nextjs -> storefront  ·  storefront/modules/catalog/services/ProductService.ts:21 apiClientService.get(`${serverSideRenderUrl}/product/${slug}`)（SSR 端；:13 serverSideRenderUrl=${process.env.API_BASE_PATH}/product/storefront；storefront/.env.production:1 API_BASE_PATH=http://storefront/api）  ·  **缺文法**：Next.js（TypeScript）沒有 pattern（stacks 顯示 javascript LLM tier skipped）；位址 API_BASE_PATH=http://storefront/api 在 storefront/.env.production:1，也不是工具會讀的檔　→ [r3 仍在] Next.js／TypeScript 沒有文法；刻意不做
- kafka-connect -> postgres  ·  kafka/connects/debezium-product.json:7 database.hostname: postgres（Debezium PostgresConnector 讀 WAL；kafka/connects/debezium-order.json:7 同；由 start-source-connectors.sh:2–8 PUT 進 kafka-connect）  ·  **框架慣例**：Debezium source connector 的設定是 kafka/connects/debezium-*.json 的 database.hostname（由 start-source-connectors.sh 用 curl PUT 進 kafka-connect），不是任何工具認得的設定檔格式　→ [r3 仍在] Debezium connector JSON；刻意不做

工具另外畫到的 variant 邊（不計分，31 條）：

- backoffice -> collector  (sync-http, inferred)  ·  .env:9 OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:5555（docker-compose.yml:39 轉發；javaagent 遙測）
- backoffice -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:221 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- cart -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:193；遙測）
- collector -> prometheus  (sync-http, inferred)  ·  docker/otel-collector/otelcol-config.yml:27 prometheusremotewrite endpoint http://prometheus:9090/api/v1/write（遙測）
- collector -> tempo  (sync-http, inferred)  ·  docker/otel-collector/otelcol-config.yml:31 otlp endpoint tempo:4317（遙測）
- customer -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:165；遙測）
- inventory -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:343；遙測）
- kafka-ui -> kafka  (async, documented)  ·  docker-compose.yml:587 KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092（管理工具）
- keycloak -> postgres  (db, inferred)  ·  yaslocal.yaml:378 DB_ADDR value: "postgres"（過時的 k8s manifest 把 Keycloak 16 的 Deployment 叫 keycloak；compose 裡同一個東西叫 identity，見 data 段 identity → postgres）
- location -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:314；遙測）
- media -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:110；遙測）
- media -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:291 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- nginx -> grafana  (sync-http, documented)  ·  nginx/templates/default.conf.template:106 proxy_pass http://$docker_grafana_host:3000（遙測入口）
- nginx -> recommendation  (sync-http, documented)  ·  nginx/templates/default.conf.template:57 proxy_pass http://$docker_recommendation_host（recommendation 在 docker-compose.yml:473 被註解掉，只有 Helm chart）
- nginx -> webhook  (sync-http, documented)  ·  nginx/templates/default.conf.template:50 proxy_pass http://$docker_webhook_host（webhook 在 docker-compose.yml:445 被註解掉，只有 Helm chart）
- order -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:254；遙測）
- payment -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:285；遙測）
- payment-paypal -> identity  (sync-http, documented)  ·  payment-paypal/src/main/resources/application.properties:13 jwt.issuer-uri=http://identity/realms/Yas（只有 Helm）
- product -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:138；遙測）
- product -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:325 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- promotion -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:371；遙測）
- rating -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:223；遙測）
- recommendation -> identity  (sync-http, documented)  ·  recommendation/src/main/resources/application.properties:41 jwt.issuer-uri=http://identity/realms/Yas
- recommendation -> kafka  (async, documented)  ·  recommendation/src/main/java/com/yas/recommendation/kafka/consumer/ProductSyncDataConsumer.java:30 @KafkaListener（recommendation/src/main/resources/application.properties:49 kafka:9092；只有 Helm）
- sampledata -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:428；遙測）
- search -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.search.yml:15；遙測）
- storefront -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:79；遙測）
- storefront -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:186 OTEL_EXPORTER_OTLP_ENDPOINT value: http://jaeger:4317（過時的 k8s manifest；遙測）
- tax -> collector  (sync-http, inferred)  ·  .env:9（docker-compose.yml:399；遙測）
- webhook -> identity  (sync-http, documented)  ·  webhook/src/main/resources/application.properties:16 jwt.issuer-uri=http://identity/realms/Yas
- webhook -> kafka  (async, documented)  ·  webhook/src/main/java/com/yas/webhook/integration/inbound/OrderEventInbound.java:15 @KafkaListener（webhook/src/main/resources/application.properties:32 spring.kafka.bootstrap-servers=kafka:9092；只有 Helm）
