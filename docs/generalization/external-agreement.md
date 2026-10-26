# 對照第三方資料集的邊集（程式計分，標準答案不是我們寫的）

由 `ExternalTruthAgreementTest` 產生。參考邊集＝MicroDepGraph 資料集（Rahman, Panichella & Taibi, SattoSE 2019）**自己發表的依賴圖**，不是本專案作者標註的 `truth/*.tsv`。
工具跑的是同一個 repo 在**資料集相依圖檔提交日（2019-08-19）之前**的 commit，所以兩邊看的是同一份程式碼。

兩邊的「依賴」定義不同（資料集＝Compose `depends_on`/`links` ＋ 內部 API 呼叫，基礎設施當一般節點，沒有外部主機與證據等級），所以這裡報的是**一致度**而不是 precision：資料集的邊工具畫到多少（漏了就是工具的問題），以及只有一邊有的邊**逐條列出**，每條都要能追到原因。

| 專案 | commit（日期） | 資料集邊 | 工具畫到 | 一致度 | 工具另外畫了 |
|---|---|---|---|---|---|
| spring-petclinic | [`1860fff`](https://github.com/spring-petclinic/spring-petclinic-microservices/tree/1860fffc083d351da5a1bdf308402246c8c98269) (2019-05-14) | 13 | 13 | **1.00** | 2 |
| microservices-book | [`c445384`](https://github.com/ewolff/microservice/tree/c445384002c912e196cf967cfb4d39b82867ba3c) (2019-01-28) | 5 | 5 | **1.00** | 2 |
| tap-and-eat | [`3ad20b8`](https://github.com/jferrater/Tap-And-Eat-MicroServices/tree/3ad20b8fa421dc837ef423270a9bf9ece1615a03) (2017-01-04) | 4 | 4 | **1.00** | 10 |
| spring-cloud-netflix | [`f0e57c3`](https://github.com/yidongnan/spring-cloud-netflix-example/tree/f0e57c3fe0e421a99ffd58601f88c93bc48432f3) (2018-08-25) | 26 | 26 | **1.00** | 2 |
| spring-cloud-microservice | [`6938297`](https://github.com/zpng/spring-cloud-microservice-examples/tree/6938297335e924f8066f5558b79ee82fa204c4ee) (2017-03-23) | 26 | 24 | **0.92** | 19 |
| lakeside-mutual | [`4fc6b43`](https://github.com/Microservice-API-Patterns/LakesideMutual/tree/4fc6b430da8a8c5db9a8d5918117e3c7a6a89c6d) (2021-02-26) | 9 | 9 | **1.00** | 12 |
| robot-shop | [`5939f27`](https://github.com/instana/robot-shop/tree/5939f27e27facc2f1a96661dc04a652c20a9ca4b) (2019-07-16) | 12 | 12 | **1.00** | 6 |
| **合計** | 7 個專案 | **95** | **93** | **0.98** | — |

## spring-petclinic

資料集的 13 條邊**全部畫到**。

工具畫了、資料集沒有（2 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- api-gateway -> customers-service  (sync-http, documented)
- api-gateway -> visits-service  (sync-http, documented)

## microservices-book

資料集的 5 條邊**全部畫到**。

工具畫了、資料集沒有（2 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- order -> catalog  (sync-http, documented)
- order -> customer  (sync-http, documented)

## tap-and-eat

資料集的 4 條邊**全部畫到**。

工具畫了、資料集沒有（10 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- accountservice -> discoveryservice  (sync-http, inferred)
- configservice -> discoveryservice  (sync-http, inferred)
- configservice -> github.com  (external, documented)
- customerservice -> discoveryservice  (sync-http, inferred)
- foodtrayservice -> discoveryservice  (sync-http, inferred)
- foodtrayservice -> item-service  (sync-http, documented)
- foodtrayservice -> price-service  (sync-http, documented)
- itemservice -> discoveryservice  (sync-http, inferred)
- priceservice -> discoveryservice  (sync-http, inferred)
- storeservice -> discoveryservice  (sync-http, inferred)

## spring-cloud-netflix

資料集的 26 條邊**全部畫到**。

工具畫了、資料集沒有（2 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- service-a -> github.com  (external, documented)
- service-b -> github.com  (external, documented)

## spring-cloud-microservice

資料集有、工具沒畫（2 條）：
- hystrix -> discovery
- hystrix -> gateway

工具畫了、資料集沒有（19 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- cloud-config-server -> cloud-eureka-server  (sync-http, inferred)
- cloud-config-server -> github.com  (external, documented)
- cloud-finagle-thrift-client -> zipkin  (sync-http, inferred)
- cloud-finagle-thrift-server -> zipkin  (sync-http, inferred)
- cloud-hystrix-dashboard -> cloud-config-server  (sync-http, inferred)
- gateway -> cloud-eureka-server  (sync-http, inferred)
- gateway -> cloud-simple-service  (sync-http, documented)
- gateway -> cloud-simple-serviceb  (sync-http, documented)
- gateway -> cloud-simple-ui  (sync-http, documented)
- gateway -> uaa-service  (sync-http, documented)
- simple-service -> cloud-config-server  (sync-http, inferred)
- simple-service -> cloud-eureka-server  (sync-http, inferred)
- simple-serviceb -> cloud-config-server  (sync-http, inferred)
- simple-serviceb -> cloud-eureka-server  (sync-http, inferred)
- simple-ui -> cloud-config-server  (sync-http, inferred)
- simple-ui -> cloud-eureka-server  (sync-http, inferred)
- simple-ui -> cloud-simple-service  (sync-http, documented)
- turbine -> cloud-config-server  (sync-http, inferred)
- turbine -> cloud-eureka-server  (sync-http, inferred)

## lakeside-mutual

資料集的 9 條邊**全部畫到**。

工具畫了、資料集沒有（12 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- customer-core -> eureka-server  (sync-http, documented)
- customer-core -> spring-boot-admin  (sync-http, inferred)
- customer-management-backend -> eureka-server  (sync-http, inferred)
- customer-management-backend -> spring-boot-admin  (sync-http, inferred)
- customer-management-frontend -> customer-self-service-backend  (sync-http, inferred)
- customer-self-service-backend -> eureka-server  (sync-http, inferred)
- customer-self-service-backend -> policy-management-backend  (sync-http, documented)
- customer-self-service-backend -> spring-boot-admin  (sync-http, inferred)
- policy-management-backend -> eureka-server  (sync-http, inferred)
- policy-management-backend -> spring-boot-admin  (sync-http, inferred)
- risk-management-server -> policy-management-backend-queue  (sync-http, inferred)
- spring-boot-admin -> eureka-server  (sync-http, documented)

## robot-shop

資料集的 12 條邊**全部畫到**。

工具畫了、資料集沒有（6 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- payment -> cart  (sync-http, documented)
- payment -> paypal.com  (external, documented)
- payment -> user  (sync-http, documented)
- shipping -> cart  (sync-http, documented)
- web -> cart  (sync-http, documented)
- web -> ratings  (sync-http, documented)
