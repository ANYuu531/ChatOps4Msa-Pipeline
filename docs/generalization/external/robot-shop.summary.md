## stacks
- javascript (GENERIC, from 7 .js/.mjs/.cjs/.jsx files, package.json; tree-sitter grammar)
- java (GENERIC, from pom.xml)
- python/web (FRAMEWORK, from requirements.txt)
- php (GENERIC, from 3 .php files, composer.json; tree-sitter grammar)
- go (GENERIC, from 1 .go files; tree-sitter grammar)

## ledger sections
- config = 50
- http-server = 25
- persistence = 6
- http-client = 3
- url = 11
- import = 27
- registry-ref = 1
- env-address = 10
- compose-service = 17
- compose-dependency = 15
- k8s-gateway = 1
- ingress-route = 1
- k8s-workload = 13
- workload-env = 1
- k8s-service = 12
- service-root = 11
- module-deps = 6
- TOTAL = 210 | files with syntax errors = 0

## graph
- after merge: 14 nodes / 21 edges | after normalize: 14 nodes / 21 edges | unresolved code edges = 1
- persistence services: [user, cart, catalogue, ratings, dispatch]

## nodes (kind, layer)
- cart  [service, L3]
- catalogue  [service, L4]
- dispatch  [service, L0]
- istio-ingressgateway  [gateway, L0]
- mongodb  [db, L5]
- mysql  [db, L5]
- payment  [service, L2]
- paypal.com  [external, L6]
- rabbitmq  [queue, L5]
- ratings  [service, L2]
- redis  [db, L5]
- shipping  [service, L2]
- user  [service, L3]
- web  [service, L1]

## edges (type, confidence, provenance ref)
- cart -> catalogue  (sync-http, documented)  code: cart/server.js:348
- cart -> redis  (db, documented)  code: cart/server.js:377
- catalogue -> mongodb  (db, documented)  code: catalogue/server.js:140
- dispatch -> rabbitmq  (async, inferred)  code: docker-compose.yaml#depends_on
- istio-ingressgateway -> web  (sync-http, documented)  code: K8s/Istio/gateway.yaml
- payment -> cart  (sync-http, documented)  code: payment/payment.py:23
- payment -> paypal.com  (external, documented)  code: payment/payment.py:88
- payment -> rabbitmq  (async, inferred)  code: docker-compose.yaml#depends_on
- payment -> user  (sync-http, documented)  code: payment/payment.py:24
- ratings -> catalogue  (sync-http, documented)  code: ratings/html/api.php:151
- ratings -> mysql  (db, documented)  code: docker-compose.yaml#depends_on
- shipping -> cart  (sync-http, documented)  code: shipping/src/main/java/org/steveww/spark/Main.java:40
- shipping -> mysql  (db, documented)  code: shipping/src/main/java/org/steveww/spark/Main.java:41
- user -> mongodb  (db, documented)  code: user/server.js:260
- user -> redis  (db, documented)  code: docker-compose.yaml#depends_on
- web -> cart  (sync-http, documented)  code: web/default.conf.template:57
- web -> catalogue  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> payment  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> ratings  (sync-http, documented)  code: web/default.conf.template:69
- web -> shipping  (sync-http, documented)  code: docker-compose.yaml#depends_on
- web -> user  (sync-http, documented)  code: docker-compose.yaml#depends_on

