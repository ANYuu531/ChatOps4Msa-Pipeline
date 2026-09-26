## stacks
- java/spring (FRAMEWORK, from pom.xml)
- javascript (LLM, from 97 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)

## ledger sections
- url = 8
- jpa = 51
- http-server = 45
- url-constant = 1
- feign = 2
- config = 52
- compose-service = 39
- compose-dependency = 33
- env-address = 23
- workload-env = 38
- k8s-workload = 9
- service-root = 12
- TOTAL = 313 | files with syntax errors = 0

## graph
- after merge: 10 nodes / 12 edges | after normalize: 10 nodes / 12 edges | unresolved code edges = 1
- persistence services: [customer-core, customer-management-backend, policy-management-backend, customer-self-service-backend]

## nodes (kind, layer)
- customer-core  [service, L3]
- customer-management-backend  [service, L1]
- customer-management-frontend  [service, L0]
- customer-self-service-backend  [service, L1]
- customer-self-service-frontend  [service, L0]
- eureka-server  [service, L4]
- policy-management-backend  [service, L2]
- policy-management-frontend  [service, L0]
- risk-management-server  [service, L0]
- spring-boot-admin  [service, L0]

## edges (type, confidence, evidence)
- customer-core -> eureka-server  (sync-http, documented)  code: docker-compose-eureka.yml
- customer-management-backend -> customer-core  (sync-http, documented)  code: customer-management-backend/src/main/java/com/lakesidemutual/customermanagement/infrastructure/CustomerCoreClient.java:20
- customer-management-frontend -> customer-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- customer-self-service-backend -> customer-core  (sync-http, documented)  code: customer-self-service-backend/src/main/java/com/lakesidemutual/customerselfservice/infrastructure/CustomerCoreRemoteProxy.java:33
- customer-self-service-backend -> policy-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- customer-self-service-frontend -> customer-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- customer-self-service-frontend -> customer-self-service-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- customer-self-service-frontend -> policy-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- policy-management-backend -> customer-core  (sync-http, documented)  code: policy-management-backend/src/main/java/com/lakesidemutual/policymanagement/infrastructure/CustomerCoreRemoteProxy.java:29
- policy-management-frontend -> policy-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- risk-management-server -> policy-management-backend  (sync-http, documented)  code: docker-compose-eureka.yml
- spring-boot-admin -> eureka-server  (sync-http, documented)  code: docker-compose-eureka.yml

## unresolved (source hint / raw target / file:line), first 40
- nginx  =>  http://app_servers   @ customer-core/nginx-loadbalancing/nginx/nginx.conf:19

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    customer_self_service_frontend["customer-self-service-frontend"]
    policy_management_frontend["policy-management-frontend"]
    risk_management_server["risk-management-server"]
    spring_boot_admin["spring-boot-admin"]
    customer_management_frontend["customer-management-frontend"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    customer_self_service_backend["customer-self-service-backend"]
    customer_management_backend["customer-management-backend"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    policy_management_backend["policy-management-backend"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    customer_core["customer-core"]
  end
  subgraph layer4 ["services · depth 4"]
    direction LR
    eureka_server["eureka-server"]
  end
  customer_management_backend -.-> customer_core
  policy_management_backend -.-> customer_core
  customer_self_service_backend -.-> customer_core
  customer_core -.-> eureka_server
  customer_management_frontend -.-> customer_management_backend
  customer_self_service_backend -.-> policy_management_backend
  customer_self_service_frontend -.-> customer_self_service_backend
  customer_self_service_frontend -.-> customer_management_backend
  customer_self_service_frontend -.-> policy_management_backend
  policy_management_frontend -.-> policy_management_backend
  spring_boot_admin -.-> eureka_server
  risk_management_server -.-> policy_management_backend

```
