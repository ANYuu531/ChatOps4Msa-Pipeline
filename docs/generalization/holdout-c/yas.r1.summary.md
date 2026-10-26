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
- env-address = 25
- workload-env = 25
- service-root = 26
- module-deps = 23
- TOTAL = 1114 | files with syntax errors = 0

## graph
- after merge: 40 nodes / 89 edges | after normalize: 40 nodes / 89 edges | unresolved code edges = 31
- persistence services: [promotion, webhook, order, rating, common-library, location, payment, tax, product, search, inventory, cart, customer, media]

## nodes (kind, layer)
- api  [service, L4]
- backoffice  [service, L2]
- backoffice-bff  [service, L0]
- backoffice-nextjs  [service, L1]
- cart  [service, L2]
- collector  [service, L0]
- common-library  [service, L0]
- customer  [service, L2]
- delivery  [service, L0]
- identity  [service, L4]
- inventory  [service, L2]
- jaeger  [service, L4]
- kafka  [queue, L5]
- kafka-connect  [queue, L5]
- kafka-ui  [service, L0]
- keycloak  [service, L0]
- location  [service, L2]
- media  [service, L3]
- nginx  [service, L1]
- order  [service, L2]
- payment  [service, L2]
- payment-paypal  [service, L0]
- pgadmin  [service, L2]
- postgres  [db, L5]
- product  [service, L2]
- prometheus  [service, L1]
- promotion  [service, L2]
- rating  [service, L2]
- recommendation  [service, L0]
- redis  [db, L5]
- sampledata  [service, L2]
- search  [service, L0]
- storefront  [service, L3]
- storefront-bff  [service, L0]
- storefront-nextjs  [service, L1]
- swagger-ui  [service, L2]
- tax  [service, L2]
- tempo  [service, L1]
- webhook  [service, L0]
- zookeeper  [service, L0]

## edges (type, confidence, provenance ref)
- backoffice -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- backoffice -> storefront  (sync-http, documented)  code: automation-ui/backoffice/src/main/resources/application.properties
- backoffice-bff -> api  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-dev.yaml
- backoffice-bff -> backoffice-nextjs  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice-bff -> identity  (sync-http, documented)  code: backoffice-bff/src/main/resources/application.yaml
- backoffice-bff -> nginx  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice-bff -> order  (sync-http, inferred)  code: backoffice-bff/src/main/resources/application-dev.yaml
- backoffice-bff -> redis  (db, inferred)  code: backoffice-bff/src/main/resources/application.yaml
- cart -> api  (sync-http, documented)  code: cart/src/main/resources/application.properties
- cart -> identity  (sync-http, documented)  code: cart/src/main/resources/application.properties
- cart -> postgres  (db, documented)  code: docker-compose.yml
- collector -> prometheus  (sync-http, documented)  code: docker-compose.o11y.yml
- collector -> tempo  (sync-http, documented)  code: docker-compose.o11y.yml
- common-library -> identity  (sync-http, documented)  code: common-library/src/main/resources/application.properties
- customer -> api  (sync-http, documented)  code: customer/src/main/resources/application.properties
- customer -> identity  (sync-http, documented)  code: customer/src/it/resources/application.properties
- customer -> postgres  (db, documented)  code: docker-compose.yml
- delivery -> identity  (sync-http, documented)  code: delivery/src/main/resources/application.properties
- inventory -> api  (sync-http, documented)  code: inventory/src/main/resources/application.properties
- inventory -> identity  (sync-http, documented)  code: inventory/src/main/resources/application.properties
- inventory -> postgres  (db, documented)  code: docker-compose.yml
- kafka -> zookeeper  (sync-http, documented)  code: docker-compose.yml
- kafka-connect -> kafka  (async, documented)  code: docker-compose.yml
- kafka-ui -> kafka  (async, documented)  code: docker-compose.yml
- kafka-ui -> kafka-connect  (async, documented)  code: docker-compose.yml
- keycloak -> postgres  (db, inferred)  code: yaslocal.yaml
- location -> identity  (sync-http, documented)  code: location/src/main/resources/application.properties
- location -> postgres  (db, documented)  code: docker-compose.yml
- media -> api  (sync-http, inferred)  code: yaslocal.yaml
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
- order -> api  (sync-http, documented)  code: order/src/main/resources/application.properties
- order -> identity  (sync-http, documented)  code: order/src/main/resources/application.properties
- payment -> api  (sync-http, documented)  code: payment/src/it/java/com/yas/payment/service/MediaServiceIT.java:42
- payment -> identity  (sync-http, documented)  code: payment/src/main/resources/application.properties
- payment -> postgres  (db, documented)  code: docker-compose.yml
- payment -> storefront  (sync-http, documented)  code: payment/src/it/resources/application.properties
- payment-paypal -> identity  (sync-http, documented)  code: payment-paypal/src/it/resources/application.properties
- payment-paypal -> storefront  (sync-http, documented)  code: payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34
- product -> api  (sync-http, documented)  code: product/src/main/resources/application.properties
- product -> identity  (sync-http, documented)  code: product/src/main/resources/application.properties
- product -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- product -> media  (sync-http, inferred)  code: yaslocal.yaml
- product -> postgres  (db, documented)  code: yaslocal.yaml
- promotion -> api  (sync-http, documented)  code: promotion/src/main/resources/application.properties
- promotion -> identity  (sync-http, documented)  code: promotion/src/main/resources/application.properties
- promotion -> postgres  (db, documented)  code: docker-compose.yml
- rating -> api  (sync-http, documented)  code: rating/src/main/resources/application.properties
- rating -> identity  (sync-http, documented)  code: rating/src/main/resources/application.properties
- rating -> postgres  (db, documented)  code: docker-compose.yml
- recommendation -> api  (sync-http, documented)  code: recommendation/src/it/resources/application.properties
- recommendation -> identity  (sync-http, documented)  code: recommendation/src/it/resources/application.properties
- sampledata -> identity  (sync-http, documented)  code: sampledata/src/main/resources/application.properties
- sampledata -> postgres  (db, inferred)  code: docker-compose.yml
- search -> api  (sync-http, documented)  code: search/src/it/resources/application.properties
- search -> identity  (sync-http, documented)  code: search/src/it/resources/application.properties
- storefront -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- storefront-bff -> api  (sync-http, documented)  code: storefront-bff/src/main/resources/application-dev.yaml
- storefront-bff -> identity  (sync-http, documented)  code: storefront-bff/src/main/resources/application.yaml
- storefront-bff -> nginx  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- storefront-bff -> payment  (sync-http, inferred)  code: storefront-bff/src/main/resources/application-dev.yaml
- storefront-bff -> redis  (db, inferred)  code: storefront-bff/src/main/resources/application.yaml
- storefront-bff -> storefront-nextjs  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- swagger-ui -> api  (sync-http, inferred)  code: yaslocal.yaml
- tax -> api  (sync-http, documented)  code: tax/src/main/resources/application.properties
- tax -> identity  (sync-http, documented)  code: tax/src/main/resources/application.properties
- tax -> postgres  (db, documented)  code: docker-compose.yml
- webhook -> api  (sync-http, documented)  code: webhook/src/main/resources/application.properties
- webhook -> identity  (sync-http, documented)  code: webhook/src/main/resources/application.properties
- webhook -> postgres  (db, documented)  code: webhook/src/it/resources/application.properties

