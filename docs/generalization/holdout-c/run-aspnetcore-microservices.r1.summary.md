## stacks
- csharp (LLM, from 146 .cs files)   <-- skipped (LLM tier)
- javascript (LLM, from 2 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- compose-service = 22
- compose-dependency = 11
- env-address = 5
- workload-env = 5
- service-root = 6
- TOTAL = 49 | files with syntax errors = 0

## graph
- after merge: 12 nodes / 13 edges | after normalize: 12 nodes / 13 edges | unresolved code edges = 0
- persistence services: []

## nodes (kind, layer)
- basket  [service, L2]
- basketdb  [service, L3]
- catalog  [service, L2]
- catalogdb  [service, L3]
- discount  [service, L3]
- distributedcache  [service, L3]
- ecommerce-mq  [service, L3]
- messagebroker  [service, L3]
- orderdb  [service, L3]
- ordering  [service, L2]
- shopping  [service, L0]
- yarpapigateway  [service, L1]

## edges (type, confidence, provenance ref)
- basket -> basketdb  (sync-http, documented)  code: src/docker-compose.override.yml
- basket -> discount  (sync-http, documented)  code: src/docker-compose.override.yml
- basket -> distributedcache  (sync-http, documented)  code: src/docker-compose.override.yml
- basket -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml
- basket -> messagebroker  (sync-http, documented)  code: src/docker-compose.override.yml
- catalog -> catalogdb  (sync-http, documented)  code: src/docker-compose.override.yml
- ordering -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml
- ordering -> messagebroker  (sync-http, documented)  code: src/docker-compose.override.yml
- ordering -> orderdb  (sync-http, documented)  code: src/docker-compose.override.yml
- shopping -> yarpapigateway  (sync-http, documented)  code: src/docker-compose.override.yml
- yarpapigateway -> basket  (sync-http, documented)  code: src/docker-compose.override.yml
- yarpapigateway -> catalog  (sync-http, documented)  code: src/docker-compose.override.yml
- yarpapigateway -> ordering  (sync-http, documented)  code: src/docker-compose.override.yml

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
    messagebroker["messagebroker"]
    discount["discount"]
    ecommerce_mq["ecommerce-mq"]
  end
  catalog -.-> catalogdb
  basket -.-> basketdb
  basket -.-> distributedcache
  basket -.-> discount
  basket -.-> messagebroker
  ordering -.-> orderdb
  ordering -.-> messagebroker
  yarpapigateway -.-> catalog
  yarpapigateway -.-> basket
  yarpapigateway -.-> ordering
  shopping -.-> yarpapigateway
  basket -. declared? .-> ecommerce_mq
  ordering -. declared? .-> ecommerce_mq

```
