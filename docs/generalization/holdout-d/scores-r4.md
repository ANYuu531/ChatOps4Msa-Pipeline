# Hold-out set D：靜態 greenfield 圖 vs 參考邊集（程式計分，r4）

由 `docs/generalization/holdout-d/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r4.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| skywalking-showcase | 18 | 8 | 10 | 0 | 0.44 | 1.00 | 0.23 / 1.00 | 1.00 / 1.00 | — / 1.00 | — | 109 |
| travels | 20 | 19 | 1 | 0 | 0.95 | 1.00 | 0.92 / 1.00 | 1.00 / 1.00 | — / 1.00 | — | 0 |
| **合計** | 38 | 27 | 11 | 0 | 0.71 | 1.00 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## skywalking-showcase

參考邊集：business 3、data 1、control 4（可計分 8）＋ variant 249。


錯（工具畫了、truth 沒有，10 條）：

- banyandb -> banyandb-plugin-carrier  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.banyandb-tail-sampling.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.banyandb-tail-sampling.yaml:65–66 depends_on；plugin-carrier 只是把 sampler .so 放進共用 volume（:36–42 註解寫明），沒有網路連線。banyandb 的 env 沒有任何位址，規則 35 把 depends_on 畫成 inferred
- es-mock -> es-exporter  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:87–88 depends_on（等 exporter 健康）；es-mock 跑的 scripts/es-e2e-data-mock.sh:17 只打 elasticsearch:9200。規則 35：來源 env 沒位址就照畫
- es-mock -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml#depends_on  ·  **規則邊界**：同上（:89–90 depends_on oap）
- executejob -> jobmanager  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.flink-monitor.yaml  ·  **規則邊界**：deploy/platform/docker/docker-compose.flink-monitor.yaml:64 服務名是 camelCase 的 executeJob，:71 command 的 ./bin/flink run -m jobmanager:8081 由規則 53 讀到、目標正確；參考邊集以 k8s 的名字 execute-flink-job 列 variant，名字對不上
- fluent-bit -> nginx  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.nginx-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.nginx-monitor.yaml:54–56 depends_on；fluent-bit 用共用 volume 讀 nginx 的日誌檔（:50–51），對 nginx 沒有網路連線；它真正的網路目標 oap 在 config/nginx/fluent-bit.conf:45，有畫到（variant）
- loadgen -> app  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.agent.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.agent.yaml:99–101 depends_on app（等 app 健康）；loadgen 真正打的是 http://frontend/index.html（services/load-gen/loadgen.py:27），再由 APISIX 轉給 app。規則 35
- produce-perf -> consume-perf  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.pulsar-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.pulsar-monitor.yaml:108–109 depends_on consume-perf: service_started（先有消費者再生產）；兩個 perf 容器只連 broker
- songs -> gateway  (sync-http, documented)  code: services/songs-service/src/main/java/org/apache/skywalking/showcase/services/song/SongServiceApplication.java:38  ·  **truth 存疑**：同 r3 的 songs-service -> gateway：SongServiceApplication.java:36–39 宣告了 rootUri("http://gateway") 的 RestTemplate bean，但沒有任何類別注入或使用它；r4 起來源改用 compose 名 songs（規則 44 的 image 名）
- vm1 -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.vm-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.vm-monitor.yaml:35–37 depends_on oap（等 oap 健康）；vm1 是 node-exporter，只被 otel-collector 抓（config/otel-collector-config.yaml:24），自己不連任何人
- vm2 -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.vm-monitor.yaml#depends_on  ·  **規則邊界**：同上

工具另外畫到的 variant 邊（不計分，109 條）：

- amqexporter -> activemq  (async, inferred)  ·  deploy/platform/docker/docker-compose.activemq-monitor.yaml:19 jmx-exporter（config/activemq/config.yaml 的 hostPort；depends_on activemq；activemq-monitor feature）
- apisix-otel-collector -> frontend  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.apisix-monitor.yaml:32 OTEL_TARGET_ADDREESS: frontend:9091（apisix-monitor feature；抓 APISIX 的 Prometheus 指標）
- app -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.agent.yaml:86（遙測）
- basic-get -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:50 URI amqp://guest:guest@rmq0:5672/%2f（rabbitmq-monitor feature 的 perf-test 客戶端）
- basic-get-auto -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:64（rabbitmq-monitor feature）
- bookie -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:41 zkServers=zookeeper:2181（pulsar-monitor feature）
- broker -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:68 metadataStoreUrl=zk:zookeeper:2181（:69、:86；pulsar-monitor feature）
- consume-perf -> broker  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:93 consume-perf 依 depends_on broker 連 broker（pulsar-perf 預設 pulsar://localhost… 由 compose 的 broker 服務提供；pulsar-monitor feature）
- es-exporter -> elasticsearch  (db, documented)  ·  deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:54 --es.uri=http://elasticsearch:9200（elasticsearch-monitor feature）
- es-mock -> elasticsearch  (db, inferred)  ·  deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:81 es-mock 跑 scripts/es-e2e-data-mock.sh 打 elasticsearch（elasticsearch-monitor feature）
- event-reporter -> oap  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.event.yaml:31 swctl --grpc-addr=oap:11800 event report（event feature）
- fluent-bit -> oap  (sync-http, inferred)  ·  deploy/platform/docker/config/nginx/fluent-bit.conf:45 Host oap（:46 Port 12800；nginx 日誌送 OAP；nginx-monitor feature）
- frontend -> oap  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.agent.yaml:106 ＋ deploy/platform/docker/config/apisix/apisix.yaml:50 upstreams[1].nodes "oap:12800"（/browser/*、/v3/* 代理給瀏覽器端 agent）＋ :30 skywalking plugin endpoint_addr http://oap:12800（遙測）
- gateway -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.agent.yaml:28 SW_AGENT_COLLECTOR_BACKEND_SERVICES: ${BACKEND_SERVICE}:11800（compose 變數；遙測）
- greedy-consumer -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:78（rabbitmq-monitor feature）
- mongodb-exporter-1 -> mongodb-1  (db, documented)  ·  deploy/platform/docker/docker-compose.mongodb-monitor.yaml:57 --mongodb.uri=mongodb://mongodb-1:27017（mongodb-monitor feature）
- mongodb-exporter-2 -> mongodb-2  (db, documented)  ·  deploy/platform/docker/docker-compose.mongodb-monitor.yaml:73 --mongodb.uri=mongodb://mongodb-2:27017（mongodb-monitor feature）
- mongodb-repl-setup -> mongodb-1  (db, inferred)  ·  deploy/platform/docker/config/mongodb/setup.sh:18 mongosh --host mongodb-1:27017（deploy/platform/docker/docker-compose.mongodb-monitor.yaml:35；mongodb-monitor feature）
- mongodb-repl-setup -> mongodb-2  (db, inferred)  ·  deploy/platform/docker/config/mongodb/setup.sh:22 mongosh --host mongodb-2:27017（mongodb-monitor feature）
- mysql-load -> mysql  (db, inferred)  ·  deploy/platform/docker/docker-compose.mysql-monitor.yaml:44 mysql -h mysql … < /docker/mock.sql（mysql-monitor feature；k8s deploy/platform/kubernetes/templates/feature-mysql-monitor/mysql.yaml:190 mysql-load-deployment 同）
- mysql-service -> mysql  (db, inferred)  ·  deploy/platform/docker/docker-compose.mysql-monitor.yaml:51 DATA_SOURCE_NAME=root:password@(mysql:3306)/（mysqld-exporter；mysql-monitor feature）
- nack -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:127（rabbitmq-monitor feature）
- nginx -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.nginx-monitor.yaml:19 nginx 用 config/nginx/Dockerfile.nginx 內建 skywalking 模組（nginx-monitor feature；遙測）
- nginx-caller -> nginx  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.nginx-monitor.yaml:46 curl http://nginx:8080/test（nginx-monitor feature）
- oap -> banyandb  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.single-node.yaml:51 SW_STORAGE_BANYANDB_TARGETS: banyandb:17912（deploy/platform/docker/docker-compose.cluster.yaml:58；平台儲存）
- oap -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:53（cluster feature 的 oap 是 oap1/oap2 的別名服務）
- oap-init -> banyandb  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:42（cluster feature）
- oap1 -> banyandb  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.cluster.yaml:58（cluster feature；oap1/oap2 共用 &oap 錨點）
- oap1 -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:53 SW_CLUSTER_ZK_HOST_PORT: zookeeper:2181（cluster feature）
- otel-collector -> amqexporter  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.activemq-monitor.yaml:36 OTEL_TARGET_ADDREESS: amqexporter:5556（activemq-monitor feature）
- otel-demo-accounting -> otel-demo-astronomy-db  (db, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:503 DB_CONNECTION_STRING: Host=astronomy-db;…（Npgsql 連線字串；:514 depends_on）—— 2026-10-27 計分後補：標註 otlp-trace 區塊時漏了 accounting 的資料庫（第一組 hold-out 的 accounting → astronomy-db 同一條）
- otel-demo-accounting -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-accounting -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:497 KAFKA_ADDR: kafka:9092（otlp-trace）
- otel-demo-ad -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-ad -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33 x-otel-demo-env FLAGD_HOST: flagd（錨點展開到每個服務；otlp-trace）
- otel-demo-cart -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-cart -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-cart -> otel-demo-valkey-cart  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:193 VALKEY_ADDR: valkey-cart:6379（otlp-trace）
- otel-demo-checkout -> otel-demo-cart  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:218 CART_ADDR: cart:7070（otlp-trace）
- otel-demo-checkout -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-checkout -> otel-demo-currency  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:219（otlp-trace）
- otel-demo-checkout -> otel-demo-email  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:220（otlp-trace）
- otel-demo-checkout -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-checkout -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:224（otlp-trace）
- otel-demo-checkout -> otel-demo-payment  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:221（otlp-trace）
- otel-demo-checkout -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:222（otlp-trace）
- otel-demo-checkout -> otel-demo-shipping  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:223（otlp-trace）
- otel-demo-collector -> oap  (sync-http, inferred)  ·  deploy/platform/docker/config/opentelemetry-demo/otel-collector-config.yaml:38 endpoint oap:11800（otlp-trace feature）
- otel-demo-currency -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-currency -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-email -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-email -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-flagd -> otel-demo-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-fraud-detection -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-fraud-detection -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-fraud-detection -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:525（otlp-trace）
- otel-demo-frontend -> otel-demo-ad  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:578 AD_ADDR: ad:9555（otlp-trace）
- otel-demo-frontend -> otel-demo-cart  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:579（otlp-trace）
- otel-demo-frontend -> otel-demo-checkout  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:580（otlp-trace）
- otel-demo-frontend -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-frontend -> otel-demo-currency  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:581（otlp-trace）
- otel-demo-frontend -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-frontend -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:582（otlp-trace）
- otel-demo-frontend -> otel-demo-recommendation  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:583（otlp-trace）
- otel-demo-frontend -> otel-demo-shipping  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:584（otlp-trace）
- otel-demo-frontend-proxy -> chatbot  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:666（混拼：來源 otel-demo-frontend-proxy、目標 chatbot 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> firepit  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:658（混拼：來源 otel-demo-frontend-proxy、目標 firepit 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> flagd-ui  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:662（混拼：來源 otel-demo-frontend-proxy、目標 flagd-ui 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> grafana  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:642（混拼：來源 otel-demo-frontend-proxy、目標 grafana 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> jaeger  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:644（混拼：來源 otel-demo-frontend-proxy、目標 jaeger 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> opamp-server  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:645（混拼：來源 otel-demo-frontend-proxy、目標 opamp-server 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> otel-demo-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:647 OTEL_COLLECTOR_HOST: otel-collector（otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:660 FLAGD_HOST: flagd（otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-frontend  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:638 FRONTEND_HOST: frontend（Envoy 路由；otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-image-provider  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:648 IMAGE_PROVIDER_HOST: image-provider（otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-load-generator  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:639 LOCUST_WEB_HOST: load-generator（otlp-trace）
- otel-demo-frontend-proxy -> telemetry-docs  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:664（混拼：來源 otel-demo-frontend-proxy、目標 telemetry-docs 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-image-provider -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-kafka -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37 x-otel-demo-grpc OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317（:39 x-otel-demo-http 4318；錨點展開到每個服務；遙測）
- otel-demo-load-generator -> otel-demo-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-load-generator -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-load-generator -> otel-demo-frontend-proxy  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:693 LOCUST_HOST: http://frontend-proxy:8080（otlp-trace）
- otel-demo-payment -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-payment -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-product-catalog -> otel-demo-astronomy-db  (db, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:371 DB_CONNECTION_STRING postgres://…@astronomy-db/astronomy_db（otlp-trace）
- otel-demo-product-catalog -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-product-catalog -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-quote -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-quote -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-recommendation -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-recommendation -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-recommendation -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:432 PRODUCT_CATALOG_ADDR（otlp-trace）
- otel-demo-shipping -> otel-demo-collector  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（遙測）
- otel-demo-shipping -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-shipping -> otel-demo-quote  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:466 QUOTE_ADDR: http://quote:8090（otlp-trace）
- postgres-exporter -> postgres  (db, documented)  ·  deploy/platform/docker/docker-compose.postgresql-monitor.yaml:51 DATA_SOURCE_NAME=postgresql://…@postgres:5432/postgres（postgresql-monitor feature）
- postgres-load -> postgres  (db, inferred)  ·  deploy/platform/docker/docker-compose.postgresql-monitor.yaml:41 psql -f /docker/mock.sql -h postgres（postgresql-monitor feature）
- produce-perf -> broker  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:102（pulsar-monitor feature）
- publisher-confirms -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:92（rabbitmq-monitor feature）
- rating -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.agent.yaml:134 SW_AGENT_REPORTER_GRPC_BACKEND_SERVICE（遙測）
- rcmd -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.agent.yaml:67（遙測）
- rocketmq-broker-1 -> rocketmq-namesrv-1  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.rocketmq-monitor.yaml:43 NAMESRV_ADDR rocketmq-namesrv-1:9876（rocketmq-monitor feature）
- rocketmq-exporter -> rocketmq-namesrv-1  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.rocketmq-monitor.yaml:88 rocketmq.config.namesrvAddr（rocketmq-monitor feature）
- satellite -> oap  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.satellite.yaml:31 SATELLITE_GRPC_CLIENT: oap:11800（satellite feature）
- slow-consumer-persistent -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:106（rabbitmq-monitor feature）
- taskmanager -> jobmanager  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.flink-monitor.yaml:47 jobmanager.rpc.address: jobmanager（flink-monitor feature）
- ui -> oap  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.single-node.yaml:84 HORIZON_OAP_QUERY_URL: http://oap:12800（:85–88 admin／zipkin／traceql；deploy/platform/docker/docker-compose.cluster.yaml:97 同；平台）
- unroutable-drop -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:155（rabbitmq-monitor feature）
- unroutable-return -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:143（rabbitmq-monitor feature）

## travels

參考邊集：business 11、data 4、control 4（可計分 19）＋ variant 8。


錯（工具畫了、truth 沒有，1 條）：

- travel-portal -> travels.travel-agency  (sync-http, documented)  code: travel_portal/travel_portal.go:134  ·  **規則邊界**：travel_portal 模組的程式碼（travel_portal.go:411）由三個 Deployment（voyages、viaggi、travels，travel_portal.yaml:39/113/187 同一個映像 quay.io/kiali/demo_travels_portal）以三個名字執行；映像名 demo_travels_portal 與模組目錄 travel_portal 只在 build.sh:24–31 對上，不是完全相等，規則 44 不猜，所以模組名留著。邊本身是真的（三個 Deployment → 代理商 travels 都由 k8s env 另外畫到）

對的裡面有 4 條標 inferred（名字推的，不是表查到的）。

