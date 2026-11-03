# 範圍普查（100 個系統）

每一列是「這個寫法在幾個系統裡出現過」，不是「漏了幾條邊」：出現不代表帶有相依關係。

| 項目 | 系統數 | 比例 |
|---|---|---|
| 原始碼全部是有文法的語言 | 94 | 94% |
| 含 Ruby／Rust（只由 LLM 閱讀） | 2 | 2% |
| 含其他不讀的語言（C／C++、Scala、Groovy…） | 4 | 4% |
| 原始碼檔案中有文法的比例（中位數） | 100% | |
| Kubernetes Secret 參照（secretKeyRef／secretRef） | 16 | 16% |
| Kubernetes Ingress | 18 | 18% |
| Kustomize | 1 | 1% |
| Compose extends | 2 | 2% |
| Compose include | 4 | 4% |
| Compose profiles | 7 | 7% |
| gRPC stub：Java／Kotlin | 1 | 1% |
| gRPC stub：Python | 0 | 0% |
| gRPC stub：JavaScript／TypeScript | 0 | 0% |
| Dapr 服務呼叫 | 7 | 7% |
| Traefik／Kong 路由 | 2 | 2% |
| Kubernetes Gateway API（HTTPRoute） | 0 | 0% |
| NATS／Redis pub/sub 等其他 broker | 11 | 11% |

## 各系統

