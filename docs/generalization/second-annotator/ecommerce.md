# 第二標註者：ecommerce

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 26357 token；比對規則更新後以 `-Dannotate.replay=true` 重算，未重新呼叫 API）。標註者**沒有看過** `truth/ecommerce.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 13 |
| 只有作者的 truth 有 | 25 |
| 只有第二標註者有 | 23 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.21** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- api-gateway -> proxy-client  ·  api-gateway/src/main/resources/application.yml routes（lb://PROXY-CLIENT）
- api-gateway -> user-service  ·  api-gateway/src/main/resources/application.yml routes
- api-gateway -> product-service  ·  api-gateway/src/main/resources/application.yml routes
- api-gateway -> order-service  ·  api-gateway/src/main/resources/application.yml routes
- api-gateway -> payment-service  ·  api-gateway/src/main/resources/application.yml routes
- api-gateway -> shipping-service  ·  api-gateway/src/main/resources/application.yml routes
- api-gateway -> favourite-service  ·  api-gateway/src/main/resources/application.yml routes
- proxy-client -> product-service  ·  proxy-client/src/main/java/com/selimhorri/app/business/product/service/ProductClientService.java:19
- proxy-client -> payment-service  ·  proxy-client/src/main/java/com/selimhorri/app/business/payment/service/PaymentClientService.java:19
- cloud-config -> github.com  ·  cloud-config/src/main/resources/application.yml（spring.cloud.config.server.git.uri）
- proxy-client -> service-discovery  ·  同上
- product-service -> service-discovery  ·  同上
- order-service -> service-discovery  ·  同上
- payment-service -> service-discovery  ·  同上
- shipping-service -> service-discovery  ·  同上
- favourite-service -> service-discovery  ·  同上
- cloud-config -> service-discovery  ·  同上
- proxy-client -> cloud-config  ·  同上
- user-service -> cloud-config  ·  同上
- product-service -> cloud-config  ·  同上
- order-service -> cloud-config  ·  同上
- payment-service -> cloud-config  ·  同上
- shipping-service -> cloud-config  ·  同上
- favourite-service -> cloud-config  ·  同上
- service-discovery -> cloud-config  ·  同上

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- user-service -> user-service-database
- favourite-service -> favourite-service-database
- shipping-service -> shipping-service-database
- product-service -> product-service-database
- payment-service -> payment-service-database
- order-service -> order-service-database
- service-discovery -> service-discovery-container
- cloud-config -> cloud-config-container
- user-service -> user-service-container
- favourite-service -> favourite-service-container
- shipping-service -> shipping-service-container
- product-service -> product-service-container
- api-gateway -> api-gateway-container
- proxy-client -> proxy-client-container
- order-service -> order-service-container
- payment-service -> payment-service-container
- user-service -> product-service
- user-service -> order-service
- user-service -> favourite-service
- user-service -> payment-service
- user-service -> shipping-service
- product-service -> user-service
- product-service -> order-service

## 標註者的原始回答

```
user-service	user-service-database	data	user-service/src/main/resources/application-dev.yml:13
user-service	user-service-database	data	user-service/src/main/resources/application-stage.yml:13
user-service	user-service-database	data	user-service/src/main/resources/application-prod.yml:13
favourite-service	favourite-service-database	data	favourite-service/src/main/resources/application-dev.yml:13
favourite-service	favourite-service-database	data	favourite-service/src/main/resources/application-stage.yml:13
favourite-service	favourite-service-database	data	favourite-service/src/main/resources/application-prod.yml:13
shipping-service	shipping-service-database	data	shipping-service/src/main/resources/application-dev.yml:13
shipping-service	shipping-service-database	data	shipping-service/src/main/resources/application-stage.yml:13
shipping-service	shipping-service-database	data	shipping-service/src/main/resources/application-prod.yml:13
product-service	product-service-database	data	product-service/src/main/resources/application-dev.yml:13
product-service	product-service-database	data	product-service/src/main/resources/application-stage.yml:13
product-service	product-service-database	data	product-service/src/main/resources/application-prod.yml:13
payment-service	payment-service-database	data	payment-service/src/main/resources/application-dev.yml:13
payment-service	payment-service-database	data	payment-service/src/main/resources/application-stage.yml:13
payment-service	payment-service-database	data	payment-service/src/main/resources/application-prod.yml:13
order-service	order-service-database	data	order-service/src/main/resources/application-dev.yml:13
order-service	order-service-database	data	order-service/src/main/resources/application-stage.yml:13
order-service	order-service-database	data	order-service/src/main/resources/application-prod.yml:13
service-discovery	service-discovery-container	control	service-discovery/compose.yml:4
cloud-config	cloud-config-container	control	cloud-config/compose.yml:4
user-service	user-service-container	control	user-service/compose.yml:4
favourite-service	favourite-service-container	control	favourite-service/compose.yml:4
shipping-service	shipping-service-container	control	shipping-service/compose.yml:4
product-service	product-service-container	control	product-service/compose.yml:4
api-gateway	api-gateway-container	control	compose.yml: (implied from README and docker-compose command)
proxy-client	proxy-client-container	control	compose.yml: (implied from README and docker-compose command)
order-service	order-service-container	control	compose.yml: (implied from README and docker-compose command)
payment-service	payment-service-container	control	compose.yml: (implied from README and docker-compose command)
user-service	service-discovery	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:17
user-service	product-service	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:20
user-service	order-service	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:23
user-service	favourite-service	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:26
user-service	payment-service	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:29
user-service	shipping-service	business	user-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:32
favourite-service	user-service	business	favourite-service/src/main/java/com/selimhorri/app/service/impl/FavouriteServiceImpl.java:40
favourite-service	product-service	business	favourite-service/src/main/java/com/selimhorri/app/service/impl/FavouriteServiceImpl.java:43
shipping-service	product-service	business	shipping-service/src/main/java/com/selimhorri/app/service/impl/OrderItemServiceImpl.java:40
shipping-service	order-service	business	shipping-service/src/main/java/com/selimhorri/app/service/impl/OrderItemServiceImpl.java:42
product-service	user-service	business	product-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:17
product-service	order-service	business	product-service/src/main/java/com/selimhorri/app/constant/AppConstant.java:23
payment-service	order-service	business	payment-service/src/main/java/com/selimhorri/app/service/impl/PaymentServiceImpl.java:38
payment-service	order-service	business	payment-service/src/main/java/com/selimhorri/app/service/impl/PaymentServiceImpl.java:52
order-service	user-service	business	order-service/src/main/java/com/selimhorri/app/service/impl/CartServiceImpl.java:38
order-service	user-service	business	order-service/src/main/java/com/selimhorri/app/service/impl/CartServiceImpl.java:52
proxy-client	user-service	business	proxy-client/src/main/java/com/selimhorri/app/business/auth/service/impl/UserDetailsServiceImpl.java:27
proxy-client	favourite-service	business	proxy-client/src/main/java/com/selimhorri/app/business/favourite/service/FavouriteClientService.java:19
proxy-client	order-service	business	proxy-client/src/main/java/com/selimhorri/app/business/order/service/OrderClientService.java:19
proxy-client	user-service	business	proxy-client/src/main/java/com/selimhorri/app/business/user/service/UserClientService.java:19
proxy-client	user-service	business	proxy-client/src/main/java/com/selimhorri/app/business/user/service/CredentialClientService.java:19
proxy-client	user-service	business	proxy-client/src/main/java/com/selimhorri/app/business/user/service/VerificationTokenClientService.java:19
proxy-client	shipping-service	business	proxy-client/src/main/java/com/selimhorri/app/business/orderItem/service/OrderItemClientService.java:19
proxy-client	order-service	business	proxy-client/src/main/java/com/selimhorri/app/business/order/service/CartClientService.java:19
api-gateway	service-discovery	business	k8s/api-gateway/deployment.yaml (implied by typical API gateway usage, but not explicitly stated, so omitted)
api-gateway	cloud-config	control	k8s/api-gateway/deployment.yaml (implied by typical API gateway usage, but not explicitly stated, so omitted)
```
