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
- loadgenerator -> frontend  ·  src/loadgenerator/locustfile.py（FRONTEND_ADDR）

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
- checkoutservice -> cartservice  ·  架構圖
- checkoutservice -> currencyservice  ·  架構圖
- checkoutservice -> emailservice  ·  架構圖
- checkoutservice -> paymentservice  ·  架構圖
- checkoutservice -> productcatalogservice  ·  架構圖
- checkoutservice -> shippingservice  ·  架構圖
- frontend -> adservice  ·  README docs/img/architecture-diagram.png（frontend → ad）
- frontend -> cartservice  ·  架構圖
- frontend -> checkoutservice  ·  架構圖
- frontend -> currencyservice  ·  架構圖
- frontend -> productcatalogservice  ·  架構圖
- frontend -> recommendationservice  ·  架構圖
- frontend -> shippingservice  ·  架構圖
- loadgenerator -> frontend  ·  架構圖（loadgenerator → frontend）

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
- teastore-auth -> teastore-persistence  ·  auth：Service.PERSISTENCE 引用 5 處
- teastore-auth -> teastore-registry  ·  同上
- teastore-image -> teastore-persistence  ·  image：Service.PERSISTENCE 引用 5 處
- teastore-image -> teastore-registry  ·  同上
- teastore-persistence -> teastore-registry  ·  同上
- teastore-recommender -> teastore-persistence  ·  recommender：Service.PERSISTENCE 引用 6 處
- teastore-recommender -> teastore-registry  ·  同上
- teastore-webui -> teastore-auth  ·  services/tools.descartes.teastore.webui：Service.AUTH 引用 2 處（loadBalanceRESTOperation）
- teastore-webui -> teastore-image  ·  webui：Service.IMAGE 引用 3 處
- teastore-webui -> teastore-persistence  ·  webui：Service.PERSISTENCE 引用 21 處
- teastore-webui -> teastore-recommender  ·  webui：Service.RECOMMENDER 引用 4 處
- teastore-webui -> teastore-registry  ·  GET_STARTED「Services register at a separate simple registry」；REGISTRY_HOST env
