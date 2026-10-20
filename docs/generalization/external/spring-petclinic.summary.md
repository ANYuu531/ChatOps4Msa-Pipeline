## stacks
- java/spring (FRAMEWORK, from pom.xml)
- javascript (LLM, from 19 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- http-server = 13
- jpa = 12
- http-client = 1
- url = 2
- compose-service = 11
- compose-dependency = 13
- service-root = 10
- TOTAL = 62 | files with syntax errors = 0

## graph
- after merge: 11 nodes / 15 edges | after normalize: 11 nodes / 15 edges | unresolved code edges = 0
- persistence services: [vets-service, customers-service, visits-service]

## nodes (kind, layer)
- admin-server  [service, L0]
- api-gateway  [service, L0]
- config-server  [service, L3]
- customers-service  [service, L1]
- discovery-server  [service, L2]
- grafana-server  [service, L4]
- hystrix-dashboard  [service, L0]
- prometheus-server  [service, L4]
- tracing-server  [service, L4]
- vets-service  [service, L0]
- visits-service  [service, L1]

## edges (type, confidence, provenance ref)
- admin-server -> config-server  (sync-http, documented)  code: docker-compose.yml
- admin-server -> discovery-server  (sync-http, documented)  code: docker-compose.yml
- api-gateway -> config-server  (sync-http, documented)  code: docker-compose.yml
- api-gateway -> customers-service  (sync-http, documented)  code: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/CustomersServiceClient.java:33
- api-gateway -> discovery-server  (sync-http, documented)  code: docker-compose.yml
- api-gateway -> visits-service  (sync-http, documented)  code: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/VisitsServiceClient.java:46
- customers-service -> config-server  (sync-http, documented)  code: docker-compose.yml
- customers-service -> discovery-server  (sync-http, documented)  code: docker-compose.yml
- discovery-server -> config-server  (sync-http, documented)  code: docker-compose.yml
- hystrix-dashboard -> config-server  (sync-http, documented)  code: docker-compose.yml
- hystrix-dashboard -> discovery-server  (sync-http, documented)  code: docker-compose.yml
- vets-service -> config-server  (sync-http, documented)  code: docker-compose.yml
- vets-service -> discovery-server  (sync-http, documented)  code: docker-compose.yml
- visits-service -> config-server  (sync-http, documented)  code: docker-compose.yml
- visits-service -> discovery-server  (sync-http, documented)  code: docker-compose.yml

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
    vets_service["vets-service"]
    api_gateway["api-gateway"]
    admin_server["admin-server"]
    hystrix_dashboard["hystrix-dashboard"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    customers_service["customers-service"]
    visits_service["visits-service"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    discovery_server["discovery-server"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    config_server["config-server"]
  end
  subgraph layer4 ["no dependencies found"]
    direction LR
    tracing_server["tracing-server"]
    grafana_server["grafana-server"]
    prometheus_server["prometheus-server"]
  end
  api_gateway -.-> customers_service
  api_gateway -.-> visits_service
  discovery_server -.-> config_server
  customers_service -.-> config_server
  customers_service -.-> discovery_server
  visits_service -.-> config_server
  visits_service -.-> discovery_server
  vets_service -.-> config_server
  vets_service -.-> discovery_server
  api_gateway -.-> config_server
  api_gateway -.-> discovery_server
  admin_server -.-> config_server
  admin_server -.-> discovery_server
  hystrix_dashboard -.-> config_server
  hystrix_dashboard -.-> discovery_server

```
