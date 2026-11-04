## stacks
- java/spring (FRAMEWORK, from pom.xml)
- javascript (GENERIC, from 19 .js/.mjs/.cjs/.jsx files; tree-sitter grammar)

## ledger sections
- http-server = 13
- jpa = 12
- http-client = 3
- url = 8
- parsed-dir = 25
- config = 26
- dockerfile-from = 3
- compose-service = 11
- compose-dependency = 13
- compose-port = 11
- service-root = 10
- module-deps = 8
- TOTAL = 143 | files with syntax errors = 0

## graph
- after merge: 12 nodes / 22 edges | after normalize: 12 nodes / 22 edges | unresolved code edges = 1
- persistence services: [vets-service, customers-service, visits-service]

## nodes (kind, layer)
- admin-server  [service, L2]
- api-gateway  [service, L2]
- config-server  [service, L5]
- customers-service  [service, L3]
- discovery-server  [service, L4]
- github.com  [external, L6]
- grafana-server  [service, L0]
- hystrix-dashboard  [service, L0]
- prometheus-server  [service, L1]
- tracing-server  [service, L7]
- vets-service  [service, L2]
- visits-service  [service, L3]

## edges (type, confidence, provenance ref)
- admin-server -> config-server  (sync-http, documented)  code: spring-petclinic-admin-server/src/main/resources/bootstrap.yml
- admin-server -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- api-gateway -> config-server  (sync-http, documented)  code: spring-petclinic-api-gateway/src/main/resources/bootstrap.yml
- api-gateway -> customers-service  (sync-http, documented)  code: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/CustomersServiceClient.java:33
- api-gateway -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- api-gateway -> visits-service  (sync-http, documented)  code: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/VisitsServiceClient.java:46
- config-server -> github.com  (external, documented)  code: spring-petclinic-config-server/src/main/resources/bootstrap.yml
- customers-service -> config-server  (sync-http, documented)  code: spring-petclinic-customers-service/src/main/resources/bootstrap.yml
- customers-service -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- discovery-server -> config-server  (sync-http, documented)  code: spring-petclinic-discovery-server/src/main/resources/bootstrap.yml
- grafana-server -> prometheus-server  (sync-http, documented)  code: docker/grafana/provisioning/datasources/all.yml
- hystrix-dashboard -> config-server  (sync-http, documented)  code: spring-petclinic-hystrix-dashboard/src/main/resources/bootstrap.yml
- hystrix-dashboard -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- prometheus-server -> admin-server  (sync-http, inferred)  code: docker/prometheus/prometheus.yml
- prometheus-server -> api-gateway  (sync-http, documented)  code: docker/prometheus/prometheus.yml
- prometheus-server -> customers-service  (sync-http, documented)  code: docker/prometheus/prometheus.yml
- prometheus-server -> vets-service  (sync-http, documented)  code: docker/prometheus/prometheus.yml
- prometheus-server -> visits-service  (sync-http, documented)  code: docker/prometheus/prometheus.yml
- vets-service -> config-server  (sync-http, documented)  code: spring-petclinic-vets-service/src/main/resources/bootstrap.yml
- vets-service -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on
- visits-service -> config-server  (sync-http, documented)  code: docker-compose.yml#depends_on
- visits-service -> discovery-server  (sync-http, inferred)  code: docker-compose.yml#depends_on

## startup order (Compose depends_on that no address backs; not a call, not scored)
- none

