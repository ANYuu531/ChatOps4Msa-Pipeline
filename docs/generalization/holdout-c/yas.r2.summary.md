## stacks
- java/spring (FRAMEWORK, from pom.xml)
- javascript (LLM, from 345 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- name-ref = 104
- jpa = 117
- name-constant = 285
- http-server = 149
- kafka-consume = 4
- config = 276
- url = 24
- compose-service = 33
- compose-dependency = 6
- k8s-service = 9
- k8s-workload = 8
- env-address = 84
- workload-env = 84
- service-root = 26
- module-deps = 23
- TOTAL = 1232 | files with syntax errors = 0

## graph
- after merge: 40 nodes / 120 edges | after normalize: 40 nodes / 120 edges | unresolved code edges = 27
- persistence services: [promotion, webhook, order, rating, common-library, location, payment, tax, product, search, inventory, cart, customer, media]

## nodes (kind, layer)
- api  [service, L9]
- backoffice  [service, L2]
- backoffice-bff  [service, L0]
- backoffice-nextjs  [service, L1]
- cart  [service, L6]
- collector  [service, L9]
- common-library  [service, L0]
- customer  [service, L6]
- delivery  [service, L0]
- identity  [service, L9]
- inventory  [service, L4]
- jaeger  [service, L9]
- kafka  [queue, L11]
- kafka-connect  [service, L0]
- kafka-ui  [service, L0]
- keycloak  [service, L0]
- location  [service, L7]
- media  [service, L8]
- nginx  [service, L1]
- order  [service, L5]
- payment  [service, L3]
- payment-paypal  [service, L0]
- pgadmin  [service, L2]
- postgres  [db, L11]
- product  [service, L7]
- prometheus  [service, L10]
- promotion  [service, L4]
- rating  [service, L4]
- recommendation  [service, L0]
- redis  [db, L11]
- sampledata  [service, L4]
- search  [service, L0]
- storefront  [service, L3]
- storefront-bff  [service, L0]
- storefront-nextjs  [service, L1]
- swagger-ui  [service, L2]
- tax  [service, L6]
- tempo  [service, L10]
- webhook  [service, L0]
- zookeeper  [service, L0]

## edges (type, confidence, provenance ref)
- backoffice -> collector  (sync-http, inferred)  code: docker-compose.yml
- backoffice -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- backoffice -> storefront  (sync-http, documented)  code: automation-ui/backoffice/src/main/resources/application.properties
- backoffice-bff -> backoffice-nextjs  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice-bff -> identity  (sync-http, documented)  code: backoffice-bff/src/main/resources/application.yaml
- backoffice-bff -> nginx  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice-bff -> order  (sync-http, inferred)  code: backoffice-bff/src/main/resources/application-dev.yaml
- backoffice-bff -> redis  (db, inferred)  code: backoffice-bff/src/main/resources/application.yaml
- cart -> collector  (sync-http, inferred)  code: docker-compose.yml
- cart -> identity  (sync-http, documented)  code: cart/src/main/resources/application.properties
- cart -> media  (sync-http, inferred)  code: docker-compose.yml
- cart -> postgres  (db, documented)  code: docker-compose.yml
- cart -> product  (sync-http, inferred)  code: docker-compose.yml
- collector -> prometheus  (sync-http, inferred)  code: docker-compose.o11y.yml#depends_on
- collector -> tempo  (sync-http, inferred)  code: docker-compose.o11y.yml#depends_on
- common-library -> identity  (sync-http, documented)  code: common-library/src/main/resources/application.properties
- customer -> collector  (sync-http, inferred)  code: docker-compose.yml
- customer -> identity  (sync-http, documented)  code: customer/src/it/resources/application.properties
- customer -> location  (sync-http, inferred)  code: docker-compose.yml
- customer -> postgres  (db, documented)  code: docker-compose.yml
- delivery -> identity  (sync-http, documented)  code: delivery/src/main/resources/application.properties
- inventory -> collector  (sync-http, inferred)  code: docker-compose.yml
- inventory -> identity  (sync-http, documented)  code: inventory/src/main/resources/application.properties
- inventory -> location  (sync-http, inferred)  code: docker-compose.yml
- inventory -> postgres  (db, documented)  code: docker-compose.yml
- inventory -> product  (sync-http, inferred)  code: docker-compose.yml
- kafka -> zookeeper  (sync-http, documented)  code: docker-compose.yml#depends_on
- kafka-connect -> kafka  (async, documented)  code: docker-compose.yml#depends_on
- kafka-ui -> kafka  (async, documented)  code: docker-compose.yml#depends_on
- keycloak -> postgres  (db, inferred)  code: yaslocal.yaml
- location -> collector  (sync-http, inferred)  code: docker-compose.yml
- location -> identity  (sync-http, documented)  code: location/src/main/resources/application.properties
- location -> postgres  (db, documented)  code: docker-compose.yml
- media -> api  (sync-http, inferred)  code: yaslocal.yaml
- media -> collector  (sync-http, inferred)  code: docker-compose.yml
- media -> identity  (sync-http, documented)  code: media/src/main/resources/application.properties
- media -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- media -> postgres  (db, documented)  code: yaslocal.yaml
- nginx -> backoffice  (sync-http, documented)  code: nginx/templates/default.conf.template:64
- nginx -> cart  (sync-http, documented)  code: nginx/templates/default.conf.template:20
- nginx -> customer  (sync-http, documented)  code: nginx/templates/default.conf.template:17
- nginx -> identity  (sync-http, documented)  code: nginx/templates/default.conf.template:86
- nginx -> inventory  (sync-http, documented)  code: nginx/templates/default.conf.template:32
- nginx -> kafka  (async, documented)  code: nginx/templates/default.conf.template:127
- nginx -> location  (sync-http, documented)  code: nginx/templates/default.conf.template:29
- nginx -> media  (sync-http, documented)  code: nginx/templates/default.conf.template:8
- nginx -> order  (sync-http, documented)  code: nginx/templates/default.conf.template:26
- nginx -> payment  (sync-http, documented)  code: nginx/templates/default.conf.template:46
- nginx -> pgadmin  (sync-http, documented)  code: nginx/templates/default.conf.template:78
- nginx -> product  (sync-http, documented)  code: nginx/templates/default.conf.template:14
- nginx -> promotion  (sync-http, documented)  code: nginx/templates/default.conf.template:38
- nginx -> rating  (sync-http, documented)  code: nginx/templates/default.conf.template:23
- nginx -> sampledata  (sync-http, documented)  code: nginx/templates/default.conf.template:53
- nginx -> storefront  (sync-http, documented)  code: nginx/templates/default.conf.template:71
- nginx -> swagger-ui  (sync-http, documented)  code: nginx/templates/default.conf.template:11
- nginx -> tax  (sync-http, documented)  code: nginx/templates/default.conf.template:35
- order -> cart  (sync-http, inferred)  code: docker-compose.yml
- order -> collector  (sync-http, inferred)  code: docker-compose.yml
- order -> customer  (sync-http, inferred)  code: docker-compose.yml
- order -> identity  (sync-http, documented)  code: order/src/main/resources/application.properties
- order -> postgres  (db, documented)  code: docker-compose.yml
- order -> product  (sync-http, inferred)  code: docker-compose.yml
- order -> tax  (sync-http, inferred)  code: docker-compose.yml
- payment -> collector  (sync-http, inferred)  code: docker-compose.yml
- payment -> identity  (sync-http, documented)  code: payment/src/main/resources/application.properties
- payment -> media  (sync-http, inferred)  code: docker-compose.yml
- payment -> order  (sync-http, inferred)  code: docker-compose.yml
- payment -> postgres  (db, documented)  code: docker-compose.yml
- payment -> storefront  (sync-http, documented)  code: payment/src/it/resources/application.properties
- payment-paypal -> identity  (sync-http, documented)  code: payment-paypal/src/it/resources/application.properties
- payment-paypal -> storefront  (sync-http, documented)  code: payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34
- product -> collector  (sync-http, inferred)  code: docker-compose.yml
- product -> identity  (sync-http, documented)  code: product/src/main/resources/application.properties
- product -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- product -> media  (sync-http, inferred)  code: yaslocal.yaml
- product -> postgres  (db, documented)  code: yaslocal.yaml
- promotion -> collector  (sync-http, inferred)  code: docker-compose.yml
- promotion -> identity  (sync-http, documented)  code: promotion/src/main/resources/application.properties
- promotion -> postgres  (db, documented)  code: docker-compose.yml
- promotion -> product  (sync-http, inferred)  code: docker-compose.yml
- rating -> collector  (sync-http, inferred)  code: docker-compose.yml
- rating -> customer  (sync-http, inferred)  code: docker-compose.yml
- rating -> identity  (sync-http, documented)  code: rating/src/main/resources/application.properties
- rating -> order  (sync-http, inferred)  code: docker-compose.yml
- rating -> postgres  (db, documented)  code: docker-compose.yml
- rating -> product  (sync-http, inferred)  code: docker-compose.yml
- recommendation -> identity  (sync-http, documented)  code: recommendation/src/it/resources/application.properties
- recommendation -> kafka  (async, documented)  code: recommendation/src/it/resources/application.properties
- sampledata -> collector  (sync-http, inferred)  code: docker-compose.yml
- sampledata -> identity  (sync-http, documented)  code: sampledata/src/main/resources/application.properties
- sampledata -> postgres  (db, inferred)  code: docker-compose.yml
- search -> collector  (sync-http, inferred)  code: docker-compose.search.yml
- search -> identity  (sync-http, documented)  code: search/src/it/resources/application.properties
- search -> kafka  (async, documented)  code: search/src/main/resources/application.properties
- search -> product  (sync-http, inferred)  code: docker-compose.search.yml
- storefront -> cart  (sync-http, inferred)  code: docker-compose.yml
- storefront -> collector  (sync-http, inferred)  code: docker-compose.yml
- storefront -> customer  (sync-http, inferred)  code: docker-compose.yml
- storefront -> inventory  (sync-http, inferred)  code: docker-compose.yml
- storefront -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- storefront -> location  (sync-http, inferred)  code: docker-compose.yml
- storefront -> order  (sync-http, inferred)  code: docker-compose.yml
- storefront -> payment  (sync-http, inferred)  code: docker-compose.yml
- storefront -> promotion  (sync-http, inferred)  code: docker-compose.yml
- storefront -> rating  (sync-http, inferred)  code: docker-compose.yml
- storefront -> sampledata  (sync-http, inferred)  code: docker-compose.yml
- storefront -> tax  (sync-http, inferred)  code: docker-compose.yml
- storefront-bff -> identity  (sync-http, documented)  code: storefront-bff/src/main/resources/application.yaml
- storefront-bff -> nginx  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- storefront-bff -> payment  (sync-http, inferred)  code: storefront-bff/src/main/resources/application-dev.yaml
- storefront-bff -> redis  (db, inferred)  code: storefront-bff/src/main/resources/application.yaml
- storefront-bff -> storefront-nextjs  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- swagger-ui -> api  (sync-http, inferred)  code: yaslocal.yaml
- tax -> collector  (sync-http, inferred)  code: docker-compose.yml
- tax -> identity  (sync-http, documented)  code: tax/src/main/resources/application.properties
- tax -> location  (sync-http, inferred)  code: docker-compose.yml
- tax -> postgres  (db, documented)  code: docker-compose.yml
- webhook -> identity  (sync-http, documented)  code: webhook/src/main/resources/application.properties
- webhook -> kafka  (async, documented)  code: webhook/src/main/resources/application.properties
- webhook -> postgres  (db, documented)  code: webhook/src/it/resources/application.properties

## unresolved (source hint / raw target / file:line), first 40
- nginx  =>  http://$docker_search_host   @ nginx/templates/default.conf.template:43
- nginx  =>  http://$docker_webhook_host   @ nginx/templates/default.conf.template:50
- nginx  =>  http://$docker_recommendation_host   @ nginx/templates/default.conf.template:57
- nginx  =>  http://$docker_grafana_host:3000   @ nginx/templates/default.conf.template:106
- nginx  =>  http://$docker_elasticsearch_host:9200   @ nginx/templates/default.conf.template:117
- null  =>  collector   @ .env:-1
- null  =>  http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_GRPC}   @ .env:-1
- null  =>  kafka   @ .env:-1
- null  =>  postgres   @ .env:-1
- null  =>  grafana   @ .env:-1
- null  =>  jaeger   @ .env:-1
- null  =>  prometheus   @ .env:-1
- null  =>  redis   @ .env:-1
- null  =>  http://api.yas.local   @ .env:-1
- null  =>  http://cart/cart   @ .env:-1
- null  =>  http://customer/customer   @ .env:-1
- null  =>  http://product/product   @ .env:-1
- null  =>  http://tax/tax   @ .env:-1
- null  =>  http://order/order   @ .env:-1
- null  =>  http://payment/payment   @ .env:-1
- null  =>  http://location/location   @ .env:-1
- null  =>  http://media/media   @ .env:-1
- null  =>  http://promotion/promotion   @ .env:-1
- null  =>  http://inventory/inventory   @ .env:-1
- null  =>  http://rating/rating   @ .env:-1
- null  =>  http://sampledata/sampledata   @ .env:-1
- null  =>  http://recommendation/recommendation   @ .env:-1