| 系統 | 有文法／LLM／不讀（檔案數） | 出現的範圍外寫法 |
|---|---|---|
| AleksK1NG__Go-CQRS-Kafka-gRPC-Microservices | 100／0／0 | — |
| AntonioFalcaoJr__EventualShop | 1000／0／0 | — |
| CodelyTV__php-ddd-example | 215／0／0 | — |
| EdwinVW__pitstop | 258／0／0 | — |
| JarrodMalkovic__auction-website | 165／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、NATS／Redis pub/sub 等其他 broker |
| JavatoDev-com__internet-banking-concept-microservices | 121／0／0 | — |
| JoeCao__qbike | 73／0／0 | — |
| Mikaelemmmm__go-zero-looklook | 171／0／0 | — |
| NesoHQ__bgce-archive | 670／0／1 | — |
| PacktPublishing__Hands-on-Microservices-with-Python | 9／0／0 | — |
| PramithaMJ__fully-completed-microservices-Java-Springboot | 86／0／0 | — |
| Rapter1990__SpringBootMicroservices | 81／0／0 | — |
| SaiUpadhyayula__spring-boot-3-microservices-course | 53／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef） |
| ThoreauZZ__spring-cloud-example | 120／0／0 | Dapr 服務呼叫 |
| ThreeDotsLabs__monolith-microservice-shop | 43／0／0 | — |
| Toktik-Team__toktik | 118／0／0 | NATS／Redis pub/sub 等其他 broker |
| abhimishra91__insight | 22／0／0 | — |
| aelassas__microservices | 48／0／0 | — |
| aidanwhiteley__books | 101／0／0 | Compose profiles |
| alexcheng1982__happyride | 233／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| aliyun__alibabacloud-microservice-demo | 410／0／0 | Kubernetes Ingress |
| alperenkucukali__dotnet-microservices | 94／0／0 | — |
| amasen02__freshcart-backend | 965／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| antosubash__abp-microservice | 197／0／0 | — |
| apssouza22__java-microservice | 111／0／0 | — |
| asc-lab__micronaut-microservices-poc | 276／0／0 | — |
| aws-containers__retail-store-sample-app | 239／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、Compose include |
| buptmiao__microservice-app | 25／0／0 | — |
| charlessolar__eShopOnContainersDDD | 376／0／0 | — |
| codergogoi__nodejs_microservice | 56／0／0 | — |
| cuongnh28__spring-microservices-blueprint | 88／0／0 | — |
| danionescu0__docker-flask-mongodb-example | 31／0／0 | — |
| dashersw__cote-workshop | 10／0／0 | Compose extends |
| desenvolvedor-io__dev-store | 246／0／0 | Compose include |
| devdcores__BookStoreApp-Distributed-Application | 227／0／0 | — |
| ebosas__microservices | 25／0／0 | — |
| ebubekirdinc__SuuCat | 501／0／0 | — |
| falberthen__EcommerceDDD | 673／0／0 | Compose include、Compose profiles |
| fdonnet__ubik_accounting | 405／0／0 | Kubernetes Ingress |
| geoserver__geoserver-cloud | 739／0／0 | Compose extends、Compose include |
| getlarge__ticketing | 454／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、Compose profiles、NATS／Redis pub/sub 等其他 broker |
| go-kratos__beer-shop | 279／0／0 | — |
| go-saas__kit | 603／0／0 | — |
| hamed-shirbandi__TaskoMask | 550／0／0 | — |
| hantsy__spring-microservice-sample | 110／0／2 | — |
| harlow__go-micro-services | 19／0／0 | — |
| hemerajs__aither | 3／0／0 | NATS／Redis pub/sub 等其他 broker |
| hoangtien2k3__ecommerce-microservices | 482／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| idugalic__micro-company | 121／0／0 | — |
| ionutbalosin__ecommerce-app | 93／0／0 | Traefik／Kong 路由 |
| ivanpaulovich__clean-architecture-manga | 192／0／0 | — |
| jingsewu__open-wes | 1704／0／1 | Dapr 服務呼叫 |
| juicycleff__ultimate-backend | 781／0／0 | NATS／Redis pub/sub 等其他 broker |
| kalintsenkov__BettingSystem | 454／0／0 | — |
| kreta__Kreta | 513／0／0 | — |
| mabuonomo__example-nestjs-microservices-grpc | 30／0／0 | — |
| madslundt__NetCoreMicroservicesSample | 150／0／0 | — |
| mateaix__matecloud | 723／3／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、Compose profiles |
| merikbest__twitter-spring-reactjs | 1765／0／0 | — |
| meysamhadeli__booking-microservices | 201／0／0 | Kubernetes Ingress |
| meysamhadeli__booking-microservices-expressjs | 228／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、Kustomize |
| meysamhadeli__booking-microservices-java-spring-boot | 284／0／0 | gRPC stub：Java／Kotlin |
| meysamhadeli__booking-microservices-nestjs | 178／0／0 | — |
| minghsu0107__go-random-chat | 90／0／0 | Traefik／Kong 路由、NATS／Redis pub/sub 等其他 broker |
| mostafacs__ecommerce-microservices-spring-reactive-webflux | 34／0／0 | — |
| mudigal-technologies__microservices-sample | 48／0／0 | — |
| nameko__nameko-examples | 20／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| netcorebcn__quiz | 38／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| nplszfl__OmniTradeERP | 592／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress |
| nvawntien__go-judge-system | 494／0／0 | Compose profiles、NATS／Redis pub/sub 等其他 broker |
| opexdev__core | 1646／0／0 | — |
| oracle-quickstart__oci-cloudnative | 179／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、NATS／Redis pub/sub 等其他 broker |
| phongnguyend__Practical.CleanArchitecture | 2219／0／0 | Dapr 服務呼叫 |
| piomin__sample-spring-microservices-advanced | 26／0／0 | — |
| piomin__sample-spring-microservices-new | 22／0／0 | — |
| rasadov__EcommerceAPI | 98／0／0 | Compose profiles |
| rcherara__microservice-architecture | 183／0／0 | — |
| saalikmubeen__microservices-architectured-app | 110／0／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、NATS／Redis pub/sub 等其他 broker |
| sczyh30__vertx-blueprint-microservice | 104／0／0 | — |
| sdgmf__go-project-sample | 119／0／0 | — |
| sivaprasadreddy__spring-boot-microservices-series | 33／0／0 | — |
| tanerdiler__spring-boot-microservice-eureka-zuul-docker-gateway-kubernetes | 27／0／0 | — |
| thangchung__clean-architecture-dotnet | 194／0／0 | Dapr 服務呼叫 |
| thangchung__coffeeshop-on-nomad | 144／0／0 | Dapr 服務呼叫 |
| thangchung__magazine-website | 245／0／0 | — |
| thangchung__northwind-dotnet | 260／0／0 | Dapr 服務呼叫 |
| tinrab__meower | 20／0／0 | NATS／Redis pub/sub 等其他 broker |
| tinrab__spidey | 26／0／0 | — |
| tkssharma__12-factor-app-microservices | 275／0／0 | — |
| tkssharma__nestjs-microservices | 108／0／0 | — |
| tkssharma__nodejs-microservices-patterns | 306／0／0 | — |
| uid4oe__grpc-web-demo | 16／0／0 | — |
| uid4oe__microservices-go-grpc | 13／0／0 | — |
| ujjavaldesai07__spring-boot-react-ecommerce-app | 193／0／0 | — |
| vany0114__microservices-dotnetcore-docker-sf-k8s | 206／0／0 | Kubernetes Ingress |
| vietnam-devs__coolstore-microservices | 207／13／0 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Kubernetes Ingress、Dapr 服務呼叫 |
| walkline__ToCloud9 | 366／0／62 | Kubernetes Secret 參照（secretKeyRef／secretRef）、Compose profiles、NATS／Redis pub/sub 等其他 broker |
| wsrh8888__beaver-server | 1253／0／0 | — |
| xiajingren__HelloAbp | 698／0／0 | — |
| yiv__yivnet | 42／0／0 | — |
