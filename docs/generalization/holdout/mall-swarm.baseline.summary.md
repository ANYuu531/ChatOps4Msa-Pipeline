## stacks
- java/spring (FRAMEWORK, from pom.xml)

## ledger sections
- url = 12
- config = 144
- http-server = 200
- rabbit-consume = 1
- feign = 5
- compose-service = 15
- compose-dependency = 2
- env-address = 1
- workload-env = 1
- k8s-workload = 6
- service-root = 9
- TOTAL = 396 | files with syntax errors = 0

## graph
- after merge: 21 nodes / 39 edges | after normalize: 21 nodes / 39 edges | unresolved code edges = 7
- persistence services: []

## nodes (kind, layer)
- db  [service, L3]
- elasticsearch  [db, L4]
- github.com  [external, L5]
- kibana  [service, L0]
- logstash  [service, L3]
- mall-admin  [service, L2]
- mall-auth  [service, L1]
- mall-common  [service, L6]
- mall-demo  [service, L1]
- mall-gateway  [service, L0]
- mall-mbg  [service, L6]
- mall-monitor  [service, L0]
- mall-portal  [service, L2]
- mall-search  [service, L2]
- mall.order.cancel  [queue, L4]
- mongo  [db, L4]
- nacos-registry  [service, L1]
- openapi-sandbox.dl.alipaydev.com  [external, L5]
- rabbit  [queue, L4]
- redis  [db, L4]
- www.macrozheng.com  [external, L5]