## alias questions (name [origin] seen in | candidates)
- payment-paypal  [graph]  looks like the same service as `payment` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: payment
- storefront-bff  [graph]  looks like the same service as `storefront` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: storefront
- storefront-nextjs  [graph]  looks like the same service as `storefront` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: storefront
- backoffice-bff  [graph]  looks like the same service as `backoffice` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: backoffice
- backoffice-nextjs  [graph]  looks like the same service as `backoffice` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: backoffice
- collector  [code]  config at .env   | candidates: -
- kafka  [code]  config at .env   | candidates: kafka-ui, kafka-connect
- postgres  [code]  config at .env   | candidates: -
- grafana  [code]  config at .env   | candidates: -
- jaeger  [code]  config at .env   | candidates: -
- prometheus  [code]  config at .env   | candidates: -
- redis  [code]  config at .env   | candidates: -

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
    keycloak["keycloak"]
    payment_paypal["payment-paypal"]
    webhook["webhook"]
    recommendation["recommendation"]
    common_library["common-library"]
    storefront_bff["storefront-bff"]
    backoffice_bff["backoffice-bff"]
    search["search"]
    delivery["delivery"]
    zookeeper["zookeeper"]
    kafka_connect["kafka-connect"]
    kafka_ui["kafka-ui"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    nginx["nginx"]
    storefront_nextjs["storefront-nextjs"]
    backoffice_nextjs["backoffice-nextjs"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    backoffice["backoffice"]
    swagger_ui["swagger-ui"]
    pgadmin["pgadmin"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    storefront["storefront"]
    payment["payment"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    promotion["promotion"]
    rating["rating"]
    sampledata["sampledata"]
    inventory["inventory"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    order["order"]
  end
  subgraph layer6 ["services · depth 6"]
    direction LR
    tax["tax"]
    customer["customer"]
    cart["cart"]
  end
  subgraph layer7 ["services · depth 7"]
    direction LR
    product["product"]
    location["location"]
  end
  subgraph layer8 ["services · depth 8"]
    direction LR
    media["media"]
  end
  subgraph layer9 ["services · depth 9"]
    direction LR
    jaeger["jaeger"]
    identity["identity"]
    collector["collector"]
    api["api"]
  end
  subgraph layer10 ["services · depth 10"]
    direction LR
    tempo["tempo"]
    prometheus["prometheus"]
  end
  subgraph layer11 ["data stores"]
    direction LR
    postgres[("postgres")]:::db
    kafka{{"kafka"}}:::queue
    redis[("redis")]:::db
  end
  payment_paypal -.-> storefront
  promotion -.-> identity
  webhook -. db .-> postgres
  webhook -.-> identity
  webhook -. async .-> kafka
  recommendation -.-> identity
  recommendation -. async .-> kafka
  collector -. declared? .-> tempo
  collector -. declared? .-> prometheus
  order -.-> identity
  backoffice -.-> storefront
  rating -.-> identity
  common_library -.-> identity
  sampledata -.-> identity
  location -.-> identity
  payment -.-> storefront
  payment -.-> identity
  storefront_bff -.-> nginx
  storefront_bff -.-> storefront_nextjs
  storefront_bff -. declared? .-> payment
  storefront_bff -.-> identity
  storefront_bff -. db? .-> redis
  tax -.-> identity
  product -.-> identity
  backoffice_bff -.-> nginx
  backoffice_bff -.-> backoffice_nextjs
  backoffice_bff -. declared? .-> order
  backoffice_bff -.-> identity
  backoffice_bff -. db? .-> redis
  search -.-> identity
  search -. async .-> kafka
  payment_paypal -.-> identity
  nginx -.-> media
  nginx -.-> swagger_ui
  nginx -.-> product
  nginx -.-> customer
  nginx -.-> cart
  nginx -.-> rating
  nginx -.-> order
  nginx -.-> location
  nginx -.-> inventory
  nginx -.-> tax
  nginx -.-> promotion
  nginx -.-> payment
  nginx -.-> sampledata
  nginx -.-> backoffice
  nginx -.-> storefront
  nginx -.-> pgadmin
  nginx -.-> identity
  nginx -. async .-> kafka
  inventory -.-> identity
  cart -.-> identity
  delivery -.-> identity
  kafka -.-> zookeeper
  kafka_connect -. async .-> kafka
  kafka_ui -. async .-> kafka
  customer -.-> identity
  media -.-> identity
  storefront -. declared? .-> jaeger
  backoffice -. declared? .-> jaeger
  swagger_ui -. declared? .-> api
  media -. db .-> postgres
  media -. declared? .-> api
  media -. declared? .-> jaeger
  product -. db .-> postgres
  product -. declared? .-> media
  product -. declared? .-> jaeger
  keycloak -. db? .-> postgres
  search -. declared? .-> product
  search -. declared? .-> collector
  backoffice -. declared? .-> collector
  storefront -. declared? .-> customer
  storefront -. declared? .-> cart
  storefront -. declared? .-> rating
  storefront -. declared? .-> order
  storefront -. declared? .-> location
  storefront -. declared? .-> inventory
  storefront -. declared? .-> tax
  storefront -. declared? .-> promotion
  storefront -. declared? .-> payment
  storefront -. declared? .-> sampledata
  storefront -. declared? .-> collector
  media -. declared? .-> collector
  product -. declared? .-> collector
  customer -. db .-> postgres
  customer -. declared? .-> location
  customer -. declared? .-> collector
  cart -. db .-> postgres
  cart -. declared? .-> media
  cart -. declared? .-> product
  cart -. declared? .-> collector
  rating -. db .-> postgres
  rating -. declared? .-> product
  rating -. declared? .-> customer
  rating -. declared? .-> order
  rating -. declared? .-> collector
  order -. db .-> postgres
  order -. declared? .-> cart
  order -. declared? .-> customer
  order -. declared? .-> product
  order -. declared? .-> tax
  order -. declared? .-> collector
  payment -. db .-> postgres
  payment -. declared? .-> order
  payment -. declared? .-> media
  payment -. declared? .-> collector
  location -. db .-> postgres
  location -. declared? .-> collector
  inventory -. db .-> postgres
  inventory -. declared? .-> product
  inventory -. declared? .-> location
  inventory -. declared? .-> collector
  promotion -. db .-> postgres
  promotion -. declared? .-> product
  promotion -. declared? .-> collector
  tax -. db .-> postgres
  tax -. declared? .-> location
  tax -. declared? .-> collector
  sampledata -. db? .-> postgres
  sampledata -. declared? .-> collector
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```
