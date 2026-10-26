## stacks
- java/spring (FRAMEWORK, from build.gradle)

## ledger sections
- feign = 1
- http-server = 3
- url = 4
- url-metadata = 2
- compose-service = 11
- compose-dependency = 26
- service-root = 7
- module-deps = 7
- TOTAL = 61 | files with syntax errors = 0

## graph
- after merge: 10 nodes / 28 edges | after normalize: 10 nodes / 28 edges | unresolved code edges = 0
- persistence services: []

## nodes (kind, layer)
- admin-dashboard  [service, L0]
- config-server  [service, L4]
- eureka-server  [service, L5]
- github.com  [external, L7]
- hystrix-dashboard  [service, L4]
- rabbitmq  [queue, L6]
- service-a  [service, L2]
- service-b  [service, L3]
- zipkin  [service, L4]
- zuul  [service, L1]

## edges (type, confidence, provenance ref)
- admin-dashboard -> config-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> hystrix-dashboard  (sync-http, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> rabbitmq  (async, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> service-a  (sync-http, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> service-b  (sync-http, inferred)  code: docker-compose.yml#depends_on
- admin-dashboard -> zuul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- config-server -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- config-server -> rabbitmq  (async, inferred)  code: docker-compose.yml#depends_on
- hystrix-dashboard -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-a -> config-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-a -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-a -> github.com  (external, documented)  code: service-a/src/main/java/net/devh/A1ServiceApplication.java:44
- service-a -> hystrix-dashboard  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-a -> rabbitmq  (async, inferred)  code: docker-compose.yml#depends_on
- service-a -> service-b  (sync-http, documented)  code: service-a/src/main/java/net/devh/feign/ServiceBClient.java:12
- service-a -> zipkin  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-b -> config-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-b -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-b -> github.com  (external, documented)  code: service-b/src/main/java/net/devh/B1ServiceApplication.java:41
- service-b -> hystrix-dashboard  (sync-http, inferred)  code: docker-compose.yml#depends_on
- service-b -> rabbitmq  (async, inferred)  code: docker-compose.yml#depends_on
- service-b -> zipkin  (sync-http, inferred)  code: docker-compose.yml#depends_on
- zuul -> config-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- zuul -> eureka-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- zuul -> rabbitmq  (async, inferred)  code: docker-compose.yml#depends_on
- zuul -> service-a  (sync-http, inferred)  code: docker-compose.yml#depends_on
- zuul -> zipkin  (sync-http, inferred)  code: docker-compose.yml#depends_on

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
    admin_dashboard["admin-dashboard"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    zuul["zuul"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    service_a["service-a"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    service_b["service-b"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    zipkin["zipkin"]
    config_server["config-server"]
    hystrix_dashboard["hystrix-dashboard"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    eureka_server["eureka-server"]
  end
  subgraph layer6 ["data stores"]
    direction LR
    rabbitmq{{"rabbitmq"}}:::queue
  end
  subgraph layer7 ["external"]
    direction LR
    github_com[/"github.com"/]:::external
  end
  service_a -.-> service_b
  service_a -. ext .-> github_com
  service_b -. ext .-> github_com
  config_server -. declared? .-> eureka_server
  config_server -. async? .-> rabbitmq
  service_b -. declared? .-> eureka_server
  service_b -. declared? .-> config_server
  service_b -. declared? .-> hystrix_dashboard
  service_b -. async? .-> rabbitmq
  service_b -. declared? .-> zipkin
  service_a -. declared? .-> eureka_server
  service_a -. declared? .-> config_server
  service_a -. declared? .-> hystrix_dashboard
  service_a -. async? .-> rabbitmq
  service_a -. declared? .-> zipkin
  admin_dashboard -. declared? .-> eureka_server
  admin_dashboard -. async? .-> rabbitmq
  admin_dashboard -. declared? .-> config_server
  admin_dashboard -. declared? .-> service_a
  admin_dashboard -. declared? .-> service_b
  admin_dashboard -. declared? .-> zuul
  admin_dashboard -. declared? .-> hystrix_dashboard
  zuul -. declared? .-> eureka_server
  zuul -. declared? .-> config_server
  zuul -. declared? .-> service_a
  zuul -. async? .-> rabbitmq
  zuul -. declared? .-> zipkin
  hystrix_dashboard -. declared? .-> eureka_server
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
