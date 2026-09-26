# 靜態 greenfield 圖 vs ground truth（程式計分）

由 `GeneralizationScoreTest` 產生：讀 `truth/<name>.tsv` 與 `<name>.mmd`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R |
|---|---|---|---|---|---|---|---|---|---|
| bank-of-anthos | 11 | 11 | 0 | 1 | 1.00 | 0.92 | 1.00 / 1.00 | 1.00 / 1.00 | — / 0.00 |
| ecommerce | 20 | 20 | 0 | 18 | 1.00 | 0.53 | 1.00 / 1.00 | — | — / 0.28 |
| ewolff-k8s | 5 | 5 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | — | — / 1.00 |
| online-boutique | 2 | 2 | 0 | 14 | 1.00 | 0.13 | 1.00 / 0.07 | 1.00 / 1.00 | — / 0.00 |
| piggymetrics | 41 | 34 | 7 | 0 | 0.83 | 1.00 | 0.63 / 1.00 | 0.67 / 1.00 | — / 1.00 |
| robot-shop | 18 | 18 | 0 | 3 | 1.00 | 0.86 | 1.00 / 0.40 | 1.00 / 1.00 | — / 1.00 |
| teastore | 1 | 1 | 0 | 12 | 1.00 | 0.08 | — / 0.00 | 1.00 / 1.00 | — / 0.00 |

## bank-of-anthos

漏（truth 有、工具沒畫）：
- loadgenerator -> frontend  ·  src/loadgenerator/Dockerfile:49 ENTRYPOINT locust --host="http://${FRONTEND_ADDR}"；FRONTEND_ADDR 的值在 src/loadgenerator/k8s/base/loadgenerator.yaml:50（host 在命令列參數裡，不在 locustfile.py）

## ecommerce

漏（truth 有、工具沒畫）：
- api-gateway -> cloud-config  ·  api-gateway/src/main/resources/application.yml（每個服務都有同型設定） spring.config.import=configserver:（預設 localhost）
- api-gateway -> service-discovery  ·  README「service registry (Eureka)」；api-gateway/src/main/resources/application.yml（每個服務都有同型設定） eureka.client（主機名只在缺席的 k8s 目錄）
- cloud-config -> service-discovery  ·  同上
- favourite-service -> cloud-config  ·  同上
- favourite-service -> service-discovery  ·  同上
- order-service -> cloud-config  ·  同上
- order-service -> service-discovery  ·  同上
- payment-service -> cloud-config  ·  同上
- payment-service -> service-discovery  ·  同上
- product-service -> cloud-config  ·  同上
- product-service -> service-discovery  ·  同上
- proxy-client -> cloud-config  ·  同上
- proxy-client -> service-discovery  ·  同上
- service-discovery -> cloud-config  ·  同上
- shipping-service -> cloud-config  ·  同上
- shipping-service -> service-discovery  ·  同上
- user-service -> cloud-config  ·  同上
- user-service -> service-discovery  ·  同上

## ewolff-k8s

全對。

## online-boutique

對的裡面有 1 條標 inferred（名字推的，不是表查到的）。

漏（truth 有、工具沒畫）：
- checkoutservice -> cartservice  ·  src/checkoutservice/main.go:113 CART_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:67
- checkoutservice -> currencyservice  ·  src/checkoutservice/main.go:114 CURRENCY_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:65
- checkoutservice -> emailservice  ·  src/checkoutservice/main.go:115 EMAIL_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:63
- checkoutservice -> paymentservice  ·  src/checkoutservice/main.go:116 PAYMENT_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:61
- checkoutservice -> productcatalogservice  ·  src/checkoutservice/main.go:112 PRODUCT_CATALOG_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:57
- checkoutservice -> shippingservice  ·  src/checkoutservice/main.go:111 SHIPPING_SERVICE_ADDR；kubernetes-manifests/checkoutservice.yaml:59
- frontend -> adservice  ·  src/frontend/main.go:138 mustMapEnv(&svc.adSvcAddr, "AD_SERVICE_ADDR")；值在 kubernetes-manifests/frontend.yaml:82 value: "adservice:9555"
- frontend -> cartservice  ·  src/frontend/main.go:134 CART_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:74 value: "cartservice:7070"
- frontend -> checkoutservice  ·  src/frontend/main.go:136 CHECKOUT_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:80 value: "checkoutservice:5050"
- frontend -> currencyservice  ·  src/frontend/main.go:133 CURRENCY_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:72 value: "currencyservice:7000"
- frontend -> productcatalogservice  ·  src/frontend/main.go:132 PRODUCT_CATALOG_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:70 value: "productcatalogservice:3550"
- frontend -> recommendationservice  ·  src/frontend/main.go:135 RECOMMENDATION_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:76 value: "recommendationservice:8080"
- frontend -> shippingservice  ·  src/frontend/main.go:137 SHIPPING_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:78 value: "shippingservice:50051"
- loadgenerator -> frontend  ·  kubernetes-manifests/loadgenerator.yaml:82 FRONTEND_ADDR，值 "frontend:80"（locust 的 --host 由它組成，不在 locustfile.py 裡）

