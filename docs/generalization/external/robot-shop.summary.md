## stacks
- java/spring (FRAMEWORK, from pom.xml)
- php (LLM, from 11 .php files)   <-- skipped (LLM tier)
- javascript (LLM, from 7 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- python/web (FRAMEWORK, from requirements.txt)
- go (LLM, from 1 .go files)   <-- skipped (LLM tier)

## ledger sections
- http-server = 10
- url = 11
- jpa = 4
- config = 8
- compose-service = 18
- compose-dependency = 16
- env-address = 2
- workload-env = 2
- k8s-workload = 2
- service-root = 12
- TOTAL = 85 | files with syntax errors = 0

## graph
- after merge: 17 nodes / 18 edges | after normalize: 17 nodes / 18 edges | unresolved code edges = 1
- persistence services: [shipping]

## nodes (kind, layer)
- cart  [service, L3]
- catalogue  [service, L2]
- dispatch  [service, L0]
- html  [service, L6]
- load  [service, L0]
- load-gen  [service, L6]
- mongo  [db, L6]
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

## edges (type, confidence, evidence)
- cart -> redis  (db, documented)  code: docker-compose.yaml
- catalogue -> mongodb  (db, documented)  code: docker-compose.yaml
- dispatch -> rabbitmq  (async, documented)  code: docker-compose.yaml
- load -> web  (sync-http, documented)  code: docker-compose-load.yaml
- payment -> cart  (sync-http, documented)  code: payment/payment.py:123
- payment -> paypal.com  (external, documented)  code: payment/payment.py:28
- payment -> rabbitmq  (async, documented)  code: docker-compose.yaml
- payment -> user  (sync-http, documented)  code: payment/payment.py:71
- ratings -> mysql  (db, documented)  code: docker-compose.yaml
- shipping -> mysql  (db, documented)  code: docker-compose.yaml
- user -> mongodb  (db, documented)  code: docker-compose.yaml
- user -> redis  (db, documented)  code: docker-compose.yaml
- web -> cart  (sync-http, inferred)  code: web/default.conf.template:65
- web -> catalogue  (sync-http, documented)  code: docker-compose.yaml
- web -> payment  (sync-http, documented)  code: docker-compose.yaml
- web -> ratings  (sync-http, inferred)  code: web/default.conf.template:77
- web -> shipping  (sync-http, documented)  code: docker-compose.yaml
- web -> user  (sync-http, documented)  code: docker-compose.yaml

## unresolved (source hint / raw target / file:line), first 40
- shipping  =>  http://%s/shipping/   @ shipping/src/main/java/com/instana/robotshop/shipping/Controller.java:23

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    dispatch["dispatch"]
    load["load"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    web["web"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    payment["payment"]
    ratings["ratings"]
    shipping["shipping"]
    catalogue["catalogue"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    user["user"]
    cart["cart"]
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
    html["html"]
    mongo[("mongo")]:::db
    load_gen["load-gen"]
  end
  payment -. ext .-> paypal_com
  payment -.-> user
  payment -.-> cart
  load -.-> web
  catalogue -. db .-> mongodb
  user -. db .-> mongodb
  user -. db .-> redis
  cart -. db .-> redis
  shipping -. db .-> mysql
  ratings -. db .-> mysql
  payment -. async .-> rabbitmq
  dispatch -. async .-> rabbitmq
  web -.-> catalogue
  web -.-> user
  web -.-> shipping
  web -.-> payment
  web -. declared? .-> cart
  web -. declared? .-> ratings
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
