# 對照第三方資料集的邊集（程式計分，標準答案不是我們寫的）

由 `ExternalTruthAgreementTest` 產生。參考邊集＝MicroDepGraph 資料集（Rahman, Panichella & Taibi, SattoSE 2019）**自己發表的依賴圖**，不是本專案作者標註的 `truth/*.tsv`。
工具跑的是同一個 repo 在**資料集擷取日（2021-02-26）之前**的 commit，所以兩邊看的是同一份程式碼。

兩邊的「依賴」定義不同（資料集＝Compose `depends_on`/`links` ＋ 內部 API 呼叫，基礎設施當一般節點，沒有外部主機與證據等級），所以這裡報的是**一致度**而不是 precision：資料集的邊工具畫到多少（漏了就是工具的問題），以及只有一邊有的邊**逐條列出**，每條都要能追到原因。

| 專案 | commit（日期） | 資料集邊 | 工具畫到 | 一致度 | 工具另外畫了 |
|---|---|---|---|---|---|
| spring-petclinic | [`8e446ae`](https://github.com/spring-petclinic/spring-petclinic-microservices/tree/8e446ae7218392af7a897fdcec82be6b1db49518) (2021-02-06) | 13 | 11 | **0.85** | 4 |
| microservices-book | [`99d9d07`](https://github.com/ewolff/microservice/tree/99d9d07d1b68285d2ceefd8a811f31788ee43d75) (2020-09-17) | 5 | 5 | **1.00** | 4 |
| tap-and-eat | [`3ad20b8`](https://github.com/jferrater/Tap-And-Eat-MicroServices/tree/3ad20b8fa421dc837ef423270a9bf9ece1615a03) (2017-01-04) | 4 | 4 | **1.00** | 3 |
| spring-cloud-netflix | [`3b86bf0`](https://github.com/yidongnan/spring-cloud-netflix-example/tree/3b86bf0e20a7c7da8f4e3e7e2cb15bf4cd407743) (2020-09-11) | 26 | 26 | **1.00** | 2 |
| spring-cloud-microservice | [`6938297`](https://github.com/zpng/spring-cloud-microservice-examples/tree/6938297335e924f8066f5558b79ee82fa204c4ee) (2017-03-23) | 26 | 24 | **0.92** | 5 |
| lakeside-mutual | [`4fc6b43`](https://github.com/Microservice-API-Patterns/LakesideMutual/tree/4fc6b430da8a8c5db9a8d5918117e3c7a6a89c6d) (2021-02-26) | 9 | 9 | **1.00** | 11 |
| robot-shop | [`2fcc0c9`](https://github.com/instana/robot-shop/tree/2fcc0c9835dbb8c0c2db54f888608891f72aa308) (2021-02-24) | 12 | 12 | **1.00** | 6 |
| **合計** | 7 個專案 | **95** | **91** | **0.96** | — |

## spring-petclinic

資料集有、工具沒畫（2 條）：
- hystrix-dashboard -> config-server
- hystrix-dashboard -> discovery-server

工具畫了、資料集沒有（4 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- api-gateway -> customers-service  (sync-http, documented)
- api-gateway -> vets-service  (sync-http, documented)
- api-gateway -> visits-service  (sync-http, documented)
- config-server -> github.com  (external, documented)

## microservices-book

資料集的 5 條邊**全部畫到**。

工具畫了、資料集沒有（4 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- microservice-demo-turbine-server -> eureka  (sync-http, documented)
- microservice-demo-zuul-server -> eureka  (sync-http, documented)
- order -> catalog  (sync-http, documented)
- order -> customer  (sync-http, documented)

## tap-and-eat

資料集的 4 條邊**全部畫到**。

工具畫了、資料集沒有（3 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- configservice -> github.com  (external, documented)
- foodtrayservice -> item-service  (sync-http, documented)
- foodtrayservice -> price-service  (sync-http, documented)

## spring-cloud-netflix

資料集的 26 條邊**全部畫到**。

工具畫了、資料集沒有（2 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- service-a -> github.com  (external, documented)
- service-b -> github.com  (external, documented)

## spring-cloud-microservice

資料集有、工具沒畫（2 條）：
- hystrix -> discovery
- hystrix -> gateway

工具畫了、資料集沒有（5 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- cloud-config-server -> github.com  (external, documented)
- gateway -> cloud-simple-service  (sync-http, documented)
- gateway -> cloud-simple-serviceb  (sync-http, documented)
- gateway -> cloud-simple-ui  (sync-http, documented)
- gateway -> uaa-service  (sync-http, documented)

## lakeside-mutual

資料集的 9 條邊**全部畫到**。

工具畫了、資料集沒有（11 條；資料集看不到外部主機與程式碼層呼叫，所以多出來不等於錯）：
- customer-core -> eureka-server  (sync-http, documented)
- customer-core -> spring-boot-admin  (sync-http, inferred)
- customer-management-backend -> eureka-server  (sync-http, inferred)
- customer-management-backend -> spring-boot-admin  (sync-http, inferred)
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
- load -> web  (sync-http, documented)
- payment -> cart  (sync-http, documented)
- payment -> paypal.com  (external, documented)
- payment -> user  (sync-http, documented)
- web -> cart  (sync-http, inferred)
- web -> ratings  (sync-http, inferred)
