# Hold-out：靜態 greenfield 圖 vs 參考邊集（程式計分，r2）

由 `docs/generalization/holdout/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r2.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hotel-reservation | 22 | 22 | 0 | 9 | 1.00 | 0.71 | — / 0.00 | 1.00 / 1.00 | — / 1.00 | — | 0 |
| mall-swarm | 23 | 23 | 0 | 1 | 1.00 | 0.96 | 1.00 / 1.00 | 1.00 / 0.90 | — / 1.00 | — / 1.00 | 12 |
| opentelemetry-demo | 39 | 38 | 1 | 0 | 0.97 | 1.00 | 0.94 / 1.00 | 1.00 / 1.00 | — / 1.00 | — | 45 |
| **合計** | 84 | 83 | 1 | 10 | 0.99 | 0.89 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## hotel-reservation

參考邊集：business 9、data 12、control 10（可計分 31）＋ variant 10。


對的裡面有 22 條標 inferred（名字推的，不是表查到的）。


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


對的裡面有 1 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，1 條）：

- mall-admin -> minio  ·  mall-admin/src/main/java/com/macro/mall/controller/MinioController.java:47 MinioClient.builder().endpoint(ENDPOINT)（:32 ${minio.endpoint}；config/admin/mall-admin-prod.yaml:25 http://192.168.3.101:9090）  ·  **truth 存疑**：prod 設定 minio.endpoint 是 IP http://192.168.3.101:9090（unresolved 有列），沒有任何 manifest 叫 minio；參考邊集的節點名 minio 本來就對不上任何靜態字串　→ [r2 仍在] 位址是 IP、沒有任何 manifest 叫 minio，靜態層無從對上

工具另外畫到的 variant 邊（不計分，12 條）：

- logstash -> elasticsearch  (db, inferred)  ·  document/docker/docker-compose-env.yml:64 depends_on elasticsearch ＋ :66 links elasticsearch:es（ELK 遙測；2026-10-23 計分後補：標註時只列了服務→logstash，漏了 ELK 內部）
- mall-admin -> logstash  (sync-http, documented)  ·  config/admin/mall-admin-prod.yaml logstash.host: logstash（遙測）
- mall-demo -> logstash  (sync-http, documented)  ·  config/demo/mall-demo-prod.yaml logstash.host: logstash（遙測）
- mall-demo -> mall-admin  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignAdminService.java:13 @FeignClient("mall-admin")
- mall-demo -> mall-portal  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignPortalService.java:12 @FeignClient("mall-portal")
- mall-demo -> mall-search  (sync-http, documented)  ·  mall-demo/src/main/java/com/macro/mall/demo/service/FeignSearchService.java:11 @FeignClient("mall-search")
- mall-demo -> mysql  (db, documented)  ·  config/demo/mall-demo-prod.yaml:3 jdbc:mysql://db:3306/mall
- mall-demo -> nacos-registry  (sync-http, documented)  ·  mall-demo/src/main/resources/application-prod.yml:5
- mall-gateway -> logstash  (sync-http, documented)  ·  config/gateway/mall-gateway-prod.yaml logstash.host: logstash（遙測）
- mall-gateway -> mall-demo  (sync-http, documented)  ·  mall-gateway/src/main/resources/application.yml:45 uri: lb://mall-demo（mall-demo 沒有 manifest）
- mall-portal -> logstash  (sync-http, documented)  ·  config/portal/mall-portal-prod.yaml logstash.host: logstash（遙測）
- mall-search -> logstash  (sync-http, documented)  ·  config/search/mall-search-prod.yaml logstash.host: logstash（遙測）

## opentelemetry-demo

參考邊集：business 15、data 6、control 17（可計分 38）＋ variant 54。


錯（工具畫了、truth 沒有，1 條）：

- frontend -> flagd  (sync-http, documented)  code: compose.yaml#depends_on  ·  **truth 存疑**：frontend 的 feature flag 由瀏覽器經 frontend-proxy /flagservice 連 flagd（src/frontend/pages/_app.tsx:35 註解），frontend 容器本身不連；但 compose.yaml 確實把 FLAGD_HOST 給了 frontend。工具靠的是 depends_on　→ [r2 仍在] depends_on＋compose 把 FLAGD_HOST 給了 frontend，工具畫 documented；truth 存疑未改

對的裡面有 5 條標 inferred（名字推的，不是表查到的）。


工具另外畫到的 variant 邊（不計分，45 條）：

- accounting -> otel-collector  (sync-http, documented)  ·  compose.full.yaml accounting.environment（遙測）
- ad -> otel-collector  (sync-http, documented)  ·  compose.yaml ad.environment OTEL_EXPORTER_OTLP_ENDPOINT=http://${OTEL_COLLECTOR_HOST}:…（遙測）
- agent -> flagd  (sync-http, documented)  ·  src/agent/src/agents/feature_flags.py:16（compose.agent.yaml）
- agent -> frontend  (sync-http, inferred)  ·  src/shared/tools.py:42 http://{BASE_URL}/api/data（src/agent/Dockerfile:19 把 shared/tools.py 複製進 agent，與 mcp 同一支共用檔；compose.agent.yaml 給 agent APPLICATION_ENDPOINT）—— 2026-10-26 計分後補：第二輪工具從 agent 自己 env 的 APPLICATION_ENDPOINT 畫到，標註時只記了 mcp 那份複本
- agent -> local-llm.com  (external, inferred)  ·  .env:202 LLM_BASE_URL=https://local-llm.com（compose.agent.yaml:39 轉發給 agent；OpenAI 相容端點的預設佔位網域，部署者會覆蓋）—— 2026-10-26 計分後補：agent 覆蓋層才有的外部端點，工具畫成 external／inferred
- agent -> mcp  (sync-http, documented)  ·  src/agent/src/agents/agents.py:45 http://{MCP_ENDPOINT}:{MCP_PORT}/mcp（compose.agent.yaml）
- agent -> otel-collector  (sync-http, documented)  ·  compose.agent.yaml TRACELOOP_BASE_URL（遙測）
- cart -> opamp-server  (sync-http, documented)  ·  compose.observability.yaml:207 OPAMP_SERVER_ENDPOINT（src/cart/src/Program.cs:103）
- cart -> otel-collector  (sync-http, documented)  ·  compose.yaml cart.environment OTEL_EXPORTER_OTLP_ENDPOINT（.env:48，遙測）
- chatbot -> agent  (sync-http, documented)  ·  src/chatbot/src/chat_interface/chat_interface.py:151 http://{AGENT_ENDPOINT}:{AGENT_PORT}/prompt（compose.agent.yaml）
- chatbot -> opamp-server  (sync-http, documented)  ·  src/chatbot/src/opamp.py:136 OPAMP_SERVER_ENDPOINT（compose.agent.yaml）
- chatbot -> otel-collector  (sync-http, inferred)  ·  compose.agent.yaml chatbot.environment（遙測）
- checkout -> otel-collector  (sync-http, documented)  ·  compose.yaml checkout.environment（遙測）
- currency -> otel-collector  (sync-http, documented)  ·  compose.yaml currency.environment（遙測）
- email -> otel-collector  (sync-http, documented)  ·  compose.yaml email.environment（遙測）
- flagd -> otel-collector  (sync-http, inferred)  ·  compose.yaml flagd.environment（遙測）
- flagd-ui -> otel-collector  (sync-http, documented)  ·  compose.yaml flagd-ui.environment（遙測）
- fraud-detection -> otel-collector  (sync-http, documented)  ·  compose.full.yaml fraud-detection.environment（遙測）
- frontend -> otel-collector  (sync-http, documented)  ·  compose.yaml frontend.environment（遙測）
- frontend-proxy -> chatbot  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:90 /chatbot/（compose.agent.yaml）
- frontend-proxy -> firepit  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:85 /profiles/（compose.profiling.yaml）
- frontend-proxy -> grafana  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:56（compose.observability.yaml）
- frontend-proxy -> jaeger  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:52（compose.observability.yaml）
- frontend-proxy -> opamp-server  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:60（compose.observability.yaml）
- frontend-proxy -> otel-collector  (sync-http, documented)  ·  src/frontend-proxy/envoy.tmpl.yaml:46 /otlp-http/ → opentelemetry_collector_http；:25 tracer grpc（遙測）
- image-provider -> otel-collector  (sync-http, documented)  ·  compose.yaml image-provider.environment OTEL_COLLECTOR_HOST（遙測）
- jaeger -> otel-collector  (sync-http, inferred)  ·  compose.observability.yaml jaeger.environment OTEL_COLLECTOR_HOST（遙測）
- jaeger -> prometheus  (sync-http, inferred)  ·  compose.observability.yaml:17 PROMETHEUS_ADDR（.env:190 prometheus:9090；Jaeger SPM 向 Prometheus 查指標，遙測）—— 2026-10-26 計分後補：observability 覆蓋層標註時漏
- kafka -> otel-collector  (sync-http, inferred)  ·  compose.full.yaml kafka.environment（遙測）
- load-generator -> agent  (sync-http, documented)  ·  src/load-generator/locustfile.py:138 AGENT_ENDPOINT（compose.agent.yaml 才給）
- load-generator -> otel-collector  (sync-http, inferred)  ·  compose.yaml load-generator.environment（遙測）
- mcp -> frontend  (sync-http, documented)  ·  src/shared/tools.py:42 http://{BASE_URL}/api/data（:16 BASE_URL=APPLICATION_ENDPOINT=frontend:8080；Dockerfile 把 tools.py 複製進 mcp；compose.agent.yaml）—— 2026-10-23 計分後補：工具從 depends_on 畫到，標註時漏了 shared/ 這支共用檔
- mcp -> otel-collector  (sync-http, inferred)  ·  compose.agent.yaml TRACELOOP_BASE_URL（遙測）
- otel-collector -> astronomy-db  (db, inferred)  ·  src/otel-collector/otelcol-config.yml:54 postgresql receiver ${POSTGRES_HOST}
- otel-collector -> frontend-proxy  (sync-http, inferred)  ·  src/otel-collector/otelcol-config.yml:28 httpcheck
- otel-collector -> image-provider  (sync-http, inferred)  ·  src/otel-collector/otelcol-config.yml:30 httpcheck
- otel-collector -> kafka  (async, documented)  ·  src/otel-collector/otelcol-config-full.yml:17 kafkametrics receiver
- otel-ebpf-profiler -> otel-collector  (sync-http, documented)  ·  compose.profiling.yaml otel-ebpf-profiler（遙測；2026-10-23 計分後補：profiling 覆蓋層標註時漏）
- payment -> otel-collector  (sync-http, documented)  ·  compose.yaml payment.environment（遙測）
- product-catalog -> opamp-server  (sync-http, documented)  ·  compose.observability.yaml:215 OPAMP_SERVER_ENDPOINT
- product-catalog -> otel-collector  (sync-http, documented)  ·  compose.yaml product-catalog.environment（遙測）
- quote -> otel-collector  (sync-http, documented)  ·  compose.yaml quote.environment（遙測）
- recommendation -> otel-collector  (sync-http, documented)  ·  compose.yaml recommendation.environment（遙測）
- shipping -> otel-collector  (sync-http, documented)  ·  compose.yaml shipping.environment（遙測）
- telemetry-docs -> otel-collector  (sync-http, inferred)  ·  compose.yaml telemetry-docs.environment OTEL_COLLECTOR_HOST（遙測）
