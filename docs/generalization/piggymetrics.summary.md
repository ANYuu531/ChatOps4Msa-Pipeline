## stacks
- java/spring (FRAMEWORK, from pom.xml)
- javascript (LLM, from 8 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- jpa = 4
- http-server = 16
- name-ref = 11
- feign = 5
- name-constant = 2
- config = 51
- compose-service = 28
- compose-dependency = 8
- service-root = 10
- module-deps = 9
- TOTAL = 144 | files with syntax errors = 0

## graph
- after merge: 16 nodes / 33 edges | after normalize: 16 nodes / 33 edges | unresolved code edges = 0
- persistence services: [statistics-service, account-service, notification-service, auth-service]

## nodes (kind, layer)
- account-mongodb  [db, L7]
- account-service  [service, L2]
- api.exchangeratesapi.io  [external, L8]
- auth-mongodb  [db, L7]
- auth-service  [service, L4]
- config  [service, L6]
- gateway  [service, L0]
- monitoring  [service, L0]
- notification-mongodb  [db, L7]
- notification-service  [service, L1]
- rabbitmq  [queue, L7]
- registry  [service, L5]
- smtp.gmail.com  [external, L8]
- statistics-mongodb  [db, L7]
- statistics-service  [service, L3]
- turbine-stream-service  [service, L0]

## edges (type, confidence, provenance ref)
- account-service -> account-mongodb  (db, documented)  code: config/src/main/resources/shared/account-service.yml
- account-service -> auth-service  (sync-http, documented)  code: account-service/src/main/java/com/piggymetrics/account/client/AuthServiceClient.java:9
- account-service -> config  (sync-http, documented)  code: account-service/src/main/resources/bootstrap.yml
- account-service -> rabbitmq  (async, documented)  code: config/src/main/resources/shared/application.yml
- account-service -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml
- account-service -> statistics-service  (sync-http, documented)  code: account-service/src/main/java/com/piggymetrics/account/client/StatisticsServiceClient.java:10
- auth-service -> auth-mongodb  (db, documented)  code: config/src/main/resources/shared/auth-service.yml
- auth-service -> config  (sync-http, documented)  code: docker-compose.yml#depends_on
- auth-service -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml
- gateway -> account-service  (sync-http, documented)  code: config/src/main/resources/shared/gateway.yml
- gateway -> auth-service  (sync-http, documented)  code: config/src/main/resources/shared/gateway.yml
- gateway -> config  (sync-http, documented)  code: docker-compose.yml#depends_on
- gateway -> notification-service  (sync-http, documented)  code: config/src/main/resources/shared/gateway.yml
- gateway -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml
- gateway -> statistics-service  (sync-http, documented)  code: config/src/main/resources/shared/gateway.yml
- monitoring -> config  (sync-http, documented)  code: docker-compose.yml#depends_on
- notification-service -> account-service  (sync-http, documented)  code: notification-service/src/main/java/com/piggymetrics/notification/client/AccountServiceClient.java:9
- notification-service -> auth-service  (sync-http, documented)  code: config/src/main/resources/shared/notification-service.yml
- notification-service -> config  (sync-http, documented)  code: notification-service/src/main/resources/bootstrap.yml
- notification-service -> notification-mongodb  (db, documented)  code: config/src/main/resources/shared/notification-service.yml
- notification-service -> rabbitmq  (async, documented)  code: config/src/main/resources/shared/application.yml
- notification-service -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml
- notification-service -> smtp.gmail.com  (external, documented)  code: config/src/main/resources/shared/notification-service.yml
- registry -> config  (sync-http, documented)  code: registry/src/main/resources/bootstrap.yml
- statistics-service -> api.exchangeratesapi.io  (external, documented)  code: statistics-service/src/main/java/com/piggymetrics/statistics/client/ExchangeRatesClient.java:10
- statistics-service -> auth-service  (sync-http, documented)  code: config/src/main/resources/shared/statistics-service.yml
- statistics-service -> config  (sync-http, documented)  code: statistics-service/src/main/resources/bootstrap.yml
- statistics-service -> rabbitmq  (async, documented)  code: config/src/main/resources/shared/application.yml
- statistics-service -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml
- statistics-service -> statistics-mongodb  (db, documented)  code: config/src/main/resources/shared/statistics-service.yml
- turbine-stream-service -> config  (sync-http, documented)  code: turbine-stream-service/src/main/resources/bootstrap.yml
- turbine-stream-service -> rabbitmq  (async, documented)  code: config/src/main/resources/shared/application.yml
- turbine-stream-service -> registry  (sync-http, documented)  code: config/src/main/resources/shared/application.yml

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
    gateway["gateway"]
    monitoring["monitoring"]
    turbine_stream_service["turbine-stream-service"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    notification_service["notification-service"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    account_service["account-service"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    statistics_service["statistics-service"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    auth_service["auth-service"]
  end
  subgraph layer5 ["services · depth 5"]
    direction LR
    registry["registry"]
  end
  subgraph layer6 ["services · depth 6"]
    direction LR
    config["config"]
  end
  subgraph layer7 ["data stores"]
    direction LR
    rabbitmq{{"rabbitmq"}}:::queue
    auth_mongodb[("auth-mongodb")]:::db
    account_mongodb[("account-mongodb")]:::db
    statistics_mongodb[("statistics-mongodb")]:::db
    notification_mongodb[("notification-mongodb")]:::db
  end
  subgraph layer8 ["external"]
    direction LR
    api_exchangeratesapi_io[/"api.exchangeratesapi.io"/]:::external
    smtp_gmail_com[/"smtp.gmail.com"/]:::external
  end
  statistics_service -. ext .-> api_exchangeratesapi_io
  account_service -.-> statistics_service
  account_service -.-> auth_service
  notification_service -.-> account_service
  statistics_service -.-> config
  account_service -.-> config
  notification_service -.-> config
  gateway -.-> auth_service
  gateway -.-> account_service
  gateway -.-> statistics_service
  gateway -.-> notification_service
  auth_service -. db .-> auth_mongodb
  notification_service -.-> auth_service
  notification_service -. db .-> notification_mongodb
  notification_service -. ext .-> smtp_gmail_com
  statistics_service -.-> auth_service
  statistics_service -. db .-> statistics_mongodb
  statistics_service -.-> registry
  account_service -.-> registry
  notification_service -.-> registry
  turbine_stream_service -.-> registry
  auth_service -.-> registry
  gateway -.-> registry
  statistics_service -. async .-> rabbitmq
  account_service -. async .-> rabbitmq
  notification_service -. async .-> rabbitmq
  turbine_stream_service -. async .-> rabbitmq
  account_service -. db .-> account_mongodb
  turbine_stream_service -.-> config
  registry -.-> config
  gateway -.-> config
  auth_service -.-> config
  monitoring -.-> config
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
