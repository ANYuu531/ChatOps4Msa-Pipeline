# Hold-out set C：靜態 greenfield 圖 vs 參考邊集（程式計分，r9）

由 `docs/generalization/holdout-c/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r9.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| run-aspnetcore-microservices | 11 | 11 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 | — | 0 |
| yas | 75 | 75 | 0 | 2 | 1.00 | 0.97 | 1.00 / 0.95 | 1.00 / 0.95 | — / 1.00 | — | 16 |
| **合計** | 86 | 86 | 0 | 2 | 1.00 | 0.98 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## run-aspnetcore-microservices

參考邊集：business 2、data 6、control 3（可計分 11）＋ variant 0。


全對。


## yas

參考邊集：business 19、data 19、control 39（可計分 77）＋ variant 40。


對的裡面有 4 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，2 條）：

- storefront-nextjs -> storefront  ·  storefront/modules/catalog/services/ProductService.ts:21 apiClientService.get(`${serverSideRenderUrl}/product/${slug}`)（SSR 端；:13 serverSideRenderUrl=${process.env.API_BASE_PATH}/product/storefront；storefront/.env.production:1 API_BASE_PATH=http://storefront/api）  ·  **缺文法**：Next.js（TypeScript）沒有 pattern（stacks 顯示 javascript LLM tier skipped）；位址 API_BASE_PATH=http://storefront/api 在 storefront/.env.production:1，也不是工具會讀的檔　→ [r3 仍在] Next.js／TypeScript 沒有文法；刻意不做
- kafka-connect -> postgres  ·  kafka/connects/debezium-product.json:7 database.hostname: postgres（Debezium PostgresConnector 讀 WAL；kafka/connects/debezium-order.json:7 同；由 start-source-connectors.sh:2–8 PUT 進 kafka-connect）  ·  **框架慣例**：Debezium source connector 的設定是 kafka/connects/debezium-*.json 的 database.hostname（由 start-source-connectors.sh 用 curl PUT 進 kafka-connect），不是任何工具認得的設定檔格式　→ [r3 仍在] Debezium connector JSON；刻意不做

工具另外畫到的 variant 邊（不計分，16 條）：

- collector -> loki  (sync-http, documented)  ·  docker/otel-collector/otelcol-config.yml:35 loki endpoint http://loki:3100/loki/api/v1/push（遙測）
- collector -> prometheus  (sync-http, documented)  ·  docker/otel-collector/otelcol-config.yml:27 prometheusremotewrite endpoint http://prometheus:9090/api/v1/write（遙測）
- collector -> tempo  (sync-http, documented)  ·  docker/otel-collector/otelcol-config.yml:31 otlp endpoint tempo:4317（遙測）
- kafka-ui -> kafka  (async, documented)  ·  docker-compose.yml:587 KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092（管理工具）
- keycloak -> postgres  (db, inferred)  ·  yaslocal.yaml:378 DB_ADDR value: "postgres"（過時的 k8s manifest 把 Keycloak 16 的 Deployment 叫 keycloak；compose 裡同一個東西叫 identity，見 data 段 identity → postgres）
- nginx -> grafana  (sync-http, documented)  ·  nginx/templates/default.conf.template:106 proxy_pass http://$docker_grafana_host:3000（遙測入口）
- nginx -> recommendation  (sync-http, documented)  ·  nginx/templates/default.conf.template:57 proxy_pass http://$docker_recommendation_host（recommendation 在 docker-compose.yml:473 被註解掉，只有 Helm chart）
- nginx -> webhook  (sync-http, documented)  ·  nginx/templates/default.conf.template:50 proxy_pass http://$docker_webhook_host（webhook 在 docker-compose.yml:445 被註解掉，只有 Helm chart）
- payment-paypal -> identity  (sync-http, documented)  ·  payment-paypal/src/main/resources/application.properties:13 jwt.issuer-uri=http://identity/realms/Yas（只有 Helm）
- prometheus -> collector  (sync-http, documented)  ·  docker/prometheus/prometheus.yml:9 scrape target collector:8888（遙測）
- recommendation -> identity  (sync-http, documented)  ·  recommendation/src/main/resources/application.properties:41 jwt.issuer-uri=http://identity/realms/Yas
- recommendation -> kafka  (async, documented)  ·  recommendation/src/main/java/com/yas/recommendation/kafka/consumer/ProductSyncDataConsumer.java:30 @KafkaListener（recommendation/src/main/resources/application.properties:49 kafka:9092；只有 Helm）
- recommendation -> postgres  (db, inferred)  ·  recommendation/src/main/java/com/yas/recommendation/configuration/VectorStoreConfiguration.java:31 vectorStore(JdbcTemplate …)（pgvector；recommendation/src/main/resources/application.properties:19；只有 Helm）
- webhook -> identity  (sync-http, documented)  ·  webhook/src/main/resources/application.properties:16 jwt.issuer-uri=http://identity/realms/Yas
- webhook -> kafka  (async, documented)  ·  webhook/src/main/java/com/yas/webhook/integration/inbound/OrderEventInbound.java:15 @KafkaListener（webhook/src/main/resources/application.properties:32 spring.kafka.bootstrap-servers=kafka:9092；只有 Helm）
- webhook -> postgres  (db, documented)  ·  webhook/src/main/java/com/yas/webhook/repository/WebhookRepository.java:8 JpaRepository（Helm：k8s/charts/backend/values.yaml:41 jdbc:postgresql://postgresql.postgres:5432）
