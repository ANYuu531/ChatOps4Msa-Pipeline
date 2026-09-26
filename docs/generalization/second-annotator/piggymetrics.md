# 第二標註者：piggymetrics

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 13880 token；比對規則更新後以 `-Dannotate.replay=true` 重算，未重新呼叫 API）。標註者**沒有看過** `truth/piggymetrics.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 25 |
| 只有作者的 truth 有 | 9 |
| 只有第二標註者有 | 2 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.69** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- statistics-service -> api.exchangeratesapi.io  ·  config/src/main/resources/shared/statistics-service.yml rates.url + ExchangeRatesClient.java:10
- turbine-stream-service -> rabbitmq  ·  turbine-stream-service/pom.xml spring-cloud-starter-stream-rabbit（consumer）
- account-service -> registry  ·  config/src/main/resources/shared/application.yml eureka.client.serviceUrl.defaultZone（所有 config client）
- auth-service -> registry  ·  同上
- gateway -> registry  ·  同上
- monitoring -> registry  ·  同上
- notification-service -> registry  ·  同上
- statistics-service -> registry  ·  同上
- turbine-stream-service -> registry  ·  同上

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- statistics-service -> external
- registry -> registry-mongodb

## 標註者的原始回答

```
account-service	statistics-service	business	account-service/src/main/java/com/piggymetrics/account/client/StatisticsServiceClient.java:10
account-service	auth-service	business	account-service/src/main/java/com/piggymetrics/account/client/AuthServiceClient.java:9
account-service	account-mongodb	data	config/src/main/resources/shared/account-service.yml:13
account-service	auth-service	external	config/src/main/resources/shared/account-service.yml:6
statistics-service	auth-service	business	statistics-service/src/main/java/com/piggymetrics/statistics/service/security/CustomUserInfoTokenServices.java:129
statistics-service	statistics-mongodb	data	config/src/main/resources/shared/statistics-service.yml:13
statistics-service	external	external	config/src/main/resources/shared/statistics-service.yml:24
notification-service	account-service	business	notification-service/src/main/java/com/piggymetrics/notification/client/AccountServiceClient.java:9
notification-service	auth-service	business	notification-service/src/main/java/com/piggymetrics/notification/config/ResourceServerConfig.java:31
notification-service	notification-mongodb	data	config/src/main/resources/shared/notification-service.yml:31
auth-service	auth-mongodb	data	config/src/main/resources/shared/auth-service.yml:4
gateway	auth-service	business	config/src/main/resources/shared/gateway.yml:21
gateway	account-service	business	config/src/main/resources/shared/gateway.yml:27
gateway	statistics-service	business	config/src/main/resources/shared/gateway.yml:33
gateway	notification-service	business	config/src/main/resources/shared/gateway.yml:39
registry	config	control	registry/src/main/resources/bootstrap.yml:6
registry	registry-mongodb	data	config/src/main/resources/shared/registry.yml:1
account-service	config	control	account-service/src/main/resources/bootstrap.yml:6
statistics-service	config	control	statistics-service/src/main/resources/bootstrap.yml:6
notification-service	config	control	notification-service/src/main/resources/bootstrap.yml:6
auth-service	config	control	auth-service/src/main/resources/bootstrap.yml:6
gateway	config	control	gateway/src/main/resources/bootstrap.yml:6
monitoring	config	control	monitoring/src/main/resources/bootstrap.yml:6
turbine-stream-service	config	control	turbine-stream-service/src/main/resources/bootstrap.yml:6
statistics-service	rabbitmq	data	config/src/main/resources/shared/application.yml:26
account-service	rabbitmq	data	config/src/main/resources/shared/application.yml:26
notification-service	rabbitmq	data	config/src/main/resources/shared/application.yml:26
notification-service	smtp.gmail.com	external	config/src/main/resources/shared/notification-service.yml:37
```
