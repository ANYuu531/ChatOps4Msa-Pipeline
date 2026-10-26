## stacks
- java/spring (FRAMEWORK, from pom.xml)
- php (LLM, from 11 .php files)   <-- skipped (LLM tier)
- javascript (LLM, from 7 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- python/web (FRAMEWORK, from requirements.txt)
- go (LLM, from 1 .go files)   <-- skipped (LLM tier)

## ledger sections
- http-server = 12
- url = 12
- jpa = 4
- config = 8
- import = 28
- http-client = 1
- env-address = 11
- compose-service = 18
- compose-dependency = 16
- workload-env = 2
- k8s-namespace = 1
- k8s-workload = 3
- k8s-service = 11
- service-root = 13
- module-deps = 3
- TOTAL = 143 | files with syntax errors = 0

## graph
- after merge: 15 nodes / 19 edges | after normalize: 15 nodes / 19 edges | unresolved code edges = 0
- persistence services: [shipping]

## nodes (kind, layer)
- cart  [service, L3]
- catalogue  [service, L2]
- dispatch  [service, L0]
- fluentd  [service, L6]
- load  [service, L0]
- mongodb  [db, L4]
- mysql  [db, L4]
- payment  [service, L2]
- paypal.com  [external, L5]
- rabbitmq  [queue, L4]
- ratings  [service, L2]
- redis  [db, L4]
- shipping  [service, L2]
- user  [service, L3]
- web  [service, L1]

## edges (type, confidence, provenance ref)
- cart -> redis  (db, inferred)  code: docker-compose.yaml#depends_on
- catalogue -> mongodb  (db, inferred)  code: docker-compose.yaml#depends_on
- dispatch -> rabbitmq  (async, inferred)  code: docker-compose.yaml#depends_on
- load -> web  (sync-http, documented)  code: docker-compose-load.yaml#depends_on
- payment -> cart  (sync-http, documented)  code: payment/payment.py:24
- payment -> paypal.com  (external, documented)  code: payment/payment.py:84
- payment -> rabbitmq  (async, inferred)  code: docker-compose.yaml#depends_on
- payment -> user  (sync-http, documented)  code: payment/payment.py:25
- ratings -> mysql  (db, inferred)  code: docker-compose.yaml#depends_on
- shipping -> cart  (sync-http, documented)  code: shipping/src/main/java/com/instana/robotshop/shipping/Controller.java:24
- shipping -> mysql  (db, documented)  code: shipping/src/main/java/com/instana/robotshop/shipping/JpaConfig.java:16
- user -> mongodb  (db, inferred)  code: docker-compose.yaml#depends_on
- user -> redis  (db, inferred)  code: docker-compose.yaml#depends_on
- web -> cart  (sync-http, documented)  code: web/default.conf.template:69
- web -> catalogue  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> payment  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> ratings  (sync-http, documented)  code: web/default.conf.template:81
- web -> shipping  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> user  (sync-http, documented)  code: docker-compose.yaml#depends_on

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
    load["load"]
    dispatch["dispatch"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    web["web"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    shipping["shipping"]
    payment["payment"]
    catalogue["catalogue"]
    ratings["ratings"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    cart["cart"]
    user["user"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    mysql[("mysql")]:::db
    mongodb[("mongodb")]:::db
    redis[("redis")]:::db
    rabbitmq{{"rabbitmq"}}:::queue
  end
  subgraph layer5 ["external"]
    direction LR
    paypal_com[/"paypal.com"/]:::external
  end
  subgraph layer6 ["no dependencies found"]
    direction LR
    fluentd["fluentd"]
  end
  shipping -.-> cart
  shipping -. db .-> mysql
  payment -. ext .-> paypal_com
  payment -.-> cart
  payment -.-> user
  load -.-> web
  catalogue -. db? .-> mongodb
  user -. db? .-> mongodb
  user -. db? .-> redis
  cart -. db? .-> redis
  ratings -. db? .-> mysql
  payment -. async? .-> rabbitmq
  dispatch -. async? .-> rabbitmq
  web -.-> catalogue
  web -.-> user
  web -.-> shipping
  web -.-> payment
  web -.-> cart
  web -.-> ratings
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
