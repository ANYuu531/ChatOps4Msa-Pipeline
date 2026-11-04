## stacks
- go (GENERIC, from 533 .go files, go.mod; tree-sitter grammar)
- javascript (GENERIC, from 1 .js/.mjs/.cjs/.jsx files, package.json; tree-sitter grammar)

## ledger sections
- http-server = 10
- persistence = 8
- config = 4
- url = 62
- compose-service = 23
- compose-mount = 11
- compose-dependency = 7
- compose-port = 38
- service-root = 4
- module-deps = 4
- TOTAL = 171 | files with syntax errors = 0

## graph
- after merge: 16 nodes / 29 edges | after normalize: 16 nodes / 29 edges | unresolved code edges = 0
- persistence services: [catalogwriteservice, pkg]

## nodes (kind, layer)
- cadvisor  [service, L3]
- catalogreadservice  [service, L0]
- catalogwriteservice  [service, L0]
- eventstore  [service, L1]
- grafana  [service, L2]
- jaeger  [service, L3]
- mongo  [db, L4]
- orderservice  [service, L0]
- otelcol  [service, L1]
- pkg  [service, L5]
- postgres  [db, L4]
- prometheus  [service, L2]
- rabbitmq  [queue, L4]
- redis  [db, L4]
- tempo  [service, L2]
- zipkin  [service, L1]