## unresolved (source hint / raw target / file:line), first 40
- shipping  =>  http://%s/shipping/   @ shipping/src/main/java/org/steveww/spark/Main.java:40

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
  subgraph layer0 ["ingress"]
    direction LR
    dispatch["dispatch"]
    istio_ingressgateway(["istio-ingressgateway"]):::gateway
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    web["web"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    ratings["ratings"]
    payment["payment"]
    shipping["shipping"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    cart["cart"]
    user["user"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    catalogue["catalogue"]
  end
  subgraph layer5 ["data stores"]
    direction LR
    rabbitmq{{"rabbitmq"}}:::queue
    mysql[("mysql")]:::db
    mongodb[("mongodb")]:::db
    redis[("redis")]:::db
  end
  subgraph layer6 ["external"]
    direction LR
    paypal_com[/"paypal.com"/]:::external
  end
  user -. db .-> mongodb
  cart -.-> catalogue
  cart -. db .-> redis
  catalogue -. db .-> mongodb
  shipping -.-> cart
  shipping -. db .-> mysql
  payment -. ext .-> paypal_com
  payment -.-> cart
  payment -.-> user
  ratings -.-> catalogue
  user -. db .-> redis
  ratings -. db .-> mysql
  payment -. async? .-> rabbitmq
  dispatch -. async? .-> rabbitmq
  web -.-> catalogue
  web -.-> user
  web -.-> shipping
  web -.-> payment
  web -.-> cart
  web -.-> ratings
  istio_ingressgateway -.-> web
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef gateway fill:#e9f9ec,stroke:#2e8b57,color:#14351f;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 13 (not-preadded load-gen; node dispatch; node web; node cart; node rabbitmq)
- rule 2: 0
- rule 3: 0
- rule 4: 6 (table CATALOGUE_HOST=catalogue; table USER_HOST=user; table CART_HOST=cart; table SHIPPING_HOST=shipping; table PAYMENT_HOST=payment)
- rule 5: 7 (env CATALOGUE_HOST=catalogue; env USER_HOST=user; env CART_HOST=cart; env SHIPPING_HOST=shipping; env PAYMENT_HOST=payment)
- rule 6: 6 (url http://${CATALOGUE_HOST}:8080/; url http://${USER_HOST}:8080/; url http://${CART_HOST}:8080/; url http://${SHIPPING_HOST}:8080/; url http://${PAYMENT_HOST}:8080/)
- rule 7: 0
- rule 8: 0
- rule 9: 0
- rule 10: 1 (drop load (K8s))
- rule 11: 1 (k8s-over-compose 12 compose names ignored)
- rule 12: 0
- rule 13: 0
- rule 14: 0
- rule 15: 0
- rule 16: 0
- rule 17: 0
- rule 18: 9 (dockerfile-env CATALOGUE_HOST=catalogue; dockerfile-env USER_HOST=user; dockerfile-env CART_HOST=cart; dockerfile-env SHIPPING_HOST=shipping; dockerfile-env PAYMENT_HOST=payment)
- rule 19: 0
- rule 20: 8 (scoped CART_ENDPOINT=cart @shipping; scoped DB_HOST=mysql @shipping; scoped CATALOGUE_HOST=catalogue @web; scoped USER_HOST=user @web; scoped CART_HOST=cart @web)
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
- rule 35: 12 (inferred catalogue->mongodb; inferred user->mongodb; inferred user->redis; inferred cart->redis; inferred shipping->mysql)
- rule 36: 2 (ns-strip github.com; cluster-suffix web.robot-shop.svc.cluster.local)
- rule 37: 0
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 0
- rule 42: 0
- rule 43: 0
- rule 44: 1 (map mongo=mongodb)
- rule 45: 0
- rule 46: 0
- rule 47: 6 (valueref user/server.js:260 mongoURL; hostref cart/server.js:348 catalogueHost; valueref cart/server.js:377 redisHost; valueref catalogue/server.js:140 mongoURL; urlref payment/payment.py:88 PAYMENT_GATEWAY)
- rule 48: 2 (usesite MONGO_URL=mongodb; usesite REDIS_HOST=redis)
- rule 51: 8 (browser web/static/js/controller.js:101; browser web/static/js/controller.js:115; browser web/static/js/controller.js:189; browser web/static/js/controller.js:207; browser web/static/js/controller.js:273)
- rule 56: 2 (istio robotshop-gateway -> web.robot-shop.svc.cluster.local; edge istio-ingressgateway->web)
