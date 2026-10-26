# Hold-out set C：靜態 greenfield 圖 vs 參考邊集（程式計分，r1）

由 `docs/generalization/holdout-c/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r1.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| run-aspnetcore-microservices | 13 | 0 | 13 | 11 | 0.00 | 0.00 | 0.00 / 0.00 | — / 0.00 | — / 0.00 | — | 0 |
| yas | 77 | 45 | 32 | 32 | 0.58 | 0.58 | 0.06 / 0.11 | 0.80 / 0.63 | — / 0.79 | — | 12 |
| **合計** | 90 | 45 | 45 | 43 | 0.50 | 0.51 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## run-aspnetcore-microservices

參考邊集：business 2、data 6、control 3（可計分 11）＋ variant 0。


錯（工具畫了、truth 沒有，13 條）：

- basket -> basketdb  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：compose 服務名 basket.api 被在點截斷成 basket（ConfigExtractor.hostOf:490、CodeGraphMerger:1676 的 k8s DNS 短名規則對任何含點的名字生效）；邊本身對（參考邊集 basket.api -> basketdb）
- basket -> discount  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上；discount.grpc → discount。邊本身對（basket.api -> discount.grpc，來源是 compose override 的 GrpcSettings__DiscountUrl=https://discount.grpc:8081）
- basket -> distributedcache  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（basket.api 改名）；邊本身對
- basket -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml  ·  **規則邊界**：src/docker-compose.override.yml:45 `hostname: ecommerce-mq` 是 messagebroker 的容器主機名；:77 MessageBroker__Host=amqp://ecommerce-mq:5672 用的是這個別名。工具不讀 compose 的 hostname: 鍵，別名變成獨立節點（與 mall-swarm 的 external_links 別名同一家族）
- basket -> messagebroker  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（basket.api 改名）；邊本身對（來自 depends_on messagebroker）
- catalog -> catalogdb  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（catalog.api → catalog）；邊本身對
- ordering -> ecommerce-mq  (sync-http, inferred)  code: src/docker-compose.override.yml  ·  **規則邊界**：同上（:111）
- ordering -> messagebroker  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（ordering.api → ordering）；邊本身對
- ordering -> orderdb  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（ordering.api → ordering）；邊本身對
- shopping -> yarpapigateway  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（shopping.web → shopping）；邊本身對（ApiSettings__GatewayAddress=http://yarpapigateway:8080）
- yarpapigateway -> basket  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（目標 basket.api → basket）；邊本身對。注意來源是 compose depends_on，不是 appsettings.json 的 ReverseProxy.Clusters（C# JSON 設定沒解析）
- yarpapigateway -> catalog  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（catalog.api → catalog）；邊本身對，來源同為 depends_on
- yarpapigateway -> ordering  (sync-http, documented)  code: src/docker-compose.override.yml  ·  **bug**：同上（ordering.api → ordering）；邊本身對，來源同為 depends_on

漏（truth 有、工具沒畫，11 條）：

- basket.api -> discount.grpc  ·  src/Services/Basket/Basket.API/Basket/StoreBasket/StoreBasketHandler.cs:35 discountProto.GetDiscountAsync(...)（gRPC client 於 src/Services/Basket/Basket.API/Program.cs:39 options.Address = GrpcSettings:DiscountUrl；src/docker-compose.override.yml:76 GrpcSettings__DiscountUrl=https://discount.grpc:8081）  ·  **bug**：畫成 basket -> discount（名字在點截斷，見上）
- shopping.web -> yarpapigateway  ·  src/WebApps/Shopping.Web/Program.cs:9 c.BaseAddress = ApiSettings:GatewayAddress（Refit ICatalogService/IBasketService/IOrderingService 三個 client 同址 :9/:14/:19；src/docker-compose.override.yml:146 ApiSettings__GatewayAddress=http://yarpapigateway:8080）  ·  **bug**：畫成 shopping -> yarpapigateway
- catalog.api -> catalogdb  ·  src/Services/Catalog/Catalog.API/Program.cs:20 opts.Connection(GetConnectionString("Database"))（Marten/PostgreSQL；src/docker-compose.override.yml:59 ConnectionStrings__Database=Server=catalogdb;Port=5432;…）  ·  **bug**：畫成 catalog -> catalogdb
- basket.api -> basketdb  ·  src/Services/Basket/Basket.API/Program.cs:23 opts.Connection(GetConnectionString("Database"))（Marten/PostgreSQL；src/docker-compose.override.yml:74 Server=basketdb）  ·  **bug**：畫成 basket -> basketdb
- basket.api -> distributedcache  ·  src/Services/Basket/Basket.API/Data/CachedBasketRepository.cs:12 cache.GetStringAsync（IDistributedCache；src/Services/Basket/Basket.API/Program.cs:32 AddStackExchangeRedisCache Configuration=ConnectionStrings:Redis；src/docker-compose.override.yml:75 ConnectionStrings__Redis=distributedcache:6379）  ·  **bug**：畫成 basket -> distributedcache
- basket.api -> messagebroker  ·  src/Services/Basket/Basket.API/Basket/CheckoutBasket/CheckoutBasketHandler.cs:41 publishEndpoint.Publish(eventMessage)（MassTransit；src/BuildingBlocks/BuildingBlocks.Messaging/MassTransit/Extentions.cs:21 configurator.Host(MessageBroker:Host)；src/docker-compose.override.yml:77 MessageBroker__Host=amqp://ecommerce-mq:5672，ecommerce-mq＝messagebroker 的 hostname :45）  ·  **bug**：畫成 basket -> messagebroker（另外多畫了 basket -> ecommerce-mq）
- ordering.api -> orderdb  ·  src/Services/Ordering/Ordering.Infrastructure/DependencyInjection.cs:21 options.UseSqlServer(connectionString)（EF Core；src/docker-compose.override.yml:110 ConnectionStrings__Database=Server=orderdb;Database=OrderDb;…）  ·  **bug**：畫成 ordering -> orderdb
- ordering.api -> messagebroker  ·  src/Services/Ordering/Ordering.Application/Orders/EventHandlers/Integration/BasketCheckoutEventHandler.cs:10 Consume(ConsumeContext<BasketCheckoutEvent>)（MassTransit consumer，src/Services/Ordering/Ordering.Application/DependencyInjection.cs:22 AddMessageBroker(…, Assembly)；src/docker-compose.override.yml:111 MessageBroker__Host=amqp://ecommerce-mq:5672）  ·  **bug**：畫成 ordering -> messagebroker
- yarpapigateway -> catalog.api  ·  src/ApiGateways/YarpApiGateway/appsettings.json:38 Clusters.catalog-cluster Address http://catalog.api:8080（route :14 /catalog-service/{**catch-all}）  ·  **bug**：畫成 yarpapigateway -> catalog
- yarpapigateway -> basket.api  ·  src/ApiGateways/YarpApiGateway/appsettings.json:45 Clusters.basket-cluster Address http://basket.api:8080（route :21 /basket-service/）  ·  **bug**：畫成 yarpapigateway -> basket
- yarpapigateway -> ordering.api  ·  src/ApiGateways/YarpApiGateway/appsettings.json:52 Clusters.ordering-cluster Address http://ordering.api:8080（route :29 /ordering-service/）  ·  **bug**：畫成 yarpapigateway -> ordering

## yas

參考邊集：business 19、data 19、control 39（可計分 77）＋ variant 40。


錯（工具畫了、truth 沒有，32 條）：

- backoffice -> storefront  (sync-http, documented)  code: automation-ui/backoffice/src/main/resources/application.properties  ·  **規則邊界**：automation-ui/backoffice/src/main/resources/application.properties:2 app.url=http://storefront/ 是 Selenium 自動化測試專案（automation-ui）的設定，它的子模組目錄剛好叫 backoffice／storefront，撞到 compose 服務名；TEST_DIR 規則只認結尾 test(s)/e2e/spec 的目錄名，不認 automation-ui
- backoffice-bff -> api  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-dev.yaml  ·  **規則邊界**：api＝http://api.yas.local 在第一個點截斷（hostOf:490）；api.yas.local 是 nginx/templates/default.conf.template:2 的 server_name，不是 workload。另外來源是 application-dev.yaml（dev profile）
- backoffice-bff -> backoffice-nextjs  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml  ·  **規則邊界**：邊對（參考邊集 backoffice -> backoffice-nextjs），但來源用了 Maven 模組名 backoffice-bff 而不是 compose 服務名 backoffice（docker-compose.yml:30–32 build: ./backoffice-bff）；工具有提出別名問題（alias questions：backoffice-bff ≈ backoffice），probe 沒答
- backoffice-bff -> identity  (sync-http, documented)  code: backoffice-bff/src/main/resources/application.yaml  ·  **規則邊界**：同上（別名未答）；邊本身對
- backoffice-bff -> nginx  (sync-http, documented)  code: backoffice-bff/src/main/resources/application-prod.yaml  ·  **規則邊界**：同上（別名未答）；邊本身對
- backoffice-bff -> order  (sync-http, inferred)  code: backoffice-bff/src/main/resources/application-dev.yaml  ·  **規則邊界**：backoffice-bff/src/main/resources/application-dev.yaml:11 的 dev 路由 uri: http://localhost:8085（註解寫「local development should not be committed」），loopback 埠號反查到 server.port=8085 的 order 模組（inferred）；dev profile 與 prod profile 被一視同仁
- backoffice-bff -> redis  (db, inferred)  code: backoffice-bff/src/main/resources/application.yaml  ·  **規則邊界**：同別名未答；邊本身對（backoffice -> redis）
- cart -> api  (sync-http, documented)  code: cart/src/main/resources/application.properties  ·  **規則邊界**：cart/src/main/resources/application.properties:10–11 yas.services.*=http://api.yas.local/… 的預設值；compose 覆蓋值在 .env 沒接上（見漏的 cart -> product），假節點 api 取代了真目標
- common-library -> identity  (sync-http, documented)  code: common-library/src/main/resources/application.properties  ·  **規則邊界**：common-library 是共用程式庫模組（被各服務當 dependency），不是 workload；它的 application.properties 只給自己的測試用。工具把任何有 pom.xml＋application.properties 的目錄當服務根
- customer -> api  (sync-http, documented)  code: customer/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（customer/src/main/resources/application.properties:15）
- delivery -> identity  (sync-http, documented)  code: delivery/src/main/resources/application.properties  ·  **bug**：delivery 沒有任何 manifest，而且 delivery/src/main/resources/application.properties 裡指到 identity 的只有 :10–11 springdoc.oauthflow.authorization-url/token-url——Swagger UI 瀏覽器端的 OAuth 流程網址，不是服務呼叫（沒有 issuer-uri、pom 沒有 oauth2 依賴）；與 mall-swarm 的 SpringDocConfig URL 同一類假邊
- inventory -> api  (sync-http, documented)  code: inventory/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（inventory/src/main/resources/application.properties:15–16）
- kafka-ui -> kafka-connect  (async, documented)  code: docker-compose.yml  ·  **規則邊界**：只有 docker-compose.yml:592 depends_on；kafka-ui 沒有任何指到 kafka-connect 的設定（KAFKA_CLUSTERS_0_KAFKACONNECT_* 不存在）。depends_on 是啟動順序不是呼叫
- media -> api  (sync-http, inferred)  code: yaslocal.yaml  ·  **規則邊界**：yaslocal.yaml:287 YAS_PUBLIC_URL=http://api.yas.local/media 是 media 自己對外的公開網址（給回傳 URL 用），不是呼叫；又被截成 api
- order -> api  (sync-http, documented)  code: order/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（order/src/main/resources/application.properties:14–18；其中 promotion 連 compose 都沒覆蓋）
- payment -> api  (sync-http, documented)  code: payment/src/it/java/com/yas/payment/service/MediaServiceIT.java:42  ·  **規則邊界**：來源是整合測試 payment/src/it/java/com/yas/payment/service/MediaServiceIT.java:42 的字串 "http://api.yas.local/medias"；src/it（Maven failsafe 慣例）不在排除規則裡，TEST_DIR 只認目錄名結尾 test(s)
- payment -> storefront  (sync-http, documented)  code: payment/src/it/resources/application.properties  ·  **規則邊界**：payment/src/it/resources/application.properties:11（main 的 :17 同）yas.public.url=http://storefront/complete-payment 是付款完成後把瀏覽器導回 storefront 的網址，不是 payment 發出的呼叫；鍵名以 .url 結尾就被收
- payment-paypal -> storefront  (sync-http, documented)  code: payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34  ·  **規則邊界**：payment-paypal/src/main/java/com/yas/payment/paypal/service/PaypalService.java:34 @Value("${yas.public.url}/capture") 是交給 PayPal 的 return_url／cancel_url（瀏覽器導回），同上；且 payment-paypal 只在 Helm 有
- product -> api  (sync-http, documented)  code: product/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（product/src/main/resources/application.properties:15–16）
- promotion -> api  (sync-http, documented)  code: promotion/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（promotion/src/main/resources/application.properties:15）
- rating -> api  (sync-http, documented)  code: rating/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（rating/src/main/resources/application.properties:15–17）
- recommendation -> api  (sync-http, documented)  code: recommendation/src/it/resources/application.properties  ·  **規則邊界**：同 cart -> api，且來源是 recommendation/src/it/resources（整合測試資源）；recommendation 只在 Helm 有
- search -> api  (sync-http, documented)  code: search/src/it/resources/application.properties  ·  **規則邊界**：同 cart -> api，來源是 search/src/it/resources/application.properties（整合測試資源）
- storefront-bff -> api  (sync-http, documented)  code: storefront-bff/src/main/resources/application-dev.yaml  ·  **規則邊界**：同 backoffice-bff -> api（storefront-bff/src/main/resources/application-dev.yaml:18）
- storefront-bff -> identity  (sync-http, documented)  code: storefront-bff/src/main/resources/application.yaml  ·  **規則邊界**：別名未答（storefront-bff ≈ storefront）；邊本身對
- storefront-bff -> nginx  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml  ·  **規則邊界**：別名未答；邊本身對
- storefront-bff -> payment  (sync-http, inferred)  code: storefront-bff/src/main/resources/application-dev.yaml  ·  **規則邊界**：storefront-bff/src/main/resources/application-dev.yaml:11 dev 路由 http://localhost:8081 → server.port=8081 的 payment（inferred），同 backoffice-bff -> order
- storefront-bff -> redis  (db, inferred)  code: storefront-bff/src/main/resources/application.yaml  ·  **規則邊界**：別名未答；邊本身對
- storefront-bff -> storefront-nextjs  (sync-http, documented)  code: storefront-bff/src/main/resources/application-prod.yaml  ·  **規則邊界**：別名未答；邊本身對
- swagger-ui -> api  (sync-http, inferred)  code: yaslocal.yaml  ·  **規則邊界**：yaslocal.yaml:253 URLS=[{ url: 'http://api.yas.local/product/v3/api-docs' …}] 是 Swagger UI 在瀏覽器端載入的 OpenAPI 文件網址，不是容器發出的呼叫；又被截成 api
- tax -> api  (sync-http, documented)  code: tax/src/main/resources/application.properties  ·  **規則邊界**：同 cart -> api（tax/src/main/resources/application.properties:14）
- webhook -> api  (sync-http, documented)  code: webhook/src/main/resources/application.properties  ·  **規則邊界**：webhook/src/main/resources/application.properties:14 yas.services.webhook=http://api.yas.local/webhook 是 webhook 自己的公開網址；截成 api；webhook 只在 Helm 有

對的裡面有 2 條標 inferred（名字推的，不是表查到的）。


漏（truth 有、工具沒畫，32 條）：

- cart -> product  ·  cart/src/main/java/com/yas/cart/service/ProductService.java:31 restClient.get()（:26 fromUriString(serviceUrlConfig.product())；docker-compose.yml:189 YAS_SERVICES_PRODUCT；.env:74 http://product/product）  ·  **規則邊界**：docker-compose.yml:189 只寫 `- YAS_SERVICES_PRODUCT`（鍵名），值在 .env:74；ConfigExtractor.extractComposeEnvironment 只收 K=V 形式，.env 讀進來的列沒有來源服務（unresolved：`null => http://product/product @ .env:-1`）。與第一組 hold-out 的 shipping -> quote、load-generator -> frontend-proxy 同一邊界；application.properties 的預設值則被畫成 cart -> api
- customer -> location  ·  customer/src/main/java/com/yas/customer/service/LocationService.java:39 restClient.get()（:33 serviceUrlConfig.location()；docker-compose.yml:161 YAS_SERVICES_LOCATION；.env:78 http://location/location）  ·  **規則邊界**：同上（docker-compose.yml:161；.env:78）
- inventory -> product  ·  inventory/src/main/java/com/yas/inventory/service/ProductService.java:40 restClient.get()（:36 serviceUrlConfig.product()；docker-compose.yml:338）  ·  **規則邊界**：同上（docker-compose.yml:338）
- inventory -> location  ·  inventory/src/main/java/com/yas/inventory/service/LocationService.java:35 restClient.get()（:30 serviceUrlConfig.location()；docker-compose.yml:339）  ·  **規則邊界**：同上（docker-compose.yml:339）
- order -> cart  ·  order/src/main/java/com/yas/order/service/CartService.java:41 restClient.post()（:36 serviceUrlConfig.cart()；docker-compose.yml:250；.env:72 http://cart/cart）  ·  **規則邊界**：同上（docker-compose.yml:250）
- order -> customer  ·  order/src/main/java/com/yas/order/service/CustomerService.java:31 restClient.get()（:27 serviceUrlConfig.customer()；docker-compose.yml:251）  ·  **規則邊界**：同上（docker-compose.yml:251）
- order -> product  ·  order/src/main/java/com/yas/order/service/ProductService.java:44 restClient.get()（:39 serviceUrlConfig.product()；docker-compose.yml:252）  ·  **規則邊界**：同上（docker-compose.yml:252）
- order -> tax  ·  order/src/main/java/com/yas/order/service/TaxService.java:33 webClient.get()（:23 serviceUrlConfig.tax()；docker-compose.yml:253；.env:75 http://tax/tax）  ·  **規則邊界**：同上（docker-compose.yml:253）
- order -> promotion  ·  order/src/main/java/com/yas/order/service/PromotionService.java:34 restClient.post()（:29 serviceUrlConfig.promotion()；compose 沒傳 YAS_SERVICES_PROMOTION，值停在 order/src/main/resources/application.properties:18 http://api.yas.local/promotion ＝ nginx 的 server_name）  ·  **規則邊界**：compose 連鍵名都沒傳，值停在 order/src/main/resources/application.properties:18 http://api.yas.local/promotion；要畫到得知道 api.yas.local 是 nginx 的 server_name 再沿 nginx 的 /promotion/ 路由走，工具沒有 server_name 規則
- payment -> order  ·  payment/src/main/java/com/yas/payment/service/OrderService.java:39 restClient.put()（:32 serviceUrlConfig.order()；docker-compose.yml:280；.env:76 http://order/order）  ·  **規則邊界**：同 cart -> product（docker-compose.yml:280）
- payment -> media  ·  payment/src/main/java/com/yas/payment/service/MediaService.java:47 restClient.get()（:67 fromUriString(serviceUrlConfig.media())；docker-compose.yml:281；.env:79 http://media/media）  ·  **規則邊界**：同上（docker-compose.yml:281）
- promotion -> product  ·  promotion/src/main/java/com/yas/promotion/service/ProductService.java:40 restClient.get()（:34 serviceUrlConfig.product()；docker-compose.yml:367）  ·  **規則邊界**：同上（docker-compose.yml:367）
- rating -> customer  ·  rating/src/main/java/com/yas/rating/service/CustomerService.java:32 restClient.get()（:28 serviceUrlConfig.customer()；docker-compose.yml:218；.env:73 http://customer/customer）  ·  **規則邊界**：同上（docker-compose.yml:218）
- rating -> order  ·  rating/src/main/java/com/yas/rating/service/OrderService.java:33 restClient.get()（:28 serviceUrlConfig.order()；docker-compose.yml:219）  ·  **規則邊界**：同上（docker-compose.yml:219）
- search -> product  ·  search/src/main/java/com/yas/search/service/ProductSyncDataService.java:30 restClient.get()（:29 serviceUrlConfig.product()；docker-compose.search.yml:11 YAS_SERVICES_PRODUCT）  ·  **規則邊界**：同上（docker-compose.search.yml:11）
- tax -> location  ·  tax/src/main/java/com/yas/tax/service/LocationService.java:31 restClient.get()（:26 serviceUrlConfig.location()；docker-compose.yml:395）  ·  **規則邊界**：同上（docker-compose.yml:395）
- storefront-nextjs -> storefront  ·  storefront/modules/catalog/services/ProductService.ts:21 apiClientService.get(`${serverSideRenderUrl}/product/${slug}`)（SSR 端；:13 serverSideRenderUrl=${process.env.API_BASE_PATH}/product/storefront；storefront/.env.production:1 API_BASE_PATH=http://storefront/api）  ·  **缺文法**：Next.js（TypeScript）沒有 pattern（stacks 顯示 javascript LLM tier skipped）；位址 API_BASE_PATH=http://storefront/api 在 storefront/.env.production:1，也不是工具會讀的檔
- order -> postgres  ·  order/src/main/java/com/yas/order/repository/CheckoutItemRepository.java:9 JpaRepository（docker-compose.yml:244 jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/order；.env:41 POSTGRES_HOST=postgres）  ·  **規則邊界**：docker-compose.yml:244 SPRING_DATASOURCE_URL=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/order 用 compose 變數內插（值在 .env:41–42）；hostOf 遇到 $ 回 null。其他 11 個服務寫死 postgres:5432 就都畫到了
- identity -> postgres  ·  docker-compose.yml:21 KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak（Keycloak 官方映像，沒有程式碼）  ·  **規則邊界**：docker-compose.yml:21 KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak（mapping 形式 environment）沒畫出；工具改畫了 keycloak -> postgres（yaslocal.yaml:378 的過時 k8s Deployment）。推測：yaslocal.yaml 的 Service identity（selector app: keycloak）＋ Deployment keycloak 讓 identity 被當成 k8s 定義的 workload，compose 的 environment 沒有接到 identity 上
- backoffice -> redis  ·  backoffice-bff/src/main/resources/application.yaml:28 spring.session.store-type: redis（Spring Session；:25 spring.data.redis.host: redis）  ·  **規則邊界**：畫成 backoffice-bff -> redis（別名未答）
- storefront -> redis  ·  storefront-bff/src/main/resources/application.yaml:28 spring.session.store-type: redis（:25 spring.data.redis.host: redis）  ·  **規則邊界**：畫成 storefront-bff -> redis（別名未答）
- search -> elasticsearch  ·  search/src/main/java/com/yas/search/config/ImperativeClientConfig.java:21 connectedTo(elasticsearchConfig.getUrl())（search/src/main/java/com/yas/search/repository/ProductRepository.java:8 ElasticsearchRepository；search/src/main/resources/application.properties:1 elasticsearch.url=elasticsearch；docker-compose.search.yml:7 wait-for-it elasticsearch:9200）  ·  **規則邊界**：search/src/main/resources/application.properties:1 elasticsearch.url=elasticsearch——值是裸主機名（沒有 scheme 也沒有 port），addressHost 只收 address-shaped 的值或 _HOST/_URL 這類環境變數名；鍵名 elasticsearch.url 兩邊都不符（與 mall-swarm 的 aliyun.oss.endpoint 同形）
- search -> kafka  ·  search/src/main/java/com/yas/search/kafka/consumer/ProductSyncDataConsumer.java:34 @KafkaListener(topics=${product.topic.name})（consumer；search/src/main/resources/application.properties:28 spring.kafka.consumer.bootstrap-servers=kafka:9092、:30 dbproduct.public.product）  ·  **bug**：search/src/main/resources/application.properties:28 spring.kafka.consumer.bootstrap-servers=kafka:9092 符合 isRelevant（spring.kafka 前綴）且值 address-shaped，卻沒有畫成 search -> kafka；工具反而去解 @KafkaListener 的 topics="${product.topic.name}"（unresolved 列出），而同一個 properties 檔的 :30 就有 product.topic.name。webhook 的 spring.kafka.bootstrap-servers=kafka:9092 同樣沒畫（variant）
- kafka-connect -> postgres  ·  kafka/connects/debezium-product.json:7 database.hostname: postgres（Debezium PostgresConnector 讀 WAL；kafka/connects/debezium-order.json:7 同；由 start-source-connectors.sh:2–8 PUT 進 kafka-connect）  ·  **框架慣例**：Debezium source connector 的設定是 kafka/connects/debezium-*.json 的 database.hostname（由 start-source-connectors.sh 用 curl PUT 進 kafka-connect），不是任何工具認得的設定檔格式
- nginx -> search  ·  nginx/templates/default.conf.template:43 proxy_pass http://$docker_search_host（:42 set $docker_search_host "search"）  ·  **規則邊界**：nginx/templates/default.conf.template:42–43 `set $docker_search_host "search"; proxy_pass http://$docker_search_host;`——nginx 變數間接（為了避免啟動時解析不到），PROXY_DIRECTIVE 收到的是 $docker_search_host（unresolved 有列）
- nginx -> elasticsearch  ·  nginx/templates/default.conf.template:117 proxy_pass http://$docker_elasticsearch_host:9200（:116 set … "elasticsearch"）  ·  **規則邊界**：同上（:116–117 $docker_elasticsearch_host）
- backoffice -> nginx  ·  backoffice-bff/src/main/resources/application-prod.yaml:10 spring.cloud.gateway route api uri: http://nginx（Path=/api/**）  ·  **規則邊界**：畫成 backoffice-bff -> nginx（別名未答）。compose 的 build: ./backoffice-bff ＋ image yas-backoffice-bff 其實已經把模組目錄接到服務名 backoffice
- backoffice -> backoffice-nextjs  ·  backoffice-bff/src/main/resources/application-prod.yaml:18 route nextjs uri: http://backoffice-nextjs:3000  ·  **規則邊界**：畫成 backoffice-bff -> backoffice-nextjs（別名未答）
- storefront -> nginx  ·  storefront-bff/src/main/resources/application-prod.yaml:10 route api uri: http://nginx（Path=/api/**）  ·  **規則邊界**：畫成 storefront-bff -> nginx（別名未答）
- storefront -> storefront-nextjs  ·  storefront-bff/src/main/resources/application-prod.yaml:18 route nextjs uri: http://storefront-nextjs:3000  ·  **規則邊界**：畫成 storefront-bff -> storefront-nextjs（別名未答）
- backoffice -> identity  ·  backoffice-bff/src/main/resources/application.yaml:16 spring.security.oauth2.client.provider.keycloak.issuer-uri: http://identity/realms/Yas（OAuth2 code flow）  ·  **規則邊界**：畫成 backoffice-bff -> identity（別名未答）
- storefront -> identity  ·  storefront-bff/src/main/resources/application.yaml:16 spring.security.oauth2.client.provider.keycloak.issuer-uri: http://identity/realms/Yas  ·  **規則邊界**：畫成 storefront-bff -> identity（別名未答）

