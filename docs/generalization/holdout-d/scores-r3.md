# Hold-out set D：靜態 greenfield 圖 vs 參考邊集（程式計分，r3）

由 `docs/generalization/holdout-d/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r3.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| skywalking-showcase | 25 | 2 | 23 | 6 | 0.08 | 0.25 | 0.00 / 0.00 | 0.50 / 1.00 | — / 0.25 | — | 104 |
| travels | 16 | 16 | 0 | 3 | 1.00 | 0.84 | 1.00 / 0.91 | 1.00 / 1.00 | — / 0.50 | — | 0 |
| **合計** | 41 | 18 | 23 | 9 | 0.44 | 0.67 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## skywalking-showcase

參考邊集：business 3、data 1、control 4（可計分 8）＋ variant 249。


錯（工具畫了、truth 沒有，23 條）：

- app -> agent  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.agent.yaml  ·  **bug**：deploy/platform/docker/docker-compose.agent.yaml:84 SW_AGENT_NAME_SERVER: agent::app 是 SkyWalking 的服務名（agent::app），不是位址；鍵名以 _SERVER 結尾符合 HOST_ENV_NAME（ConfigExtractor:428–429），hostOf 切到第一個 : 得到 agent。同檔 SW_AGENT_NAME: agent::gateway 因 _NAME 不在 pattern 裡沒有中招。含 :: 的值不該當位址
- banyandb -> banyandb-plugin-carrier  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.banyandb-tail-sampling.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.banyandb-tail-sampling.yaml:65–66 depends_on；plugin-carrier 只是把 sampler .so 放進共用 volume（:36–42 註解寫明），沒有網路連線。banyandb 的 env 沒有任何位址，規則 35 把 depends_on 畫成 inferred
- bookie -> metadata-store  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.pulsar-monitor.yaml  ·  **bug**：deploy/platform/docker/docker-compose.pulsar-monitor.yaml:42 metadataServiceUri=metadata-store:zk:zookeeper:2181 是 Pulsar 的 URI（scheme metadata-store:zk:）；值符合 address-shaped 的 [^\s/]+:\d{2,5}（ConfigExtractor:451–452），hostOf 切到第一個 : 把 scheme metadata-store 當主機
- broker -> zk  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.pulsar-monitor.yaml  ·  **bug**：同上：deploy/platform/docker/docker-compose.pulsar-monitor.yaml:68 metadataStoreUrl=zk:zookeeper:2181，scheme zk 被當主機（真目標 zookeeper 另外由 :69 zookeeperServers 畫到了）
- es-mock -> es-exporter  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:87–88 depends_on（等 exporter 健康）；es-mock 跑的 scripts/es-e2e-data-mock.sh:17 只打 elasticsearch:9200。規則 35：來源 env 沒位址就照畫
- es-mock -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml#depends_on  ·  **規則邊界**：同上（:89–90 depends_on oap）
- executejob -> taskmanager  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.flink-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.flink-monitor.yaml:66–67 depends_on taskmanager（啟動順序）；真目標在 :71 command 的 ./bin/flink run -m jobmanager:8081，工具不讀 command:。另外服務名是 camelCase 的 executeJob（:64），節點被小寫成 executejob
- fluent-bit -> nginx  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.nginx-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.nginx-monitor.yaml:54–56 depends_on；fluent-bit 用共用 volume 讀 nginx 的日誌檔（:50–51），對 nginx 沒有網路連線；它真正的網路目標 oap 在 config/nginx/fluent-bit.conf:45，有畫到（variant）
- gateway-service -> rcmd  (sync-http, documented)  code: services/gateway-service/src/main/resources/application.yaml  ·  **規則邊界**：邊對（參考邊集 gateway -> rcmd，services/gateway-service/src/main/resources/application.yaml:31），但來源用了模組目錄名 gateway-service；compose 的 gateway 服務是 image: ${HUB}/gateway-service:${TAG}（deploy/platform/docker/docker-compose.agent.yaml:23）沒有 build:，Gradle 專案也沒有 artifactId，規則 44 搆不到；alias question「gateway-service ≈ gateway」有提出、probe 沒答
- gateway-service -> songs  (sync-http, documented)  code: services/gateway-service/src/main/resources/application.yaml  ·  **規則邊界**：同上（application.yaml:27）；工具另外從 depends_on 畫到 gateway -> songs（inferred，算對）
- load-gen -> frontend  (sync-http, documented)  code: services/load-gen/loadgen.py:27  ·  **規則邊界**：邊對（loadgen -> frontend，services/load-gen/loadgen.py:27）；模組目錄 load-gen vs compose 服務 loadgen（image: ${HUB}/load-gen:${TAG}，docker-compose.agent.yaml:97），連字號有無不同，規則 36 的「標點換連字號」只認 Basket.API ↔ basket.api 那種，不認去掉連字號
- loadgen -> app  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.agent.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.agent.yaml:99–101 depends_on app（等 app 健康）；loadgen 真正打的是 http://frontend/index.html（services/load-gen/loadgen.py:27），再由 APISIX 轉給 app。規則 35
- mysql-load -> otel-collector  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.mysql-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.mysql-monitor.yaml:35–36 depends_on otel-collector（啟動順序）；真目標 mysql 在 :44 command 的 mysql -h mysql，工具不讀 command:
- nginx-caller -> otel-collector  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.nginx-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.nginx-monitor.yaml:39–40 depends_on；真目標 nginx 在 :46 command 的 curl http://nginx:8080/test
- otel-demo-frontend -> frontend  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.otlp-trace.yaml  ·  **規則邊界**：deploy/platform/docker/docker-compose.otlp-trace.yaml:577 FRONTEND_ADDR: frontend:8080 是 otel-demo-frontend 自己的位址（:573 networks.otel-demo.aliases: [frontend]）；工具不讀 aliases，主機 frontend 對到音樂應用的 compose 服務 frontend（APISIX），自己的位址變成指向別人的邊。與 mall-swarm external_links（規則 25）、aspnetcore hostname:（規則 37）同一家族：compose 還有第三種別名——network aliases
- postgres-load -> otel-collector  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.postgresql-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.postgresql-monitor.yaml:32–33 depends_on；真目標 postgres 在 :41 command 的 psql -h postgres
- produce-perf -> consume-perf  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.pulsar-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.pulsar-monitor.yaml:108–109 depends_on consume-perf: service_started（先有消費者再生產）；兩個 perf 容器只連 broker
- recommendation-service -> rating  (sync-http, documented)  code: services/recommendation-service/src/app.py:48  ·  **規則邊界**：邊對（rcmd -> rating，services/recommendation-service/src/app.py:48，python pattern 抓到、documented）；模組目錄 recommendation-service vs compose 服務 rcmd（image: ${HUB}/recommendation-service:${TAG}，docker-compose.agent.yaml:63）；規則 44 搆不到、alias question 未答
- recommendation-service -> songs  (sync-http, documented)  code: services/recommendation-service/src/app.py:46  ·  **規則邊界**：同上（app.py:46）
- songs-service -> activemq  (async, documented)  code: services/songs-service/src/main/java/org/apache/skywalking/showcase/services/song/mq/SongMessageReceiver.java:38  ·  **規則邊界**：邊對（songs -> activemq，SongMessageReceiver.java:38）；模組目錄 songs-service vs compose 服務 songs（image: ${HUB}/songs-service:${TAG}，docker-compose.agent.yaml:42）；工具另外從 depends_on 畫到 songs -> activemq（documented，算對）
- songs-service -> gateway  (sync-http, documented)  code: services/songs-service/src/main/java/org/apache/skywalking/showcase/services/song/SongServiceApplication.java:38  ·  **truth 存疑**：services/songs-service/src/main/java/org/apache/skywalking/showcase/services/song/SongServiceApplication.java:36–39 宣告了一個 RestTemplate bean，rootUri("http://gateway")，但 songs-service 裡沒有任何類別注入或使用它（grep RestTemplate 只有這個 bean）；是一個沒人用的 bean 寫了主機名。工具從程式碼畫 documented 不算錯，參考邊集因為沒有呼叫點而沒列；另外來源也是模組名 songs-service
- vm1 -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.vm-monitor.yaml#depends_on  ·  **規則邊界**：deploy/platform/docker/docker-compose.vm-monitor.yaml:35–37 depends_on oap（等 oap 健康）；vm1 是 node-exporter，只被 otel-collector 抓（config/otel-collector-config.yaml:24），自己不連任何人
- vm2 -> oap  (sync-http, inferred)  code: deploy/platform/docker/docker-compose.vm-monitor.yaml#depends_on  ·  **規則邊界**：同上

對的裡面有 1 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，6 條）：

- app -> gateway  ·  services/app/server/src/index.js:47 axios.get(`http://${GATEWAY}/songs/top`)（:48 /rcmd；:33 GATEWAY = process.env.GATEWAY || 'gateway'，manifest 沒設 GATEWAY）  ·  **缺文法**：Node.js／Express 沒有 tree-sitter pattern（stacks 顯示 javascript LLM tier skipped）；位址在程式碼預設值 services/app/server/src/index.js:33 GATEWAY = process.env.GATEWAY || 'gateway'，compose／k8s 都沒設 GATEWAY，所以 manifest 層也沒得畫；depends_on 只有 oap
- rcmd -> songs  ·  services/recommendation-service/src/app.py:46 requests.get('http://songs/songs')（寫死在程式碼）  ·  **規則邊界**：畫成 recommendation-service -> songs（模組名未對到 compose 名，見上）
- rcmd -> rating  ·  services/recommendation-service/src/app.py:48 requests.get('http://rating/rating')（寫死在程式碼）  ·  **規則邊界**：畫成 recommendation-service -> rating（同上）
- gateway -> rcmd  ·  services/gateway-service/src/main/resources/application.yaml:31 route recommendation-service uri: http://rcmd（Path=/rcmd/**）  ·  **規則邊界**：畫成 gateway-service -> rcmd（同上）；gateway -> songs 之所以算對，是 depends_on 那條（inferred）救的，不是路由表
- frontend -> app  ·  deploy/platform/docker/config/apisix/apisix.yaml:54 upstreams[2].nodes "app:80"（routes /homepage、/test → service 2 → upstream 2；k8s 的同一份設定在 deploy/platform/kubernetes/templates/feature-agent/resources.yaml:342）  ·  **框架慣例**：APISIX 的靜態路由檔 deploy/platform/docker/config/apisix/apisix.yaml:54 upstreams nodes "app:80"（compose 用 volumes 掛進容器，docker-compose.agent.yaml:108–110）；工具認的反向代理只有 nginx／Apache 的 proxy_pass、Envoy 的 cluster、YARP 的 appsettings，沒有 APISIX。k8s 那份在 ConfigMap 裡（feature-agent/resources.yaml:342）又是 Helm template，連 YAML 都讀不到
- loadgen -> frontend  ·  services/load-gen/loadgen.py:40 driver.get(url)（Selenium 無頭 Chrome；:27 url = os.getenv('URL', 'http://frontend/index.html')，compose／k8s 都沒設 URL）  ·  **規則邊界**：畫成 load-gen -> frontend（load-gen vs loadgen，見上）

