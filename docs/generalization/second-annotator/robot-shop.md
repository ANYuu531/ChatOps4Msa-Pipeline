# 第二標註者：robot-shop

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 17773 token；比對規則更新後以 `-Dannotate.replay=true` 重算，未重新呼叫 API）。標註者**沒有看過** `truth/robot-shop.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 17 |
| 只有作者的 truth 有 | 4 |
| 只有第二標註者有 | 1 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.77** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- web -> cart  ·  web/default.conf.template:69
- web -> ratings  ·  web/default.conf.template:81
- payment -> paypal.com  ·  payment/payment.py:26 PAYMENT_GATEWAY
- ratings -> catalogue  ·  ratings/html/src/Service/CatalogueService.php:27 sprintf('%s/product/%s', $this->catalogueUrl)（host 由 config/services.yaml 的 CATALOGUE_URL 注入）

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- payment -> payment-gateway

## 標註者的原始回答

```
catalogue	mongodb	data	docker-compose.yaml:31
user	mongodb	data	docker-compose.yaml:46
user	redis	data	docker-compose.yaml:47
cart	redis	data	docker-compose.yaml:62
shipping	mysql	data	docker-compose.yaml:87
ratings	mysql	data	docker-compose.yaml:106
payment	rabbitmq	data	docker-compose.yaml:119
dispatch	rabbitmq	data	docker-compose.yaml:137
web	catalogue	business	docker-compose.yaml:150
web	user	business	docker-compose.yaml:151
web	shipping	business	docker-compose.yaml:152
web	payment	business	docker-compose.yaml:153
load	web	business	docker-compose-load.yaml:12
shipping	cart	business	shipping/src/main/java/com/instana/robotshop/shipping/Controller.java:136
payment	user	business	payment/payment.py:64
payment	payment-gateway	external	payment/payment.py:84
payment	user	business	payment/payment.py:106
payment	cart	business	payment/payment.py:116
cart	redis	data	cart/server.js:29
cart	catalogue	business	cart/server.js:362
user	redis	data	user/server.js:259
user	mongodb	data	user/server.js:272
catalogue	mongodb	data	catalogue/server.js:157
```
