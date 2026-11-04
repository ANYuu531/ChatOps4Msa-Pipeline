## stacks
- java/spring (FRAMEWORK, from pom.xml)
- typescript (GENERIC, from 342 .ts/.tsx/.mts/.cts files, package.json; tree-sitter grammar)
- javascript (GENERIC, from 3 .js/.mjs/.cjs/.jsx files, package.json; tree-sitter grammar)

## ledger sections
- jpa = 117
- name-ref = 59
- name-constant = 260
- http-server = 149
- kafka-consume = 4
- config = 210
- http-client = 155
- url = 96
- env-address = 145
- k8s-workload = 28
- workload-env = 108
- k8s-service = 29
- compose-service = 33
- compose-mount = 22
- compose-dependency = 6
- compose-port = 19
- key-read = 15
- service-root = 23
- module-deps = 22
- TOTAL = 1500 | files with syntax errors = 172

## graph
- after merge: 38 nodes / 123 edges | after normalize: 38 nodes / 123 edges | unresolved code edges = 28
- persistence services: [promotion, webhook, order, rating, common-library, location, payment, tax, product, search, inventory, cart, customer, media]

## nodes (kind, layer)
- backoffice  [service, L0]
- backoffice-nextjs  [service, L1]
- cart  [service, L3]
- collector  [service, L6]
- customer  [service, L3]
- elasticsearch  [db, L8]
- grafana  [service, L1]
- identity  [service, L6]
- inventory  [service, L1]
- jaeger  [service, L6]
- kafka  [queue, L8]
- kafka-connect  [queue, L8]
- kafka-ui  [service, L0]
- keycloak  [service, L0]
- location  [service, L4]
- loki  [service, L7]
- media  [service, L5]
- nginx  [service, L0]
- order  [service, L2]
- payment  [service, L1]
- payment-paypal  [service, L0]
- pgadmin  [service, L1]
- postgres  [db, L8]
- product  [service, L4]
- prometheus  [service, L6]
- promotion  [service, L1]
- rating  [service, L1]
- recommendation  [service, L1]
- redis  [db, L8]
- sampledata  [service, L1]
- search  [service, L1]
- storefront  [service, L0]
- storefront-nextjs  [service, L1]
- swagger-ui  [service, L1]
- tax  [service, L3]
- tempo  [service, L7]
- webhook  [service, L1]
- zookeeper  [service, L0]