## unresolved (source hint / raw target / file:line), first 40
- webhook  =>  ${webhook.integration.kafka.order.topic-name}   @ webhook/src/main/java/com/yas/webhook/integration/inbound/OrderEventInbound.java:16
- webhook  =>  ${webhook.integration.kafka.product.topic-name}   @ webhook/src/main/java/com/yas/webhook/integration/inbound/ProductEventInbound.java:16
- recommendation  =>  ${product.topic.name}   @ recommendation/src/main/java/com/yas/recommendation/kafka/consumer/ProductSyncDataConsumer.java:33
- search  =>  ${product.topic.name}   @ search/src/main/java/com/yas/search/kafka/consumer/ProductSyncDataConsumer.java:37
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
- kafka  [code]  config at .env   | candidates: kafka-ui
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
    collector["collector"]
    common_library["common-library"]
    storefront_bff["storefront-bff"]
    backoffice_bff["backoffice-bff"]
    search["search"]
    delivery["delivery"]
    zookeeper["zookeeper"]
    kafka_ui["kafka-ui"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    tempo["tempo"]
    prometheus["prometheus"]
    nginx["nginx"]
    storefront_nextjs["storefront-nextjs"]
    backoffice_nextjs["backoffice-nextjs"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    backoffice["backoffice"]
    swagger_ui["swagger-ui"]
    product["product"]
    payment["payment"]
    promotion["promotion"]
    order["order"]
    rating["rating"]
    sampledata["sampledata"]
    location["location"]
    tax["tax"]
    customer["customer"]
    cart["cart"]
    inventory["inventory"]
    pgadmin["pgadmin"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    storefront["storefront"]
    media["media"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    jaeger["jaeger"]
    api["api"]
    identity["identity"]
  end
  subgraph layer5 ["data stores"]
    direction LR
    postgres[("postgres")]:::db
    redis[("redis")]:::db
    kafka{{"kafka"}}:::queue
    kafka_connect{{"kafka-connect"}}:::queue
  end
  payment -.-> api
  payment_paypal -.-> storefront
  promotion -.-> identity
  promotion -.-> api
  webhook -. db .-> postgres
  webhook -.-> api
  webhook -.-> identity
  recommendation -.-> api
  recommendation -.-> identity
  collector -.-> tempo
  collector -.-> prometheus
  order -.-> identity
  order -.-> api
  backoffice -.-> storefront
  rating -.-> identity
  rating -.-> api
  common_library -.-> identity
  sampledata -.-> identity
  location -.-> identity
  payment -.-> storefront
  payment -.-> identity
  storefront_bff -.-> nginx
  storefront_bff -.-> storefront_nextjs
  storefront_bff -. declared? .-> payment
  storefront_bff -.-> api
  storefront_bff -.-> identity
  storefront_bff -. db? .-> redis
  tax -.-> api
  tax -.-> identity
  product -.-> identity
  product -.-> api
  backoffice_bff -.-> nginx
  backoffice_bff -.-> backoffice_nextjs
  backoffice_bff -. declared? .-> order
  backoffice_bff -.-> api
  backoffice_bff -.-> identity
  backoffice_bff -. db? .-> redis
  search -.-> identity
  search -.-> api
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
  inventory -.-> api
  cart -.-> identity
  cart -.-> api
  delivery -.-> identity
  kafka -.-> zookeeper
  kafka_connect -. async .-> kafka
  kafka_ui -. async .-> kafka
  kafka_ui -. async .-> kafka_connect
  customer -.-> identity
  customer -.-> api
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
  customer -. db .-> postgres
  cart -. db .-> postgres
  rating -. db .-> postgres
  payment -. db .-> postgres
  location -. db .-> postgres
  inventory -. db .-> postgres
  promotion -. db .-> postgres
  tax -. db .-> postgres
  sampledata -. db? .-> postgres
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```