工具另外畫到的 variant 邊（不計分，12 條）：

- backoffice -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:221 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- collector -> prometheus  (sync-http, documented)  ·  docker/otel-collector/otelcol-config.yml:27 prometheusremotewrite endpoint http://prometheus:9090/api/v1/write（遙測）
- collector -> tempo  (sync-http, documented)  ·  docker/otel-collector/otelcol-config.yml:31 otlp endpoint tempo:4317（遙測）
- kafka-ui -> kafka  (async, documented)  ·  docker-compose.yml:587 KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092（管理工具）
- keycloak -> postgres  (db, inferred)  ·  yaslocal.yaml:378 DB_ADDR value: "postgres"（過時的 k8s manifest 把 Keycloak 16 的 Deployment 叫 keycloak；compose 裡同一個東西叫 identity，見 data 段 identity → postgres）
- media -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:291 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- payment-paypal -> identity  (sync-http, documented)  ·  payment-paypal/src/main/resources/application.properties:13 jwt.issuer-uri=http://identity/realms/Yas（只有 Helm）
- product -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:325 value: http://jaeger:4317（過時的 k8s manifest；遙測）
- recommendation -> identity  (sync-http, documented)  ·  recommendation/src/main/resources/application.properties:41 jwt.issuer-uri=http://identity/realms/Yas
- storefront -> jaeger  (sync-http, inferred)  ·  yaslocal.yaml:186 OTEL_EXPORTER_OTLP_ENDPOINT value: http://jaeger:4317（過時的 k8s manifest；遙測）
- webhook -> identity  (sync-http, documented)  ·  webhook/src/main/resources/application.properties:16 jwt.issuer-uri=http://identity/realms/Yas
- webhook -> postgres  (db, documented)  ·  webhook/src/main/java/com/yas/webhook/repository/WebhookRepository.java:8 JpaRepository（Helm：k8s/charts/backend/values.yaml:41 jdbc:postgresql://postgresql.postgres:5432）