## edges (type, confidence, provenance ref)
- kibana -> elasticsearch  (db, documented)  code: document/docker/docker-compose-env.yml
- logstash -> elasticsearch  (db, documented)  code: document/docker/docker-compose-env.yml
- mall-admin -> db  (sync-http, documented)  code: config/admin/mall-admin-prod.yaml
- mall-admin -> github.com  (external, documented)  code: mall-admin/src/main/java/com/macro/mall/config/SpringDocConfig.java:32
- mall-admin -> logstash  (sync-http, documented)  code: config/admin/mall-admin-prod.yaml
- mall-admin -> redis  (db, inferred)  code: config/admin/mall-admin-prod.yaml
- mall-admin -> www.macrozheng.com  (external, documented)  code: mall-admin/src/main/java/com/macro/mall/config/SpringDocConfig.java:35
- mall-auth -> github.com  (external, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/config/SpringDocConfig.java:31
- mall-auth -> mall-admin  (sync-http, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/service/UmsAdminService.java:16
- mall-auth -> mall-portal  (sync-http, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/service/UmsMemberService.java:14
- mall-auth -> www.macrozheng.com  (external, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/config/SpringDocConfig.java:34
- mall-demo -> db  (sync-http, documented)  code: config/demo/mall-demo-prod.yaml
- mall-demo -> github.com  (external, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/config/SpringDocConfig.java:32
- mall-demo -> logstash  (sync-http, documented)  code: config/demo/mall-demo-prod.yaml
- mall-demo -> mall-admin  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignAdminService.java:13
- mall-demo -> mall-portal  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignPortalService.java:12
- mall-demo -> mall-search  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignSearchService.java:11
- mall-demo -> www.macrozheng.com  (external, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/config/SpringDocConfig.java:35
- mall-gateway -> logstash  (sync-http, documented)  code: config/gateway/mall-gateway-prod.yaml
- mall-gateway -> mall-admin  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-auth  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-demo  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-portal  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-search  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> redis  (db, inferred)  code: config/gateway/mall-gateway-prod.yaml
- mall-monitor -> nacos-registry  (sync-http, documented)  code: mall-monitor/src/main/resources/application-prod.yml
- mall-portal -> db  (sync-http, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> github.com  (external, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/config/SpringDocConfig.java:32
- mall-portal -> logstash  (sync-http, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> mongo  (db, inferred)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> openapi-sandbox.dl.alipaydev.com  (external, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> rabbit  (async, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> redis  (db, inferred)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> www.macrozheng.com  (external, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/config/SpringDocConfig.java:35
- mall-search -> db  (sync-http, documented)  code: config/search/mall-search-prod.yaml
- mall-search -> github.com  (external, documented)  code: mall-search/src/main/java/com/macro/mall/search/config/SpringDocConfig.java:26
- mall-search -> logstash  (sync-http, documented)  code: config/search/mall-search-prod.yaml
- mall-search -> www.macrozheng.com  (external, documented)  code: mall-search/src/main/java/com/macro/mall/search/config/SpringDocConfig.java:29
- mall.order.cancel -> mall-portal  (async, documented)  code: mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderReceiver.java:16

## unresolved (source hint / raw target / file:line), first 40
- mall-admin  =>  http://   @ mall-admin/src/main/java/com/macro/mall/service/impl/OssServiceImpl.java:66
- mall-admin  =>  http://   @ mall-admin/src/main/java/com/macro/mall/service/impl/OssServiceImpl.java:93
- mall-admin  =>  http://39.98.190.128:8080/aliyun/oss/callback   @ mall-admin/src/main/resources/application.yml:-1
- mall-portal  =>  http://192.168.3.101:8060/#/pages/money/paySuccess   @ config/portal/mall-portal-prod.yaml:-1
- mall-admin  =>  http://39.98.190.128:8080/aliyun/oss/callback   @ config/admin/mall-admin-dev.yaml:-1
- mall-admin  =>  http://39.98.190.128:8080/aliyun/oss/callback   @ config/admin/mall-admin-prod.yaml:-1
- mall-admin  =>  http://192.168.3.101:9090   @ config/admin/mall-admin-prod.yaml:-1

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
    mall_monitor["mall-monitor"]
    mall_gateway["mall-gateway"]
    kibana["kibana"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    mall_auth["mall-auth"]
    mall_demo["mall-demo"]
    nacos_registry["nacos-registry"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    mall_admin["mall-admin"]
    mall_portal["mall-portal"]
    mall_search["mall-search"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    db["db"]
    logstash["logstash"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    mall_order_cancel{{"mall.order.cancel"}}:::queue
    mongo[("mongo")]:::db
    redis[("redis")]:::db
    rabbit{{"rabbit"}}:::queue
    elasticsearch[("elasticsearch")]:::db
  end
  subgraph layer5 ["external"]
    direction LR
    github_com[/"github.com"/]:::external
    www_macrozheng_com[/"www.macrozheng.com"/]:::external
    openapi_sandbox_dl_alipaydev_com[/"openapi-sandbox.dl.alipaydev.com"/]:::external
  end
  subgraph layer6 ["no dependencies found"]
    direction LR
    mall_mbg["mall-mbg"]
    mall_common["mall-common"]
  end
  mall_admin -. ext .-> github_com
  mall_admin -. ext .-> www_macrozheng_com
  mall_portal -. ext .-> github_com
  mall_portal -. ext .-> www_macrozheng_com
  mall_order_cancel -. async .-> mall_portal
  mall_auth -. ext .-> github_com
  mall_auth -. ext .-> www_macrozheng_com
  mall_auth -.-> mall_portal
  mall_auth -.-> mall_admin
  mall_demo -. ext .-> github_com
  mall_demo -. ext .-> www_macrozheng_com
  mall_demo -.-> mall_search
  mall_demo -.-> mall_portal
  mall_demo -.-> mall_admin
  mall_search -. ext .-> github_com
  mall_search -. ext .-> www_macrozheng_com
  mall_demo -.-> db
  mall_demo -.-> logstash
  mall_portal -.-> db
  mall_portal -. db? .-> mongo
  mall_portal -. db? .-> redis
  mall_portal -. async .-> rabbit
  mall_portal -.-> logstash
  mall_portal -. ext .-> openapi_sandbox_dl_alipaydev_com
  mall_admin -.-> db
  mall_admin -. db? .-> redis
  mall_admin -.-> logstash
  mall_search -.-> db
  mall_search -.-> logstash
  mall_gateway -. db? .-> redis
  mall_gateway -.-> logstash
  mall_monitor -.-> nacos_registry
  logstash -. db .-> elasticsearch
  kibana -. db .-> elasticsearch
  mall_gateway -.-> mall_auth
  mall_gateway -.-> mall_admin
  mall_gateway -.-> mall_portal
  mall_gateway -.-> mall_search
  mall_gateway -.-> mall_demo
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