## edges (type, confidence, provenance ref)
- catalogreadservice -> jaeger  (sync-http, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> mongo  (db, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> otelcol  (sync-http, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> rabbitmq  (async, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> redis  (db, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> tempo  (sync-http, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogreadservice -> zipkin  (sync-http, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogwriteservice -> jaeger  (sync-http, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- catalogwriteservice -> otelcol  (sync-http, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- catalogwriteservice -> postgres  (db, documented)  code: internal/services/catalogwriteservice/config/config.development.json
- catalogwriteservice -> rabbitmq  (async, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- catalogwriteservice -> tempo  (sync-http, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- catalogwriteservice -> zipkin  (sync-http, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- grafana -> jaeger  (sync-http, documented)  code: deployments/docker-compose/monitoring/grafana.yaml
- grafana -> prometheus  (sync-http, documented)  code: deployments/docker-compose/docker-compose.infrastructure-collector.yaml#depends_on
- grafana -> tempo  (sync-http, documented)  code: deployments/docker-compose/docker-compose.infrastructure-collector.yaml#depends_on
- orderservice -> eventstore  (sync-http, inferred)  code: internal/services/orderservice/config/config.development.json
- orderservice -> jaeger  (sync-http, inferred)  code: internal/services/orderservice/config/config.development.json
- orderservice -> mongo  (db, inferred)  code: internal/services/orderservice/config/config.development.json
- orderservice -> otelcol  (sync-http, inferred)  code: internal/services/orderservice/config/config.development.json
- orderservice -> rabbitmq  (async, inferred)  code: internal/services/orderservice/config/config.development.json
- orderservice -> zipkin  (sync-http, inferred)  code: internal/services/orderservice/config/config.development.json
- otelcol -> jaeger  (sync-http, inferred)  code: deployments/docker-compose/docker-compose.infrastructure-collector.yaml#depends_on
- otelcol -> prometheus  (sync-http, inferred)  code: deployments/docker-compose/docker-compose.infrastructure-collector.yaml#depends_on
- otelcol -> tempo  (sync-http, inferred)  code: deployments/docker-compose/docker-compose.infrastructure-collector.yaml#depends_on
- prometheus -> cadvisor  (sync-http, documented)  code: deployments/docker-compose/monitoring/prometheus.yml
- prometheus -> grafana  (sync-http, documented)  code: deployments/docker-compose/monitoring/prometheus.yml
- prometheus -> tempo  (sync-http, documented)  code: deployments/docker-compose/monitoring/prometheus.yml
- tempo -> prometheus  (sync-http, documented)  code: deployments/docker-compose/otelcollector/tempo.yaml

## unresolved (source hint / raw target / file:line), first 40

## alias questions (name [origin] seen in | candidates)
- none

## alias answers applied
- none

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    catalogwriteservice["catalogwriteservice"]
    catalogreadservice["catalogreadservice"]
    orderservice["orderservice"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    zipkin["zipkin"]
    otelcol["otelcol"]
    eventstore["eventstore"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    tempo["tempo"]
    prometheus["prometheus"]
    grafana["grafana"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    jaeger["jaeger"]
    cadvisor["cadvisor"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    postgres[("postgres")]:::db
    rabbitmq{{"rabbitmq"}}:::queue
    redis[("redis")]:::db
    mongo[("mongo")]:::db
  end
  subgraph layer5 ["no dependencies found"]
    direction LR
    pkg["pkg"]
  end
  catalogwriteservice -. db .-> postgres
  catalogwriteservice -. async? .-> rabbitmq
  catalogwriteservice -. declared? .-> jaeger
  catalogwriteservice -. declared? .-> tempo
  catalogwriteservice -. declared? .-> zipkin
  catalogwriteservice -. declared? .-> otelcol
  catalogreadservice -. async? .-> rabbitmq
  catalogreadservice -. db? .-> redis
  catalogreadservice -. db? .-> mongo
  catalogreadservice -. declared? .-> jaeger
  catalogreadservice -. declared? .-> tempo
  catalogreadservice -. declared? .-> zipkin
  catalogreadservice -. declared? .-> otelcol
  orderservice -. db? .-> mongo
  orderservice -. async? .-> rabbitmq
  orderservice -. declared? .-> jaeger
  orderservice -. declared? .-> zipkin
  orderservice -. declared? .-> otelcol
  orderservice -. declared? .-> eventstore
  otelcol -. declared? .-> prometheus
  otelcol -. declared? .-> tempo
  otelcol -. declared? .-> jaeger
  grafana -.-> prometheus
  grafana -.-> tempo
  tempo -.-> prometheus
  grafana -.-> jaeger
  prometheus -.-> grafana
  prometheus -.-> cadvisor
  prometheus -.-> tempo
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 0
- rule 2: 0
- rule 3: 0
- rule 4: 0
- rule 5: 1 (not-a-host PROMETHEUS_SERVER_URL=http:${PROMETHEUS_ADDR})
- rule 6: 0
- rule 7: 1 (node prometheus)
- rule 8: 1 (loopback localhost:9090)
- rule 9: 0
- rule 10: 13 (drop devcontainer (.devcontainer); drop rabbitmq (deployments/docker-compose); drop cadvisor (deployments/docker-compose); drop prometheus (deployments/docker-compose); drop otelcol (deployments/docker-compose))
- rule 11: 0
- rule 12: 0
- rule 13: 0
- rule 14: 0
- rule 15: 35 (published 5432; edge catalogwriteservice->postgres :5432; published 5672; edge catalogwriteservice->rabbitmq :5672; published 15672)
- rule 16: 0
- rule 17: 0
- rule 18: 0
- rule 19: 0
- rule 20: 0
- rule 21: 0
- rule 22: 0
- rule 23: 0
- rule 24: 0
- rule 25: 0
- rule 26: 0
- rule 27: 0
- rule 28: 0
- rule 29: 0
- rule 30: 0
- rule 31: 1 (hidden .devcontainer/Dockerfile)
- rule 32: 0
- rule 33: 0
- rule 34: 0
- rule 35: 5 (inferred otelcol->prometheus; inferred otelcol->tempo; inferred otelcol->jaeger; inferred grafana->prometheus; inferred grafana->tempo)
- rule 36: 0
- rule 37: 0
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 0
- rule 42: 21 (ignore /internal/services/catalogwriteservice/internal/shared/data/dbcontext/catalogs_dbcontext_test.go/; ignore /internal/services/orderservice/internal/orders/exceptions/domain_exceptions/order_domain_errors_test.go/; ignore /internal/pkg/postgresmessaging/messagepersistence/postgres_message_service_test.go/; ignore /internal/pkg/postgresgorm/repository/gorm_generic_repository_test.go/; ignore /internal/pkg/postgresgorm/gorm_options_test.go/)
- rule 43: 0
- rule 44: 0
- rule 45: 0
- rule 46: 0
- rule 47: 0
- rule 48: 0
- rule 49: 0
- rule 50: 0
- rule 51: 0
- rule 52: 0
- rule 53: 0
- rule 54: 0
- rule 55: 0
- rule 56: 4 (mount deployments/docker-compose/otelcollector/otelcol-observability.yml=otelcol; mount deployments/docker-compose/otelcollector/tempo.yaml=tempo; mount deployments/docker-compose/monitoring/grafana.yaml=grafana; mount deployments/docker-compose/monitoring/prometheus.yml=prometheus)
- rule 57: 0
- rule 58: 0
- rule 59: 0
- rule 60: 15 (module-file internal/services/catalogwriteservice/config/config.development.json localhost; module-file internal/services/catalogwriteservice/config/config.development.json host.docker.internal; module-file internal/services/catalogreadservice/config/config.development.json localhost; module-file internal/services/catalogreadservice/config/config.development.json host.docker.internal; module-file internal/services/orderservice/config/config.development.json localhost)
- rule 61: 0