## edges (type, confidence, provenance ref)
- backoffice -> backoffice-nextjs  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice -> collector  (sync-http, inferred)  code: docker-compose.yml
- backoffice -> identity  (sync-http, documented)  code: backoffice-bff/src/main/resources/application.yaml
- backoffice -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- backoffice -> nginx  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml
- backoffice -> order  (sync-http, inferred)  code: backoffice-bff/src/main/resources/application-dev.yaml
- backoffice -> redis  (db, inferred)  code: backoffice-bff/src/main/resources/application.yaml
- cart -> collector  (sync-http, inferred)  code: docker-compose.yml
- cart -> identity  (sync-http, documented)  code: cart/src/main/resources/application.properties
- cart -> media  (sync-http, inferred)  code: docker-compose.yml
- cart -> postgres  (db, documented)  code: cart/src/main/resources/application.properties
- cart -> product  (sync-http, inferred)  code: docker-compose.yml
- collector -> loki  (sync-http, documented)  code: docker/otel-collector/otelcol-config.yml
- collector -> prometheus  (sync-http, documented)  code: docker/otel-collector/otelcol-config.yml
- collector -> tempo  (sync-http, documented)  code: docker/otel-collector/otelcol-config.yml
- customer -> collector  (sync-http, inferred)  code: docker-compose.yml
- customer -> identity  (sync-http, documented)  code: customer/src/main/resources/application.properties
- customer -> location  (sync-http, inferred)  code: docker-compose.yml
- customer -> postgres  (db, documented)  code: customer/src/main/resources/application.properties
- identity -> postgres  (db, inferred)  code: docker-compose.yml
- inventory -> collector  (sync-http, inferred)  code: docker-compose.yml
- inventory -> identity  (sync-http, documented)  code: inventory/src/main/resources/application.properties
- inventory -> location  (sync-http, inferred)  code: docker-compose.yml
- inventory -> postgres  (db, documented)  code: inventory/src/main/resources/application.properties
- inventory -> product  (sync-http, inferred)  code: docker-compose.yml
- kafka -> zookeeper  (sync-http, documented)  code: docker-compose.yml#depends_on
- kafka-connect -> kafka  (async, documented)  code: docker-compose.yml#depends_on
- kafka-ui -> kafka  (async, documented)  code: docker-compose.yml#depends_on
- keycloak -> postgres  (db, inferred)  code: yaslocal.yaml
- location -> collector  (sync-http, inferred)  code: docker-compose.yml
- location -> identity  (sync-http, documented)  code: location/src/main/resources/application.properties
- location -> postgres  (db, documented)  code: location/src/main/resources/application.properties
- media -> collector  (sync-http, inferred)  code: docker-compose.yml
- media -> identity  (sync-http, documented)  code: media/src/main/resources/application.properties
- media -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- media -> postgres  (db, documented)  code: media/src/main/resources/application.properties
- nginx -> backoffice  (sync-http, documented)  code: nginx/templates/default.conf.template:64
- nginx -> cart  (sync-http, documented)  code: nginx/templates/default.conf.template:20
- nginx -> customer  (sync-http, documented)  code: nginx/templates/default.conf.template:17
- nginx -> elasticsearch  (db, inferred)  code: nginx/templates/default.conf.template:117
- nginx -> grafana  (sync-http, documented)  code: nginx/templates/default.conf.template:106
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
- nginx -> recommendation  (sync-http, documented)  code: nginx/templates/default.conf.template:57
- nginx -> sampledata  (sync-http, documented)  code: nginx/templates/default.conf.template:53
- nginx -> search  (sync-http, documented)  code: nginx/templates/default.conf.template:43
- nginx -> storefront  (sync-http, documented)  code: nginx/templates/default.conf.template:71
- nginx -> swagger-ui  (sync-http, documented)  code: nginx/templates/default.conf.template:11
- nginx -> tax  (sync-http, documented)  code: nginx/templates/default.conf.template:35
- nginx -> webhook  (sync-http, documented)  code: nginx/templates/default.conf.template:50
- order -> cart  (sync-http, inferred)  code: docker-compose.yml
- order -> collector  (sync-http, inferred)  code: docker-compose.yml
- order -> customer  (sync-http, inferred)  code: docker-compose.yml
- order -> identity  (sync-http, documented)  code: order/src/main/resources/application.properties
- order -> postgres  (db, documented)  code: order/src/main/resources/application.properties
- order -> product  (sync-http, inferred)  code: docker-compose.yml
- order -> tax  (sync-http, inferred)  code: docker-compose.yml
- payment -> collector  (sync-http, inferred)  code: docker-compose.yml
- payment -> identity  (sync-http, documented)  code: payment/src/main/resources/application.properties
- payment -> media  (sync-http, inferred)  code: docker-compose.yml
- payment -> order  (sync-http, inferred)  code: docker-compose.yml
- payment -> postgres  (db, documented)  code: payment/src/main/resources/application.properties
- payment-paypal -> identity  (sync-http, documented)  code: payment-paypal/src/main/resources/application.properties
- product -> collector  (sync-http, inferred)  code: docker-compose.yml
- product -> identity  (sync-http, documented)  code: product/src/main/resources/application.properties
- product -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- product -> media  (sync-http, inferred)  code: yaslocal.yaml
- product -> postgres  (db, documented)  code: product/src/main/resources/application.properties
- prometheus -> collector  (sync-http, documented)  code: docker/prometheus/prometheus.yml
- promotion -> collector  (sync-http, inferred)  code: docker-compose.yml
- promotion -> identity  (sync-http, documented)  code: promotion/src/main/resources/application.properties
- promotion -> postgres  (db, documented)  code: promotion/src/main/resources/application.properties
- promotion -> product  (sync-http, inferred)  code: docker-compose.yml
- rating -> collector  (sync-http, inferred)  code: docker-compose.yml
- rating -> customer  (sync-http, inferred)  code: docker-compose.yml
- rating -> identity  (sync-http, documented)  code: rating/src/main/resources/application.properties
- rating -> order  (sync-http, inferred)  code: docker-compose.yml
- rating -> postgres  (db, documented)  code: rating/src/main/resources/application.properties
- rating -> product  (sync-http, inferred)  code: docker-compose.yml
- recommendation -> identity  (sync-http, documented)  code: recommendation/src/main/resources/application.properties
- recommendation -> kafka  (async, documented)  code: recommendation/src/main/resources/application.properties
- recommendation -> postgres  (db, inferred)  code: recommendation/src/main/resources/application.properties
- sampledata -> collector  (sync-http, inferred)  code: docker-compose.yml
- sampledata -> identity  (sync-http, documented)  code: sampledata/src/main/resources/application.properties
- sampledata -> postgres  (db, inferred)  code: sampledata/src/main/java/com/yas/sampledata/config/DataSourceConfig.java:17
- search -> collector  (sync-http, inferred)  code: docker-compose.search.yml
- search -> elasticsearch  (db, documented)  code: search/src/main/resources/application.properties
- search -> identity  (sync-http, documented)  code: search/src/main/resources/application.properties
- search -> kafka  (async, documented)  code: search/src/main/resources/application.properties
- search -> product  (sync-http, inferred)  code: docker-compose.search.yml
- storefront -> cart  (sync-http, inferred)  code: docker-compose.yml
- storefront -> collector  (sync-http, inferred)  code: docker-compose.yml
- storefront -> customer  (sync-http, inferred)  code: docker-compose.yml
- storefront -> identity  (sync-http, documented)  code: storefront-bff/src/main/resources/application.yaml
- storefront -> inventory  (sync-http, inferred)  code: docker-compose.yml
- storefront -> jaeger  (sync-http, inferred)  code: yaslocal.yaml
- storefront -> location  (sync-http, inferred)  code: docker-compose.yml
- storefront -> nginx  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- storefront -> order  (sync-http, inferred)  code: docker-compose.yml
- storefront -> payment  (sync-http, inferred)  code: storefront-bff/src/main/resources/application-dev.yaml
- storefront -> promotion  (sync-http, inferred)  code: docker-compose.yml
- storefront -> rating  (sync-http, inferred)  code: docker-compose.yml
- storefront -> redis  (db, inferred)  code: storefront-bff/src/main/resources/application.yaml
- storefront -> sampledata  (sync-http, inferred)  code: docker-compose.yml
- storefront -> storefront-nextjs  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml
- storefront -> tax  (sync-http, inferred)  code: docker-compose.yml
- tax -> collector  (sync-http, inferred)  code: docker-compose.yml
- tax -> identity  (sync-http, documented)  code: tax/src/main/resources/application.properties
- tax -> location  (sync-http, inferred)  code: docker-compose.yml
- tax -> postgres  (db, documented)  code: tax/src/main/resources/application.properties
- webhook -> identity  (sync-http, documented)  code: webhook/src/main/resources/application.properties
- webhook -> kafka  (async, documented)  code: webhook/src/main/resources/application.properties
- webhook -> postgres  (db, documented)  code: webhook/src/main/resources/application.properties

