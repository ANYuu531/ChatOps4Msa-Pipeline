## stacks
- java/spring (FRAMEWORK, from pom.xml)

## ledger sections
- name-ref = 11
- name-constant = 39
- url-constant = 84
- url = 60
- http-server = 128
- jpa = 28
- feign = 33
- compose-service = 21
- config = 82
- service-root = 10
- module-deps = 10
- TOTAL = 506 | files with syntax errors = 0

## graph
- after merge: 11 nodes / 37 edges | after normalize: 11 nodes / 37 edges | unresolved code edges = 0
- persistence services: [product-service, shipping-service, payment-service, favourite-service, order-service, user-service]

## nodes (kind, layer)
- api-gateway  [service, L0]
- cloud-config  [service, L5]
- favourite-service  [service, L2]
- github.com  [external, L7]
- order-service  [service, L3]
- payment-service  [service, L2]
- product-service  [service, L3]
- proxy-client  [service, L1]
- service-discovery  [service, L6]
- shipping-service  [service, L2]
- user-service  [service, L4]

## edges (type, confidence, provenance ref)
- api-gateway -> cloud-config  (sync-http, inferred)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> favourite-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> order-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> payment-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> product-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> proxy-client  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> service-discovery  (sync-http, inferred)  code: api-gateway/pom.xml
- api-gateway -> shipping-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- api-gateway -> user-service  (sync-http, documented)  code: api-gateway/src/main/resources/application.yml
- cloud-config -> github.com  (external, documented)  code: cloud-config/src/main/resources/application.yml
- cloud-config -> service-discovery  (sync-http, inferred)  code: cloud-config/pom.xml
- favourite-service -> cloud-config  (sync-http, inferred)  code: favourite-service/src/main/resources/application.yml
- favourite-service -> product-service  (sync-http, documented)  code: favourite-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:21
- favourite-service -> service-discovery  (sync-http, inferred)  code: favourite-service/pom.xml
- favourite-service -> user-service  (sync-http, documented)  code: favourite-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:18
- order-service -> cloud-config  (sync-http, inferred)  code: order-service/src/main/resources/application.yml
- order-service -> service-discovery  (sync-http, inferred)  code: order-service/pom.xml
- order-service -> user-service  (sync-http, documented)  code: order-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:18
- payment-service -> cloud-config  (sync-http, inferred)  code: payment-service/src/main/resources/application.yml
- payment-service -> order-service  (sync-http, documented)  code: payment-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:24
- payment-service -> service-discovery  (sync-http, inferred)  code: payment-service/pom.xml
- product-service -> cloud-config  (sync-http, inferred)  code: product-service/src/main/resources/application.yml
- product-service -> service-discovery  (sync-http, inferred)  code: product-service/pom.xml
- proxy-client -> cloud-config  (sync-http, inferred)  code: proxy-client/src/main/resources/application.yml
- proxy-client -> favourite-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/favourite/service/FavouriteClientService.java:19
- proxy-client -> order-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/order/service/OrderClientService.java:19
- proxy-client -> payment-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/payment/service/PaymentClientService.java:19
- proxy-client -> product-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/product/service/ProductClientService.java:19
- proxy-client -> service-discovery  (sync-http, inferred)  code: proxy-client/pom.xml
- proxy-client -> shipping-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/orderItem/service/OrderItemClientService.java:19
- proxy-client -> user-service  (sync-http, documented)  code: proxy-client/src/main/java/com/selimhorri/app/business/user/service/UserClientService.java:19
- shipping-service -> cloud-config  (sync-http, inferred)  code: shipping-service/src/main/resources/application.yml
- shipping-service -> order-service  (sync-http, documented)  code: shipping-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:24
- shipping-service -> product-service  (sync-http, documented)  code: shipping-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:21
- shipping-service -> service-discovery  (sync-http, inferred)  code: shipping-service/pom.xml
- user-service -> cloud-config  (sync-http, inferred)  code: user-service/src/main/resources/application.yml
- user-service -> service-discovery  (sync-http, inferred)  code: user-service/pom.xml

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
    api_gateway["api-gateway"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    proxy_client["proxy-client"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    shipping_service["shipping-service"]
    payment_service["payment-service"]
    favourite_service["favourite-service"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    product_service["product-service"]
    order_service["order-service"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    user_service["user-service"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    cloud_config["cloud-config"]
  end
  subgraph layer6 ["services · depth 6"]
    direction LR
    service_discovery["service-discovery"]
  end
  subgraph layer7 ["external"]
    direction LR
    github_com[/"github.com"/]:::external
  end
  shipping_service -.-> product_service
  shipping_service -.-> order_service
  payment_service -.-> order_service
  favourite_service -.-> user_service
  favourite_service -.-> product_service
  order_service -.-> user_service
  proxy_client -.-> order_service
  proxy_client -.-> payment_service
  proxy_client -.-> user_service
  proxy_client -.-> product_service
  proxy_client -.-> favourite_service
  proxy_client -.-> shipping_service
  product_service -. declared? .-> cloud_config
  cloud_config -. ext .-> github_com
  api_gateway -. declared? .-> cloud_config
  api_gateway -.-> order_service
  api_gateway -.-> payment_service
  api_gateway -.-> product_service
  api_gateway -.-> shipping_service
  api_gateway -.-> user_service
  api_gateway -.-> favourite_service
  api_gateway -.-> proxy_client
  shipping_service -. declared? .-> cloud_config
  payment_service -. declared? .-> cloud_config
  favourite_service -. declared? .-> cloud_config
  order_service -. declared? .-> cloud_config
  user_service -. declared? .-> cloud_config
  proxy_client -. declared? .-> cloud_config
  product_service -. declared? .-> service_discovery
  cloud_config -. declared? .-> service_discovery
  api_gateway -. declared? .-> service_discovery
  shipping_service -. declared? .-> service_discovery
  payment_service -. declared? .-> service_discovery
  favourite_service -. declared? .-> service_discovery
  order_service -. declared? .-> service_discovery
  user_service -. declared? .-> service_discovery
  proxy_client -. declared? .-> service_discovery
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
