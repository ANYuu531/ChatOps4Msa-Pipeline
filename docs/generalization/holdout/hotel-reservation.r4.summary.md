## stacks
- go (GENERIC, from 53 .go files, go.mod; tree-sitter grammar)
- python (GENERIC, from .py sources)

## ledger sections
- persistence = 8
- config = 6
- grpc = 1
- url = 2
- http-server = 9
- registry-ref = 11
- http-client = 3
- import = 3
- k8s-service = 58
- k8s-workload = 51
- k8s-namespace = 1
- compose-service = 24
- compose-alias = 11
- compose-dependency = 22
- TOTAL = 210 | files with syntax errors = 0

## graph
- after merge: 23 nodes / 22 edges | after normalize: 23 nodes / 22 edges | unresolved code edges = 15
- persistence services: []

## nodes (kind, layer)
- attractions  [service, L0]
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
- attractions -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- attractions -> mongodb-attractions  (db, inferred)  code: docker-compose.yml#depends_on
- frontend -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- geo -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- geo -> mongodb-geo  (db, inferred)  code: docker-compose.yml#depends_on
- profile -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- profile -> memcached-profile  (sync-http, inferred)  code: docker-compose.yml#depends_on
- profile -> mongodb-profile  (db, inferred)  code: docker-compose.yml#depends_on
- rate -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- rate -> memcached-rate  (sync-http, inferred)  code: docker-compose.yml#depends_on
- rate -> mongodb-rate  (db, inferred)  code: docker-compose.yml#depends_on
- recommendation -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- recommendation -> mongodb-recommendation  (db, inferred)  code: docker-compose.yml#depends_on
- reservation -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- reservation -> memcached-reserve  (sync-http, inferred)  code: docker-compose.yml#depends_on
- reservation -> mongodb-reservation  (db, inferred)  code: docker-compose.yml#depends_on
- review -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- review -> memcached-review  (sync-http, inferred)  code: docker-compose.yml#depends_on
- review -> mongodb-review  (db, inferred)  code: docker-compose.yml#depends_on
- search -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- user -> consul  (sync-http, inferred)  code: docker-compose.yml#depends_on
- user -> mongodb-user  (db, inferred)  code: docker-compose.yml#depends_on

## unresolved (source hint / raw target / file:line), first 40
- dialer  =>  null   @ dialer/dialer.go:55
- registry  =>  null   @ registry/registry.go:89
- services  =>  null   @ services/reservation/server.go:123
- services  =>  null   @ services/reservation/server.go:158
- services  =>  null   @ services/review/server.go:121
- services  =>  srv-review   @ services/frontend/server.go:84
- services  =>  srv-attractions   @ services/frontend/server.go:88
- services  =>  srv-search   @ services/frontend/server.go:64
- services  =>  srv-profile   @ services/frontend/server.go:68
- services  =>  srv-recommendation   @ services/frontend/server.go:72
- services  =>  srv-user   @ services/frontend/server.go:76
- services  =>  srv-reservation   @ services/frontend/server.go:80
- services  =>  srv-geo   @ services/search/server.go:70
- services  =>  srv-rate   @ services/search/server.go:73
- null  =>  http://localhost:5000/review   @ test.py:7

## alias questions (name [origin] seen in | candidates)
- memcached-profile  [graph]  looks like the same service as `profile` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: profile
- memcached-review  [graph]  looks like the same service as `review` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: review
- dialer  [code]  the module directory of grpc at dialer/dialer.go:55 — no service on the graph has this name   | candidates: -
- registry  [code]  the module directory of url at registry/registry.go:89 — no service on the graph has this name   | candidates: -
- services  [code]  the module directory of http-client at services/reservation/server.go:123 — no service on the graph has this name  (+11 more)   | candidates: -
- srv-review  [code]  registry-ref at services/frontend/server.go:84   | candidates: review, memcached-review
- srv-attractions  [code]  registry-ref at services/frontend/server.go:88   | candidates: attractions
- srv-search  [code]  registry-ref at services/frontend/server.go:64   | candidates: search
- srv-profile  [code]  registry-ref at services/frontend/server.go:68   | candidates: profile, memcached-profile
- srv-recommendation  [code]  registry-ref at services/frontend/server.go:72   | candidates: recommendation
- srv-user  [code]  registry-ref at services/frontend/server.go:76   | candidates: user
- srv-reservation  [code]  registry-ref at services/frontend/server.go:80   | candidates: reservation
- srv-geo  [code]  registry-ref at services/search/server.go:70   | candidates: geo
- srv-rate  [code]  registry-ref at services/search/server.go:73   | candidates: rate

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
  frontend -. declared? .-> consul
  profile -. db? .-> mongodb_profile
  profile -. declared? .-> memcached_profile
  profile -. declared? .-> consul
  search -. declared? .-> consul
  geo -. db? .-> mongodb_geo
  geo -. declared? .-> consul
  rate -. db? .-> mongodb_rate
  rate -. declared? .-> memcached_rate
  rate -. declared? .-> consul
  review -. db? .-> mongodb_review
  review -. declared? .-> memcached_review
  review -. declared? .-> consul
  attractions -. db? .-> mongodb_attractions
  attractions -. declared? .-> consul
  recommendation -. db? .-> mongodb_recommendation
  recommendation -. declared? .-> consul
  user -. db? .-> mongodb_user
  user -. declared? .-> consul
  reservation -. db? .-> mongodb_reservation
  reservation -. declared? .-> memcached_reserve
  reservation -. declared? .-> consul
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;

```

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 0
- rule 2: 0
- rule 3: 0
- rule 4: 0
- rule 5: 0
- rule 6: 0
- rule 7: 13 (node consul; node mongodb-profile; node memcached-profile; node mongodb-geo; node mongodb-rate)
- rule 8: 0
- rule 9: 0
- rule 10: 51 (drop mongodb-profile (knative); drop mongodb-user (knative); drop jaeger (knative); drop mongodb-recommendation (knative); drop memcached-profile (knative))
- rule 11: 1 (k8s-over-compose 24 compose names ignored)
- rule 12: 0
- rule 13: 0
- rule 14: 0
- rule 15: 0
- rule 16: 0
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
- rule 31: 1 (hidden .bingo/go.mod)
- rule 32: 0
- rule 33: 0
- rule 34: 0
- rule 35: 22 (inferred frontend->consul; inferred profile->mongodb-profile; inferred profile->memcached-profile; inferred profile->consul; inferred search->consul)
- rule 36: 0
- rule 37: 11 (hostname user-memcached=memcached-rate; hostname user-memcached=memcached-profile; hostname user-memcached=memcached-reserve; hostname geo-db=mongodb-geo; hostname profile-db=mongodb-profile)
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 0
- rule 42: 0
- rule 43: 0
- rule 44: 0
- rule 45: 0
- rule 46: 0
- rule 49: 9 (listed services => srv-review; listed services => srv-attractions; listed services => srv-search; listed services => srv-profile; listed services => srv-recommendation)
- rule 52: 58 (render helm-chart/hotelreservation/charts/consul/templates/deployment.yaml; render helm-chart/hotelreservation/charts/consul/templates/service.yaml; render helm-chart/hotelreservation/charts/frontend/templates/configmap.yaml; render helm-chart/hotelreservation/charts/frontend/templates/deployment.yaml; render helm-chart/hotelreservation/charts/frontend/templates/service.yaml)