工具另外畫到的 variant 邊（不計分，104 條）：

- amqexporter -> activemq  (async, inferred)  ·  deploy/platform/docker/docker-compose.activemq-monitor.yaml:19 jmx-exporter（config/activemq/config.yaml 的 hostPort；depends_on activemq；activemq-monitor feature）
- apisix-otel-collector -> frontend  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.apisix-monitor.yaml:32 OTEL_TARGET_ADDREESS: frontend:9091（apisix-monitor feature；抓 APISIX 的 Prometheus 指標）
- basic-get -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:50 URI amqp://guest:guest@rmq0:5672/%2f（rabbitmq-monitor feature 的 perf-test 客戶端）
- basic-get-auto -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:64（rabbitmq-monitor feature）
- bookie -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:41 zkServers=zookeeper:2181（pulsar-monitor feature）
- broker -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:68 metadataStoreUrl=zk:zookeeper:2181（:69、:86；pulsar-monitor feature）
- consume-perf -> broker  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.pulsar-monitor.yaml:93 consume-perf 依 depends_on broker 連 broker（pulsar-perf 預設 pulsar://localhost… 由 compose 的 broker 服務提供；pulsar-monitor feature）
- es-exporter -> elasticsearch  (db, inferred)  ·  deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:54 --es.uri=http://elasticsearch:9200（elasticsearch-monitor feature）
- es-mock -> elasticsearch  (db, inferred)  ·  deploy/platform/docker/docker-compose.elasticsearch-monitor.yaml:81 es-mock 跑 scripts/es-e2e-data-mock.sh 打 elasticsearch（elasticsearch-monitor feature）
- event-reporter -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.event.yaml:31 swctl --grpc-addr=oap:11800 event report（event feature）
- fluent-bit -> oap  (sync-http, inferred)  ·  deploy/platform/docker/config/nginx/fluent-bit.conf:45 Host oap（:46 Port 12800；nginx 日誌送 OAP；nginx-monitor feature）
- gateway -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.agent.yaml:28 SW_AGENT_COLLECTOR_BACKEND_SERVICES: ${BACKEND_SERVICE}:11800（compose 變數；遙測）
- greedy-consumer -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:78（rabbitmq-monitor feature）
- mongodb-exporter-1 -> mongodb-1  (db, inferred)  ·  deploy/platform/docker/docker-compose.mongodb-monitor.yaml:57 --mongodb.uri=mongodb://mongodb-1:27017（mongodb-monitor feature）
- mongodb-exporter-2 -> mongodb-2  (db, inferred)  ·  deploy/platform/docker/docker-compose.mongodb-monitor.yaml:73 --mongodb.uri=mongodb://mongodb-2:27017（mongodb-monitor feature）
- mongodb-repl-setup -> mongodb-1  (db, inferred)  ·  deploy/platform/docker/config/mongodb/setup.sh:18 mongosh --host mongodb-1:27017（deploy/platform/docker/docker-compose.mongodb-monitor.yaml:35；mongodb-monitor feature）
- mongodb-repl-setup -> mongodb-2  (db, inferred)  ·  deploy/platform/docker/config/mongodb/setup.sh:22 mongosh --host mongodb-2:27017（mongodb-monitor feature）
- mysql-service -> mysql  (db, inferred)  ·  deploy/platform/docker/docker-compose.mysql-monitor.yaml:51 DATA_SOURCE_NAME=root:password@(mysql:3306)/（mysqld-exporter；mysql-monitor feature）
- nack -> rmq0  (async, documented)  ·  deploy/platform/docker/docker-compose.rabbitmq-monitor.yaml:127（rabbitmq-monitor feature）
- nginx -> oap  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.nginx-monitor.yaml:19 nginx 用 config/nginx/Dockerfile.nginx 內建 skywalking 模組（nginx-monitor feature；遙測）
- oap -> banyandb  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.single-node.yaml:51 SW_STORAGE_BANYANDB_TARGETS: banyandb:17912（deploy/platform/docker/docker-compose.cluster.yaml:58；平台儲存）
- oap -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:53（cluster feature 的 oap 是 oap1/oap2 的別名服務）
- oap-init -> banyandb  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:42（cluster feature）
- oap1 -> banyandb  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.cluster.yaml:58（cluster feature；oap1/oap2 共用 &oap 錨點）
- oap1 -> zookeeper  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.cluster.yaml:53 SW_CLUSTER_ZK_HOST_PORT: zookeeper:2181（cluster feature）
- otel-collector -> amqexporter  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.activemq-monitor.yaml:36 OTEL_TARGET_ADDREESS: amqexporter:5556（activemq-monitor feature）
- otel-demo-accounting -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-accounting → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-accounting -> otel-demo-astronomy-db  (db, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:503 DB_CONNECTION_STRING: Host=astronomy-db;…（Npgsql 連線字串；:514 depends_on）—— 2026-10-27 計分後補：標註 otlp-trace 區塊時漏了 accounting 的資料庫（第一組 hold-out 的 accounting → astronomy-db 同一條）
- otel-demo-accounting -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:497 KAFKA_ADDR: kafka:9092（otlp-trace）
- otel-demo-ad -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-ad → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-ad -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33 x-otel-demo-env FLAGD_HOST: flagd（錨點展開到每個服務；otlp-trace）
- otel-demo-cart -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-cart → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-cart -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-cart -> otel-demo-valkey-cart  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:193 VALKEY_ADDR: valkey-cart:6379（otlp-trace）
- otel-demo-checkout -> cart  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:218 CART_ADDR: cart:7070（混拼：目標停在 alias cart）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-checkout -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-checkout → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-checkout -> otel-demo-currency  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:219（otlp-trace）
- otel-demo-checkout -> otel-demo-email  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:220（otlp-trace）
- otel-demo-checkout -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-checkout -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:224（otlp-trace）
- otel-demo-checkout -> otel-demo-payment  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:221（otlp-trace）
- otel-demo-checkout -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:222（otlp-trace）
- otel-demo-checkout -> otel-demo-shipping  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:223（otlp-trace）
- otel-demo-collector -> oap  (sync-http, inferred)  ·  deploy/platform/docker/config/opentelemetry-demo/otel-collector-config.yaml:38 endpoint oap:11800（otlp-trace feature）
- otel-demo-currency -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-currency → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-currency -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-email -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-email → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-email -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-flagd -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-flagd → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-fraud-detection -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-fraud-detection → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-fraud-detection -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-fraud-detection -> otel-demo-kafka  (async, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:525（otlp-trace）
- otel-demo-frontend -> cart  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:579 CART_ADDR: cart:7070（混拼）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-frontend → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend -> otel-demo-ad  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:578 AD_ADDR: ad:9555（otlp-trace）
- otel-demo-frontend -> otel-demo-checkout  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:580（otlp-trace）
- otel-demo-frontend -> otel-demo-currency  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:581（otlp-trace）
- otel-demo-frontend -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-frontend -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:582（otlp-trace）
- otel-demo-frontend -> otel-demo-recommendation  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:583（otlp-trace）
- otel-demo-frontend -> otel-demo-shipping  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:584（otlp-trace）
- otel-demo-frontend-proxy -> chatbot  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:666（混拼：來源 otel-demo-frontend-proxy、目標 chatbot 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> firepit  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:658（混拼：來源 otel-demo-frontend-proxy、目標 firepit 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> flagd-ui  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:662（混拼：來源 otel-demo-frontend-proxy、目標 flagd-ui 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> frontend  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:638 FRONTEND_HOST: frontend（混拼：alias frontend 指 otel-demo-frontend，但工具的 frontend 節點是音樂應用的 APISIX，兩個 network 同名）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> grafana  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:642（混拼：來源 otel-demo-frontend-proxy、目標 grafana 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> jaeger  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:644（混拼：來源 otel-demo-frontend-proxy、目標 jaeger 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> opamp-server  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:645（混拼：來源 otel-demo-frontend-proxy、目標 opamp-server 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-frontend-proxy -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:647（alias 拼法混用；遙測）
- otel-demo-frontend-proxy -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:660 FLAGD_HOST: flagd（otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-image-provider  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:648 IMAGE_PROVIDER_HOST: image-provider（otlp-trace）
- otel-demo-frontend-proxy -> otel-demo-load-generator  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:639 LOCUST_WEB_HOST: load-generator（otlp-trace）
- otel-demo-frontend-proxy -> telemetry-docs  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:664（混拼：來源 otel-demo-frontend-proxy、目標 telemetry-docs 在這份 compose 裡不存在）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-image-provider -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-image-provider → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-kafka -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-kafka → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-load-generator -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-load-generator → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-load-generator -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-load-generator -> otel-demo-frontend-proxy  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:693 LOCUST_HOST: http://frontend-proxy:8080（otlp-trace）
- otel-demo-payment -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-payment → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-payment -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-product-catalog -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-product-catalog → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-product-catalog -> otel-demo-astronomy-db  (db, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:371 DB_CONNECTION_STRING postgres://…@astronomy-db/astronomy_db（otlp-trace）
- otel-demo-product-catalog -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-quote -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-quote → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-quote -> otel-demo-flagd  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-recommendation -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-recommendation → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-recommendation -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-recommendation -> otel-demo-product-catalog  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:432 PRODUCT_CATALOG_ADDR（otlp-trace）
- otel-demo-shipping -> otel-collector  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:37（混拼：otel-demo-shipping → alias otel-collector；遙測）—— 2026-10-27 計分後補：工具把來源解成 compose 名、目標停在 network alias（compose 的 networks.<n>.aliases 沒讀），檔頭已宣告兩種拼法都算 variant，這是沒料到的第三種「混拼」；邊本身與已列的同一條
- otel-demo-shipping -> otel-demo-flagd  (sync-http, documented)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:33（otlp-trace）
- otel-demo-shipping -> otel-demo-quote  (sync-http, inferred)  ·  deploy/platform/docker/docker-compose.otlp-trace.yaml:466 QUOTE_ADDR: http://quote:8090（otlp-trace）
- postgres-exporter -> postgres  (db, documented)  ·  deploy/platform/docker/docker-compose.postgresql-monitor.yaml:51 DATA_SOURCE_NAME=postgresql://…@postgres:5432/postgres（postgresql-monitor feature）
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

參考邊集：business 11、data 4、control 4（可計分 19）＋ variant 7。


對的裡面有 16 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，3 條）：

- travels -> travels  ·  travel_portal/travel_portal.go:411（入口網站 travels.uk → 代理商 travels；travel_portal.yaml:208 value http://travels.travel-agency:8000；兩個 workload 同名，見檔頭）  ·  **規則邊界**：travel_portal.yaml:152 的 Deployment travels（入口網站 travels.uk）與 travel_agency.yaml:549 的 Service travels（代理商）同名，manifest 沒有 metadata.namespace（namespace 由 README 的 kubectl -n 給），工具併成一個節點 travels；travel_portal.yaml:208 TRAVELS_AGENCY_SERVICE=http://travels.travel-agency:8000 經 hostOf 剝 <svc>.<ns>（ConfigExtractor.hostOf:958–960）成 travels，addCodeEdge 遇到 from.equals(to) 直接丟（CodeGraphMerger:1751）。沒有 namespace 就分不開；刻意不猜
- control -> viaggi  ·  travel_control/travel_control.go:82（同上；travel_control.yaml:34 第二段 http://viaggi.travel-portal:8000）  ·  **規則邊界**：travel_control.yaml:34 PORTAL_SERVICES 的值是「voyages.fr;http://voyages.travel-portal:8000,viaggi.it;http://viaggi.travel-portal:8000,travels.uk;http://travels.travel-portal:8000」（程式碼 travel_control/travel_control.go:42–47 自己用逗號與分號拆）；ConfigExtractor.hostOf:936–944 一個值只取一個主機：切到第一個 :// 之後、再切到第一個 : 或 /，得到 voyages.travel-portal → voyages，後面兩個網址丟了。多位址的 env 值沒有拆分規則
- control -> travels  ·  travel_control/travel_control.go:82（同上；travel_control.yaml:34 第三段 http://travels.travel-portal:8000，指的是入口網站 travels.uk）  ·  **規則邊界**：同上（travel_control.yaml:34 第三段 http://travels.travel-portal:8000）；就算拆開，目標 travels 也會撞到上面那條同名問題（這裡指的是入口網站 travels.uk）
