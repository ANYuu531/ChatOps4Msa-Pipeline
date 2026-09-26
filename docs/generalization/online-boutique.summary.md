## stacks
- go (LLM, from 29 .go files)   <-- skipped (LLM tier)
- python/web (FRAMEWORK, from requirements.txt)
- csharp (LLM, from 8 .cs files)   <-- skipped (LLM tier)
- javascript (LLM, from 6 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- java (GENERIC, from build.gradle)

## ledger sections
- http-server = 1
- config = 18
- k8s-workload = 39
- env-address = 46
- workload-env = 51
- service-root = 12
- TOTAL = 167 | files with syntax errors = 0

## graph
- after merge: 13 nodes / 17 edges | after normalize: 13 nodes / 17 edges | unresolved code edges = 0
- persistence services: []

## nodes (kind, layer)
- adservice  [service, L2]
- cartservice  [service, L3]
- checkoutservice  [service, L2]
- currencyservice  [service, L3]
- emailservice  [service, L3]
- frontend  [service, L1]
- loadgenerator  [service, L0]
- paymentservice  [service, L3]
- productcatalogservice  [service, L3]
- recommendationservice  [service, L2]
- redis-cart  [db, L4]
- shippingservice  [service, L3]
- shoppingassistantservice  [service, L2]

## edges (type, confidence, evidence)
- cartservice -> redis-cart  (db, inferred)  code: kubernetes-manifests/cartservice.yaml
- checkoutservice -> cartservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- checkoutservice -> currencyservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- checkoutservice -> emailservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- checkoutservice -> paymentservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- checkoutservice -> productcatalogservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- checkoutservice -> shippingservice  (sync-http, inferred)  code: kubernetes-manifests/checkoutservice.yaml
- frontend -> adservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> cartservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> checkoutservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> currencyservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> productcatalogservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> recommendationservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> shippingservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- frontend -> shoppingassistantservice  (sync-http, inferred)  code: kubernetes-manifests/frontend.yaml
- loadgenerator -> frontend  (sync-http, inferred)  code: kubernetes-manifests/loadgenerator.yaml
- recommendationservice -> productcatalogservice  (sync-http, documented)  code: src/recommendationservice/recommendation_server.py:131

## unresolved (source hint / raw target / file:line), first 40

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    loadgenerator["loadgenerator"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    frontend["frontend"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    checkoutservice["checkoutservice"]
    recommendationservice["recommendationservice"]
    adservice["adservice"]
    shoppingassistantservice["shoppingassistantservice"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    emailservice["emailservice"]
    paymentservice["paymentservice"]
    productcatalogservice["productcatalogservice"]
    cartservice["cartservice"]
    currencyservice["currencyservice"]
    shippingservice["shippingservice"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    redis_cart[("redis-cart")]:::db
  end
  recommendationservice -.-> productcatalogservice
  checkoutservice -. declared? .-> productcatalogservice
  checkoutservice -. declared? .-> shippingservice
  checkoutservice -. declared? .-> paymentservice
  checkoutservice -. declared? .-> emailservice
  checkoutservice -. declared? .-> currencyservice
  checkoutservice -. declared? .-> cartservice
  frontend -. declared? .-> productcatalogservice
  frontend -. declared? .-> currencyservice
  frontend -. declared? .-> cartservice
  frontend -. declared? .-> recommendationservice
  frontend -. declared? .-> shippingservice
  frontend -. declared? .-> checkoutservice
  frontend -. declared? .-> adservice
  frontend -. declared? .-> shoppingassistantservice
  cartservice -. db? .-> redis_cart
  loadgenerator -. declared? .-> frontend
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;

```
