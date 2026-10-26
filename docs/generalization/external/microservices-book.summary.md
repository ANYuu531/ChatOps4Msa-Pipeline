## stacks
- java/spring (FRAMEWORK, from pom.xml)

## ledger sections
- config = 31
- name-ref = 4
- url = 4
- jpa = 5
- http-server = 20
- compose-service = 6
- compose-dependency = 5
- service-root = 13
- module-deps = 6
- TOTAL = 94 | files with syntax errors = 0

## graph
- after merge: 6 nodes / 7 edges | after normalize: 6 nodes / 7 edges | unresolved code edges = 0
- persistence services: [order, catalog, customer]

## nodes (kind, layer)
- catalog  [service, L1]
- customer  [service, L1]
- eureka  [service, L2]
- order  [service, L0]
- turbine  [service, L0]
- zuul  [service, L0]

## edges (type, confidence, provenance ref)
- catalog -> eureka  (sync-http, documented)  code: docker/docker-compose.yml#depends_on
- customer -> eureka  (sync-http, documented)  code: docker/docker-compose.yml#depends_on
- order -> catalog  (sync-http, documented)  code: microservice-demo/microservice-demo-order/src/main/java/com/ewolff/microservice/order/clients/CatalogClient.java:44
- order -> customer  (sync-http, documented)  code: microservice-demo/microservice-demo-order/src/main/java/com/ewolff/microservice/order/clients/CustomerClient.java:43
- order -> eureka  (sync-http, documented)  code: docker/docker-compose.yml#depends_on
- turbine -> eureka  (sync-http, documented)  code: docker/docker-compose.yml#depends_on
- zuul -> eureka  (sync-http, documented)  code: docker/docker-compose.yml#depends_on

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
    order["order"]
    zuul["zuul"]
    turbine["turbine"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    customer["customer"]
    catalog["catalog"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    eureka["eureka"]
  end
  order -.-> catalog
  order -.-> customer
  customer -.-> eureka
  catalog -.-> eureka
  order -.-> eureka
  zuul -.-> eureka
  turbine -.-> eureka

```
