## stacks
- java/spring (FRAMEWORK, from pom.xml)

## ledger sections
- name-constant = 12
- url-metadata = 10
- url = 12
- jpa = 4
- config = 202
- http-server = 200
- name-ref = 34
- rabbit-consume = 1
- feign = 5
- compose-service = 15
- compose-alias = 7
- compose-dependency = 16
- env-address = 1
- workload-env = 1
- k8s-namespace = 1
- k8s-service = 6
- k8s-workload = 6
- service-root = 9
- module-deps = 9
- TOTAL = 551 | files with syntax errors = 0

## graph
- after merge: 18 nodes / 36 edges | after normalize: 18 nodes / 36 edges | unresolved code edges = 7
- persistence services: [mall-admin, mall-portal, mall-demo, mall-search]

## nodes (kind, layer)
- elasticsearch  [db, L4]
- kibana  [service, L0]
- logstash  [service, L3]
- mall-admin  [service, L2]
- mall-auth  [service, L1]
- mall-demo  [service, L1]
- mall-gateway  [service, L0]
- mall-mbg  [service, L6]
- mall-monitor  [service, L0]
- mall-portal  [service, L2]
- mall-search  [service, L2]
- mongo  [db, L4]
- mysql  [db, L4]
- nacos-registry  [service, L3]
- openapi-sandbox.dl.alipaydev.com  [external, L5]
- oss-cn-shenzhen.aliyuncs.com  [external, L5]
- rabbitmq  [queue, L4]
- redis  [db, L4]

## edges (type, confidence, provenance ref)
- kibana -> elasticsearch  (db, inferred)  code: document/docker/docker-compose-env.yml
- logstash -> elasticsearch  (db, inferred)  code: document/docker/docker-compose-env.yml#depends_on
- mall-admin -> logstash  (sync-http, documented)  code: config/admin/mall-admin-prod.yaml
- mall-admin -> mysql  (db, documented)  code: config/admin/mall-admin-prod.yaml
- mall-admin -> nacos-registry  (sync-http, documented)  code: mall-admin/src/main/resources/application-prod.yml
- mall-admin -> oss-cn-shenzhen.aliyuncs.com  (external, documented)  code: mall-admin/src/main/resources/application.yml
- mall-admin -> redis  (db, documented)  code: config/admin/mall-admin-prod.yaml
- mall-auth -> mall-admin  (sync-http, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/service/UmsAdminService.java:16
- mall-auth -> mall-portal  (sync-http, documented)  code: mall-auth/src/main/java/com/macro/mall/auth/service/UmsMemberService.java:14
- mall-auth -> nacos-registry  (sync-http, documented)  code: mall-auth/src/main/resources/application-prod.yml
- mall-demo -> logstash  (sync-http, documented)  code: config/demo/mall-demo-prod.yaml
- mall-demo -> mall-admin  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignAdminService.java:13
- mall-demo -> mall-portal  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignPortalService.java:12
- mall-demo -> mall-search  (sync-http, documented)  code: mall-demo/src/main/java/com/macro/mall/demo/service/FeignSearchService.java:11
- mall-demo -> mysql  (db, documented)  code: config/demo/mall-demo-prod.yaml
- mall-demo -> nacos-registry  (sync-http, documented)  code: mall-demo/src/main/resources/application-prod.yml
- mall-gateway -> logstash  (sync-http, documented)  code: config/gateway/mall-gateway-prod.yaml
- mall-gateway -> mall-admin  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-auth  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-demo  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-portal  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> mall-search  (sync-http, documented)  code: mall-gateway/src/main/resources/application.yml
- mall-gateway -> nacos-registry  (sync-http, documented)  code: document/docker/docker-compose-app.yml#depends_on
- mall-gateway -> redis  (db, inferred)  code: config/gateway/mall-gateway-prod.yaml
- mall-monitor -> nacos-registry  (sync-http, documented)  code: mall-monitor/src/main/resources/application-prod.yml
- mall-portal -> logstash  (sync-http, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> mongo  (db, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> mysql  (db, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> nacos-registry  (sync-http, documented)  code: mall-portal/src/main/resources/application-prod.yml
- mall-portal -> openapi-sandbox.dl.alipaydev.com  (external, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> rabbitmq  (async, documented)  code: config/portal/mall-portal-prod.yaml
- mall-portal -> redis  (db, documented)  code: config/portal/mall-portal-prod.yaml
- mall-search -> elasticsearch  (db, documented)  code: config/search/mall-search-prod.yaml
- mall-search -> logstash  (sync-http, documented)  code: config/search/mall-search-prod.yaml
- mall-search -> mysql  (db, documented)  code: config/search/mall-search-prod.yaml
- mall-search -> nacos-registry  (sync-http, documented)  code: document/docker/docker-compose-app.yml#depends_on

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
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    mall_admin["mall-admin"]
    mall_portal["mall-portal"]
    mall_search["mall-search"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    nacos_registry["nacos-registry"]
    logstash["logstash"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    mysql[("mysql")]:::db
    mongo[("mongo")]:::db
    redis[("redis")]:::db
    rabbitmq{{"rabbitmq"}}:::queue
    elasticsearch[("elasticsearch")]:::db
  end
  subgraph layer5 ["external"]
    direction LR
    oss_cn_shenzhen_aliyuncs_com[/"oss-cn-shenzhen.aliyuncs.com"/]:::external
    openapi_sandbox_dl_alipaydev_com[/"openapi-sandbox.dl.alipaydev.com"/]:::external
  end
  subgraph layer6 ["no dependencies found"]
    direction LR
    mall_mbg["mall-mbg"]
  end
  mall_auth -.-> mall_portal
  mall_auth -.-> mall_admin
  mall_demo -.-> mall_search
  mall_demo -.-> mall_portal
  mall_demo -.-> mall_admin
  mall_admin -.-> nacos_registry
  mall_admin -. ext .-> oss_cn_shenzhen_aliyuncs_com
  mall_portal -.-> nacos_registry
  mall_auth -.-> nacos_registry
  mall_demo -. db .-> mysql
  mall_demo -.-> logstash
  mall_portal -. db .-> mysql
  mall_portal -. db .-> mongo
  mall_portal -. db .-> redis
  mall_portal -. async .-> rabbitmq
  mall_portal -.-> logstash
  mall_portal -. ext .-> openapi_sandbox_dl_alipaydev_com
  mall_admin -. db .-> mysql
  mall_admin -. db .-> redis
  mall_admin -.-> logstash
  mall_search -. db .-> mysql
  mall_search -. db .-> elasticsearch
  mall_search -.-> logstash
  mall_gateway -. db? .-> redis
  mall_gateway -.-> logstash
  mall_monitor -.-> nacos_registry
  mall_search -.-> nacos_registry
  mall_gateway -.-> nacos_registry
  logstash -. db? .-> elasticsearch
  mall_demo -.-> nacos_registry
  mall_gateway -.-> mall_auth
  mall_gateway -.-> mall_admin
  mall_gateway -.-> mall_portal
  mall_gateway -.-> mall_search
  mall_gateway -.-> mall_demo
  kibana -. db? .-> elasticsearch
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
