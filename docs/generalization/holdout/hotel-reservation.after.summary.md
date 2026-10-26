## stacks
- go (LLM, from 53 .go files)   <-- skipped (LLM tier)
- python (GENERIC, from .py sources)

## ledger sections
- import = 3
- url = 1
- k8s-service = 58
- k8s-workload = 51
- compose-service = 24
- compose-dependency = 22
- service-root = 1
- TOTAL = 160 | files with syntax errors = 0

## graph
- after merge: 24 nodes / 22 edges | after normalize: 24 nodes / 22 edges | unresolved code edges = 1
- persistence services: []

## nodes (kind, layer)
- attractions  [service, L0]
- bingo  [service, L3]
- consul  [service, L1]
- frontend  [service, L0]
- geo  [service, L0]
- memcached-profile  [service, L1]
- memcached-rate  [service, L1]
- memcached-reserve  [service, L1]
- memcached-review  [service, L1]
- mongodb-attractions  [db, L2]
- mongodb-geo  [db, L2]
- mongodb-profile  [db, L2]
- mongodb-rate  [db, L2]
- mongodb-recommendation  [db, L2]
- mongodb-reservation  [db, L2]
- mongodb-review  [db, L2]
- mongodb-user  [db, L2]
- profile  [service, L0]
- rate  [service, L0]
- recommendation  [service, L0]
- reservation  [service, L0]
- review  [service, L0]
- search  [service, L0]
- user  [service, L0]

## edges (type, confidence, provenance ref)
- attractions -> consul  (sync-http, documented)  code: docker-compose.yml
- attractions -> mongodb-attractions  (db, documented)  code: docker-compose.yml
- frontend -> consul  (sync-http, documented)  code: docker-compose.yml
- geo -> consul  (sync-http, documented)  code: docker-compose.yml
- geo -> mongodb-geo  (db, documented)  code: docker-compose.yml
- profile -> consul  (sync-http, documented)  code: docker-compose.yml
- profile -> memcached-profile  (sync-http, documented)  code: docker-compose.yml
- profile -> mongodb-profile  (db, documented)  code: docker-compose.yml
- rate -> consul  (sync-http, documented)  code: docker-compose.yml
- rate -> memcached-rate  (sync-http, documented)  code: docker-compose.yml
- rate -> mongodb-rate  (db, documented)  code: docker-compose.yml
- recommendation -> consul  (sync-http, documented)  code: docker-compose.yml
- recommendation -> mongodb-recommendation  (db, documented)  code: docker-compose.yml
- reservation -> consul  (sync-http, documented)  code: docker-compose.yml
- reservation -> memcached-reserve  (sync-http, documented)  code: docker-compose.yml
- reservation -> mongodb-reservation  (db, documented)  code: docker-compose.yml
- review -> consul  (sync-http, documented)  code: docker-compose.yml
- review -> memcached-review  (sync-http, documented)  code: docker-compose.yml
- review -> mongodb-review  (db, documented)  code: docker-compose.yml
- search -> consul  (sync-http, documented)  code: docker-compose.yml
- user -> consul  (sync-http, documented)  code: docker-compose.yml
- user -> mongodb-user  (db, documented)  code: docker-compose.yml

## unresolved (source hint / raw target / file:line), first 40
- null  =>  http://localhost:5000/review   @ test.py:7

## alias questions (name [origin] seen in | candidates)
- memcached-profile  [graph]  looks like the same service as `profile` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: profile
- memcached-review  [graph]  looks like the same service as `review` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: review

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
    frontend["frontend"]
    profile["profile"]
    search["search"]
    geo["geo"]
    rate["rate"]
    review["review"]
    attractions["attractions"]
    recommendation["recommendation"]
    user["user"]
    reservation["reservation"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    consul["consul"]
    memcached_profile["memcached-profile"]
    memcached_rate["memcached-rate"]
    memcached_review["memcached-review"]
    memcached_reserve["memcached-reserve"]
  end
  subgraph layer2 ["data stores"]
    direction LR
    mongodb_profile[("mongodb-profile")]:::db
    mongodb_geo[("mongodb-geo")]:::db
    mongodb_rate[("mongodb-rate")]:::db
    mongodb_review[("mongodb-review")]:::db
    mongodb_attractions[("mongodb-attractions")]:::db
    mongodb_recommendation[("mongodb-recommendation")]:::db
    mongodb_user[("mongodb-user")]:::db
    mongodb_reservation[("mongodb-reservation")]:::db
  end
  subgraph layer3 ["no dependencies found"]
    direction LR
    bingo["bingo"]
  end
  frontend -.-> consul
  profile -. db .-> mongodb_profile
  profile -.-> memcached_profile
  profile -.-> consul
  search -.-> consul
  geo -. db .-> mongodb_geo
  geo -.-> consul
  rate -. db .-> mongodb_rate
  rate -.-> memcached_rate
  rate -.-> consul
  review -. db .-> mongodb_review
  review -.-> memcached_review
  review -.-> consul
  attractions -. db .-> mongodb_attractions
  attractions -.-> consul
  recommendation -. db .-> mongodb_recommendation
  recommendation -.-> consul
  user -. db .-> mongodb_user
  user -.-> consul
  reservation -. db .-> mongodb_reservation
  reservation -.-> memcached_reserve
  reservation -.-> consul
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;

```
