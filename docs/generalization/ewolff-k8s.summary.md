## stacks
- java/spring (FRAMEWORK, from pom.xml)

## ledger sections
- http-server = 20
- jpa = 5
- config = 5
- name-ref = 4
- url = 8
- k8s-workload = 4
- k8s-service = 4
- service-root = 4
- module-deps = 3
- TOTAL = 57 | files with syntax errors = 0

## graph
- after merge: 4 nodes / 5 edges | after normalize: 4 nodes / 5 edges | unresolved code edges = 0
- persistence services: [catalog, order, customer]

## nodes (kind, layer)
- apache  [service, L0]
- catalog  [service, L2]
- customer  [service, L2]
- order  [service, L1]

## edges (type, confidence, provenance ref)
- apache -> catalog  (sync-http, documented)  code: microservice-kubernetes-demo/apache/000-default.conf:17
- apache -> customer  (sync-http, documented)  code: microservice-kubernetes-demo/apache/000-default.conf:20
- apache -> order  (sync-http, documented)  code: microservice-kubernetes-demo/apache/000-default.conf:14
- order -> catalog  (sync-http, documented)  code: microservice-kubernetes-demo/microservice-kubernetes-demo-order/src/main/java/com/ewolff/microservice/order/clients/CatalogClient.java:36
- order -> customer  (sync-http, documented)  code: microservice-kubernetes-demo/microservice-kubernetes-demo-order/src/main/java/com/ewolff/microservice/order/clients/CustomerClient.java:38

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
    apache["apache"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    order["order"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    catalog["catalog"]
    customer["customer"]
  end
  order -.-> catalog
  order -.-> customer
  apache -.-> order
  apache -.-> catalog
  apache -.-> customer

```