## unresolved (source hint / raw target / file:line), first 40
- config-server  =>  file:///${GIT_REPO}   @ spring-petclinic-config-server/src/main/resources/bootstrap.yml:-1

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
    hystrix_dashboard["hystrix-dashboard"]
    grafana_server["grafana-server"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    prometheus_server["prometheus-server"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    vets_service["vets-service"]
    api_gateway["api-gateway"]
    admin_server["admin-server"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    customers_service["customers-service"]
    visits_service["visits-service"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    discovery_server["discovery-server"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    config_server["config-server"]
  end
  subgraph layer6 ["external"]
    direction LR
    github_com[/"github.com"/]:::external
  end
  subgraph layer7 ["no dependencies found"]
    direction LR
    tracing_server["tracing-server"]
  end
  api_gateway -.-> customers_service
  api_gateway -.-> visits_service
  grafana_server -.-> prometheus_server
  prometheus_server -. declared? .-> admin_server
  prometheus_server -.-> api_gateway
  prometheus_server -.-> customers_service
  prometheus_server -.-> visits_service
  prometheus_server -.-> vets_service
  config_server -. ext .-> github_com
  discovery_server -.-> config_server
  hystrix_dashboard -.-> config_server
  admin_server -.-> config_server
  vets_service -.-> config_server
  api_gateway -.-> config_server
  customers_service -.-> config_server
  customers_service -. declared? .-> discovery_server
  visits_service -.-> config_server
  visits_service -. declared? .-> discovery_server
  vets_service -. declared? .-> discovery_server
  api_gateway -. declared? .-> discovery_server
  admin_server -. declared? .-> discovery_server
  hystrix_dashboard -. declared? .-> discovery_server
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 0
- rule 2: 1 (climb docker)
- rule 3: 7 (config-client discovery-server; config-client hystrix-dashboard; config-client admin-server; config-client vets-service; config-client api-gateway)
- rule 4: 0
- rule 5: 0
- rule 6: 0
- rule 7: 0
- rule 8: 1 (config-signal server.port)
- rule 9: 6 (jpa spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Vet.java; jpa spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Specialty.java; jpa spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Pet.java; jpa spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Owner.java; jpa spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/PetType.java)
- rule 10: 0
- rule 11: 11 (node config-server; node discovery-server; node customers-service; node visits-service; node vets-service)
- rule 12: 0
- rule 13: 0
- rule 14: 0
- rule 15: 9 (published 9090; edge prometheus-server->admin-server :9090; edge discovery-server->config-server :8888; edge hystrix-dashboard->config-server :8888; edge admin-server->config-server :8888)
- rule 16: 5 (edge admin-server->discovery-server; edge vets-service->discovery-server; edge api-gateway->discovery-server; edge customers-service->discovery-server; edge visits-service->discovery-server)
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
- rule 31: 0
- rule 32: 0
- rule 33: 0
- rule 34: 0
- rule 35: 13 (inferred discovery-server->config-server; inferred customers-service->config-server; inferred customers-service->discovery-server; inferred visits-service->config-server; inferred visits-service->discovery-server)
- rule 36: 0
- rule 37: 0
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 0
- rule 42: 0
- rule 43: 0
- rule 44: 18 (map grafana=grafana-server; map prometheus=prometheus-server; image spring-petclinic-config-server=config-server; map spring-petclinic-config-server=config-server; image spring-petclinic-discovery-server=discovery-server)
- rule 45: 0
- rule 46: 0
- rule 47: 0
- rule 48: 0
- rule 49: 0
- rule 50: 0
- rule 51: 11 (browser spring-petclinic-api-gateway/src/main/resources/static/scripts/pet-form/pet-form.js:8; browser spring-petclinic-api-gateway/src/main/resources/static/scripts/pet-form/pet-form.js:13; browser spring-petclinic-api-gateway/src/main/resources/static/scripts/visits/visits.controller.js:11; browser spring-petclinic-api-gateway/src/main/resources/static/scripts/visits/visits.controller.js:21; browser spring-petclinic-api-gateway/src/main/resources/static/scripts/visits/visits.js:8)
- rule 52: 0
- rule 53: 0
- rule 54: 0
- rule 55: 0
- rule 56: 0
- rule 57: 8 (documents spring-petclinic-config-server/src/main/resources/bootstrap.yml 2; documents spring-petclinic-discovery-server/src/main/resources/bootstrap.yml 2; documents spring-petclinic-hystrix-dashboard/src/main/resources/bootstrap.yml 2; documents spring-petclinic-admin-server/src/main/resources/bootstrap.yml 2; documents spring-petclinic-vets-service/src/main/resources/bootstrap.yml 2)
- rule 58: 0
- rule 59: 0
- rule 60: 6 (module-file docker/grafana/provisioning/datasources/all.yml prometheus-server; module-file docker/prometheus/prometheus.yml localhost; module-file docker/prometheus/prometheus.yml api-gateway; module-file docker/prometheus/prometheus.yml customers-service; module-file docker/prometheus/prometheus.yml visits-service)
- rule 61: 0