## piggymetrics

錯（工具畫了、truth 沒有）：
- auth-service -> rabbitmq  (documented)
- gateway -> rabbitmq  (documented)
- monitoring -> auth-service  (documented)
- monitoring -> rabbitmq  (documented)
- registry -> auth-service  (documented)
- registry -> rabbitmq  (documented)
- turbine-stream-service -> auth-service  (documented)

## robot-shop

對的裡面有 2 條標 inferred（名字推的，不是表查到的）。

漏（truth 有、工具沒畫）：
- cart -> catalogue  ·  cart/server.js:362 request('http://' + catalogueHost + ':8080/product/')
- ratings -> catalogue  ·  ratings/html/src/Service/CatalogueService.php:27 sprintf('%s/product/%s', $this->catalogueUrl)（host 由 config/services.yaml 的 CATALOGUE_URL 注入）
- shipping -> cart  ·  shipping/src/main/java/com/instana/robotshop/shipping/Controller.java:24 String.format("http://%s/shipping/", getenv("CART_ENDPOINT","cart"))

## teastore

漏（truth 有、工具沒畫）：
- teastore-auth -> teastore-persistence  ·  services/tools.descartes.teastore.auth/src/main/java/tools/descartes/teastore/auth/rest/AuthUserActionsRest.java:96 sendEntityForCreation(Service.PERSISTENCE（auth 內 5 處）；目標由 registry 執行期解析
- teastore-auth -> teastore-registry  ·  同上
- teastore-image -> teastore-persistence  ·  services/tools.descartes.teastore.image/src/main/java/tools/descartes/teastore/image/setup/SetupController.java:158 loadBalanceRESTOperation(Service.PERSISTENCE（image 內 5 處）；目標由 registry 執行期解析
- teastore-image -> teastore-registry  ·  同上
- teastore-persistence -> teastore-registry  ·  同上
- teastore-recommender -> teastore-persistence  ·  services/tools.descartes.teastore.recommender/src/main/java/tools/descartes/teastore/recommender/servlet/TrainingSynchronizer.java:138 loadBalanceRESTOperation(Service.PERSISTENCE（recommender 內 6 處）；目標由 registry 執行期解析
- teastore-recommender -> teastore-registry  ·  同上
- teastore-webui -> teastore-auth  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:71 Service.AUTH（webui 內 2 處）；業務呼叫在共用模組 utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/rest/LoadBalancedStoreOperations.java:56 對 Service.AUTH 發 REST，目標由 registry 執行期解析
- teastore-webui -> teastore-image  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:110 multicastRESTOperation(Service.IMAGE（webui 內 3 處）；目標由 registry 執行期解析
- teastore-webui -> teastore-persistence  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:67 loadBalanceRESTOperation(Service.PERSISTENCE（webui 內 21 處）；目標由 registry 執行期解析
- teastore-webui -> teastore-recommender  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:84 multicastRESTOperation(Service.RECOMMENDER（webui 內 4 處）；目標由 registry 執行期解析
- teastore-webui -> teastore-registry  ·  GET_STARTED「Services register at a separate simple registry」；REGISTRY_HOST env