## startup order (Compose depends_on that no address backs; not a call, not scored)
- kafka-ui ~> kafka-connect  code: docker-compose.yml#depends_on

## unresolved (source hint / raw target / file:line), first 40
- storefront  =>  null   @ storefront/common/services/ApiClientService.ts:31
- storefront  =>  null   @ storefront/modules/rating/services/RatingService.ts:23
- storefront  =>  null   @ storefront/modules/search/services/SearchService.ts:43
- backoffice  =>  null   @ backoffice/common/services/ApiClientService.ts:31
- null  =>  collector   @ .env:-1
- null  =>  http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_GRPC}   @ .env:-1
- null  =>  kafka   @ .env:-1
- null  =>  kafka:${KAFKA_SERVICE_PORT}   @ .env:-1
- null  =>  postgres   @ .env:-1
- null  =>  grafana   @ .env:-1
- null  =>  jaeger   @ .env:-1
- null  =>  prometheus   @ .env:-1
- null  =>  ${PROMETHEUS_SERVICE_HOST}:${PROMETHEUS_SERVICE_PORT}   @ .env:-1
- null  =>  redis   @ .env:-1
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
- null  =>  [{ url: 'http://api.yas.local/product/v3/api-docs', name: 'Product' },{ url: 'http://api.yas.local/media/v3/api-docs', name: 'Media' },{ url: 'http://api.yas.local/customer/v3/api-docs', name: 'Custom...   @ .env:-1

## alias questions (name [origin] seen in | candidates)
- storefront-nextjs  [graph]  looks like the same service as `storefront` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: storefront
- backoffice-nextjs  [graph]  looks like the same service as `backoffice` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: backoffice
- payment-paypal  [graph]  looks like the same service as `payment` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: payment
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
    storefront["storefront"]
    backoffice["backoffice"]
    keycloak["keycloak"]
    nginx["nginx"]
    payment_paypal["payment-paypal"]
    zookeeper["zookeeper"]
    kafka_ui["kafka-ui"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    swagger_ui["swagger-ui"]
    sampledata["sampledata"]
    promotion["promotion"]
    webhook["webhook"]
    recommendation["recommendation"]
    rating["rating"]
    payment["payment"]
    storefront_nextjs["storefront-nextjs"]
    backoffice_nextjs["backoffice-nextjs"]
    search["search"]
    inventory["inventory"]
    pgadmin["pgadmin"]
    grafana["grafana"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    order["order"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    tax["tax"]
    customer["customer"]
    cart["cart"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    product["product"]
    location["location"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    media["media"]
  end
  subgraph layer6 ["services · depth 6"]
    direction LR
    jaeger["jaeger"]
    identity["identity"]
    collector["collector"]
    prometheus["prometheus"]
  end
  subgraph layer7 ["services · depth 7"]
    direction LR
    tempo["tempo"]
    loki["loki"]
  end
  subgraph layer8 ["data stores"]
    direction LR
    postgres[("postgres")]:::db
    kafka{{"kafka"}}:::queue
    redis[("redis")]:::db
    elasticsearch[("elasticsearch")]:::db
    kafka_connect{{"kafka-connect"}}:::queue
  end
  sampledata -. db? .-> postgres
  promotion -.-> identity
  promotion -. db .-> postgres
  webhook -.-> identity
  webhook -. db .-> postgres
  webhook -. async .-> kafka
  recommendation -. db? .-> postgres
  recommendation -.-> identity
  recommendation -. async .-> kafka
  order -.-> identity
  order -. db .-> postgres
  collector -.-> prometheus
  collector -.-> tempo
  collector -.-> loki
  prometheus -.-> collector
  rating -.-> identity
  rating -. db .-> postgres
  sampledata -.-> identity
  location -.-> identity
  location -. db .-> postgres
  payment -.-> identity
  payment -. db .-> postgres
  storefront -.-> nginx
  storefront -.-> storefront_nextjs
  storefront -. declared? .-> payment
  storefront -.-> identity
  storefront -. db? .-> redis
  tax -.-> identity
  tax -. db .-> postgres
  product -.-> identity
  product -. db .-> postgres
  backoffice -.-> nginx
  backoffice -.-> backoffice_nextjs
  backoffice -. declared? .-> order
  backoffice -.-> identity
  backoffice -. db? .-> redis
  search -. db .-> elasticsearch
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
  nginx -.-> search
  nginx -.-> payment
  nginx -.-> webhook
  nginx -.-> sampledata
  nginx -.-> recommendation
  nginx -.-> backoffice
  nginx -.-> storefront
  nginx -.-> pgadmin
  nginx -.-> identity
  nginx -.-> grafana
  nginx -. db? .-> elasticsearch
  nginx -. async .-> kafka
  inventory -.-> identity
  inventory -. db .-> postgres
  cart -.-> identity
  cart -. db .-> postgres
  kafka -.-> zookeeper
  kafka_connect -. async .-> kafka
  kafka_ui -. async .-> kafka
  customer -.-> identity
  customer -. db .-> postgres
  media -. db .-> postgres
  media -.-> identity
  storefront -. declared? .-> jaeger
  backoffice -. declared? .-> jaeger
  media -. declared? .-> jaeger
  product -. declared? .-> media
  product -. declared? .-> jaeger
  keycloak -. db? .-> postgres
  search -. declared? .-> product
  search -. declared? .-> collector
  identity -. db? .-> postgres
  backoffice -. declared? .-> collector
  storefront -. declared? .-> customer
  storefront -. declared? .-> cart
  storefront -. declared? .-> rating
  storefront -. declared? .-> order
  storefront -. declared? .-> location
  storefront -. declared? .-> inventory
  storefront -. declared? .-> tax
  storefront -. declared? .-> promotion
  storefront -. declared? .-> sampledata
  storefront -. declared? .-> collector
  media -. declared? .-> collector
  product -. declared? .-> collector
  customer -. declared? .-> location
  customer -. declared? .-> collector
  cart -. declared? .-> media
  cart -. declared? .-> product
  cart -. declared? .-> collector
  rating -. declared? .-> product
  rating -. declared? .-> customer
  rating -. declared? .-> order
  rating -. declared? .-> collector
  order -. declared? .-> cart
  order -. declared? .-> customer
  order -. declared? .-> product
  order -. declared? .-> tax
  order -. declared? .-> collector
  payment -. declared? .-> order
  payment -. declared? .-> media
  payment -. declared? .-> collector
  location -. declared? .-> collector
  inventory -. declared? .-> product
  inventory -. declared? .-> location
  inventory -. declared? .-> collector
  promotion -. declared? .-> product
  promotion -. declared? .-> collector
  tax -. declared? .-> location
  tax -. declared? .-> collector
  sampledata -. declared? .-> collector
  kafka_ui -. startup .-> kafka_connect
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 23 (not-preadded promotion; not-preadded webhook; not-preadded recommendation; not-preadded order; not-preadded rating)
- rule 2: 0
- rule 3: 0
- rule 4: 4 (table spring.datasource.product.url=jdbc:postgresql://localhost:5432/product; table spring.datasource.media.url=jdbc:postgresql://localhost:5432/media; table OTEL_COLLECTOR_HOST=collector; table PROMETHEUS_SERVICE_HOST=prometheus)
- rule 5: 14 (not-a-host BASE_URL=/swagger-ui; env DB_ADDR=postgres; env SPRING_DATA_REDIS_HOST=redis; key hostname=api.yas.local; key spring.data.redis.host=redis)
- rule 6: 23 (url http://media; url http://swagger-ui:8080; url http://product; url http://customer; url http://cart)
- rule 7: 18 (env identity.KC_DB_URL=postgres; env media.SPRING_DATASOURCE_URL=postgres; env product.SPRING_DATASOURCE_URL=postgres; env customer.SPRING_DATASOURCE_URL=postgres; env cart.SPRING_DATASOURCE_URL=postgres)
- rule 8: 17 (config-signal url; config-signal server.port; config-signal spring.datasource.driver-class-name; config-signal spring.datasource.username; config-signal spring.datasource.password)
- rule 9: 77 (jpa promotion/src/main/java/com/yas/promotion/repository/PromotionRepository.java; jpa promotion/src/main/java/com/yas/promotion/model/PromotionUsage.java; jpa promotion/src/main/java/com/yas/promotion/model/Promotion.java; jpa promotion/src/main/java/com/yas/promotion/model/PromotionApply.java; jpa webhook/src/main/java/com/yas/webhook/repository/WebhookRepository.java)
- rule 10: 0
- rule 11: 1 (k8s-over-compose 33 compose names ignored)
- rule 12: 0
- rule 13: 0
- rule 14: 50 (edge storefront->jaeger; edge backoffice->jaeger; edge media->jaeger; edge product->media; edge product->jaeger)
- rule 15: 21 (published 5432; edge sampledata->postgres :5432; edge promotion->postgres :5432; edge webhook->postgres :5432; edge recommendation->postgres :5432)
- rule 16: 0
- rule 17: 0
- rule 18: 1 (dockerfile-env API_BASE_PATH=storefront)
- rule 19: 0
- rule 20: 1 (scoped API_BASE_PATH=storefront @storefront)
- rule 21: 0
- rule 22: 0
- rule 23: 0
- rule 24: 59 (interp search.YAS_PUBLIC_URL=api.yas.local; forward search.YAS_SERVICES_PRODUCT=product; forward search.OTEL_EXPORTER_OTLP_ENDPOINT=collector; forward backoffice.OTEL_EXPORTER_OTLP_ENDPOINT=collector; forward storefront.YAS_SERVICES_CUSTOMER=customer)
- rule 25: 0
- rule 26: 0
- rule 27: 3 (list spring.kafka.bootstrap-servers=kafka:9092; list spring.kafka.consumer.bootstrap-servers=kafka:9092; list spring.kafka.producer.bootstrap-servers=kafka:9092)
- rule 28: 0
- rule 29: 0
- rule 30: 3 (edge webhook->kafka; edge recommendation->kafka; edge search->kafka)
- rule 31: 0
- rule 32: 0
- rule 33: 1 (drop api.yas.local)
- rule 34: 0
- rule 35: 6 (startup collector->tempo; startup collector->prometheus; documented kafka->zookeeper; documented kafka-connect->kafka; documented kafka-ui->kafka)
- rule 36: 10 (ns-strip postgresql.postgres; keep-dotted storefront.yas.local.com; keep-dotted api.yas.local.com; ns-strip opentelemetry-collector.observability; keep-dotted identity.yas.local.com)
- rule 37: 0
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 3 (lib common-library; no-edge common-library/src/main/java/com/yas/commonlibrary/kafka/cdc/BaseCdcConsumer.java; no-edge common-library/src/main/resources/application.properties)
- rule 42: 119 (ignore /promotion/src/it/resources/mockito-extensions/org.mockito.plugins.MockMaker/; ignore /promotion/src/it/resources/logback-spring.xml/; ignore /promotion/src/it/resources/application.properties/; ignore /promotion/src/it/java/com/yas/promotion/service/PromotionServiceIT.java/; ignore /webhook/src/it/resources/mockito-extensions/org.mockito.plugins.MockMaker/)
- rule 43: 6 (drop springdoc.swagger-ui.path; drop springdoc.packagestoscan; drop springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant; drop springdoc.swagger-ui.oauth.client-id; drop springdoc.oauthflow.authorization-url)
- rule 44: 18 (compose-only promotion=promotion; compose-only order=order; image storefront=storefront-nextjs; no-workload storefront=storefront-nextjs; compose-only rating=rating)
- rule 45: 1 (accept elasticsearch.url=elasticsearch)
- rule 46: 5 (set-var http://search; set-var http://webhook; set-var http://recommendation; set-var http://grafana:3000; set-var http://elasticsearch:9200)
- rule 47: 151 (urlref storefront/modules/order/services/OrderService.ts:10 baseUrl; urlref storefront/modules/order/services/OrderService.ts:21 baseUrl; urlref storefront/modules/order/services/OrderService.ts:29 baseUrl; urlref storefront/modules/order/services/OrderService.ts:37 baseUrl; urlref storefront/modules/rating/services/RatingService.ts:28 baseUrl)
- rule 48: 0
- rule 49: 0
- rule 50: 2 (dotenv storefront/.env.production API_BASE_PATH=storefront; dotenv storefront/.env.development API_BASE_PATH=storefront)
- rule 51: 0
- rule 52: 89 (render k8s/deploy/observability/opentelemetry/templates/opentelemetry-collector.yaml; render k8s/deploy/observability/grafana/templates/dashboards.yaml; render k8s/deploy/observability/grafana/templates/grafana-credentials.secret.yaml; render k8s/deploy/observability/grafana/templates/grafana.yaml; render k8s/deploy/observability/grafana/templates/loki-datasource.yaml)
- rule 53: 3 (compose search.entrypoint elasticsearch:9200=elasticsearch; compose backoffice.entrypoint identity:80=identity; compose storefront.entrypoint identity:80=identity)
- rule 54: 0
- rule 55: 32 (list application.yaml opentelemetry-collector; list application.yaml identity.yas.local.com; list application.yaml kafka-cluster-kafka-brokers; list application.yaml cart; list application.yaml customer)
- rule 56: 1 (mount docker/otel-collector/otelcol-config.yml=collector)
- rule 57: 3 (template k8s/charts/values.template.yaml; template k8s/charts/Chart.template.yaml; template .github/workflow-template.yaml)
- rule 58: 0
- rule 59: 0
- rule 60: 7 (mounted docker/otel-collector/otelcol-config.yml 0.0.0.0; mounted docker/otel-collector/otelcol-config.yml prometheus; mounted docker/otel-collector/otelcol-config.yml tempo; mounted docker/otel-collector/otelcol-config.yml loki; mounted docker/otel-collector/otelcol-config.yml collector)
- rule 61: 0
