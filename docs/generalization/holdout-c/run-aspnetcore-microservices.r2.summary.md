## stacks
- csharp (LLM, from 146 .cs files)   <-- skipped (LLM tier)
- javascript (LLM, from 2 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- compose-service = 22
- compose-dependency = 11
- env-address = 8
- workload-env = 8
- service-root = 6
- TOTAL = 55 | files with syntax errors = 0

## graph
- after merge: 13 nodes / 12 edges | after normalize: 13 nodes / 12 edges | unresolved code edges = 0
- persistence services: []

## nodes (kind, layer)
- basket  [service, L2]
- basketdb  [service, L3]
- catalog  [service, L2]
- catalogdb  [service, L3]
- discount  [service, L3]
- discount.grpc  [external, L4]
- distributedcache  [service, L3]
- ecommerce-mq  [service, L3]
- messagebroker  [service, L5]
- orderdb  [service, L3]
- ordering  [service, L2]
- shopping  [service, L0]
- yarpapigateway  [service, L1]

## edges (type, confidence, provenance ref)
- basket -> basketdb  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- basket -> discount  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- basket -> discount.grpc  (external, inferred)  code: src/docker-compose.override.yml
- basket -> distributedcache  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- basket -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml
- catalog -> catalogdb  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- ordering -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml
- ordering -> orderdb  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- shopping -> yarpapigateway  (sync-http, documented)  code: src/docker-compose.override.yml#depends_on
- yarpapigateway -> basket  (sync-http, inferred)  code: src/docker-compose.override.yml#depends_on
- yarpapigateway -> catalog  (sync-http, inferred)  code: src/docker-compose.override.yml#depends_on
- yarpapigateway -> ordering  (sync-http, inferred)  code: src/docker-compose.override.yml#depends_on

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
    shopping["shopping"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    yarpapigateway["yarpapigateway"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    catalog["catalog"]
    basket["basket"]
    ordering["ordering"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    catalogdb["catalogdb"]
    basketdb["basketdb"]
    distributedcache["distributedcache"]
    orderdb["orderdb"]
    discount["discount"]
    ecommerce_mq["ecommerce-mq"]
  end
  subgraph layer4 ["external"]
    direction LR
    discount_grpc[/"discount.grpc"/]:::external
  end
  subgraph layer5 ["no dependencies found"]
    direction LR
    messagebroker["messagebroker"]
  end
  catalog -.-> catalogdb
  basket -.-> basketdb
  basket -.-> distributedcache
  basket -.-> discount
  ordering -.-> orderdb
  yarpapigateway -. declared? .-> catalog
  yarpapigateway -. declared? .-> basket
  yarpapigateway -. declared? .-> ordering
  shopping -.-> yarpapigateway
  basket -. ext? .-> discount_grpc
  basket -. declared? .-> ecommerce_mq
  ordering -. declared? .-> ecommerce_mq
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
