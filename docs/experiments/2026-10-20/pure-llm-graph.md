# 純 LLM 抽相依圖 vs DepWeaver 靜態層

- model: `gpt-4.1-mini`, temperature 0, JSON mode; 3 runs × 3 arms × 7 projects
- 參考邊集：`docs/generalization/truth/<name>.tsv`（variant 不計）；計分規則同 `GeneralizationScoreTest`
- 輸入預算：`{"descriptor_bytes": 300000, "source_bytes": 500000, "per_file_bytes": 20000, "max_files": 400}`，實際給了什麼見 `budget.md`／`prompts/`
- chat calls: 63 (3260592 prompt + 105074 completion tokens)

## 總表（P / R 為各 run 平均；括號內為最小–最大）

| 專案 | 組別 | 畫（avg） | 對 | 錯 | 漏 | P | R | business P/R（3 次平均） | data P/R（3 次平均） | control P/R（3 次平均） |
|---|---|---|---|---|---|---|---|---|---|---|
| bank-of-anthos | knowledge | 11.3 | 8.0 | 3.3 | 4.0 | 0.71 (0.67–0.73) | 0.67 (0.67–0.67) | 0.78 / 0.78 | 0.53 / 0.47 | — / 1.00 |
| bank-of-anthos | files | 12.0 | 12.0 | 0.0 | 0.0 | 1.00 (1.00–1.00) | 1.00 (1.00–1.00) | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| bank-of-anthos | files-nonames | 12.0 | 12.0 | 0.0 | 0.0 | 1.00 (1.00–1.00) | 1.00 (1.00–1.00) | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| bank-of-anthos | **depweaver-static** | 12 | 12 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| ewolff-k8s | knowledge | 3.7 | 3.7 | 0.0 | 1.3 | 1.00 (1.00–1.00) | 0.73 (0.60–1.00) | 1.00 / 1.00 | — | — / 0.55 |
| ewolff-k8s | files | 5.0 | 5.0 | 0.0 | 0.0 | 1.00 (1.00–1.00) | 1.00 (1.00–1.00) | 1.00 / 1.00 | — | — / 1.00 |
| ewolff-k8s | files-nonames | 5.0 | 5.0 | 0.0 | 0.0 | 1.00 (1.00–1.00) | 1.00 (1.00–1.00) | 1.00 / 1.00 | — | — / 1.00 |
| ewolff-k8s | **depweaver-static** | 5 | 5 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | — | — / 1.00 |
| ecommerce | knowledge | 27.0 | 23.0 | 4.0 | 14.0 | 0.85 (0.85–0.85) | 0.62 (0.62–0.62) | 0.43 / 0.25 | — | — / 0.83 |
| ecommerce | files | 36.0 | 32.7 | 3.3 | 4.3 | 0.92 (0.84–0.96) | 0.88 (0.73–1.00) | 0.80 / 1.00 | — | — / 0.82 |
| ecommerce | files-nonames | 0.0 | 0.0 | 0.0 | 37.0 | 0.00 (0.00–0.00) | 0.00 (0.00–0.00) | — / 0.00 | — | — / 0.00 |
| ecommerce | **depweaver-static** | 37 | 37 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | — | — / 1.00 |
| piggymetrics | knowledge | 27.3 | 22.0 | 5.3 | 11.0 | 0.81 (0.73–0.92) | 0.67 (0.64–0.70) | 0.00 / 0.00 | 1.00 / 0.63 | — / 0.87 |
| piggymetrics | files | 25.3 | 23.3 | 2.0 | 9.7 | 0.92 (0.90–0.96) | 0.71 (0.61–0.79) | 0.75 / 0.60 | 0.89 / 0.80 | — / 0.67 |
| piggymetrics | files-nonames | 27.3 | 25.3 | 2.0 | 7.7 | 0.93 (0.91–0.93) | 0.77 (0.61–0.85) | 0.70 / 0.60 | 0.92 / 0.84 | — / 0.87 |
| piggymetrics | **depweaver-static** | 33 | 33 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| teastore | knowledge | 22.0 | 10.0 | 12.0 | 3.0 | 0.45 (0.45–0.45) | 0.77 (0.77–0.77) | 0.36 / 0.57 | 0.17 / 1.00 | — / 1.00 |
| teastore | files | 11.3 | 10.7 | 0.7 | 2.3 | 0.93 (0.86–1.00) | 0.82 (0.46–1.00) | 1.00 / 0.67 | 0.67 / 1.00 | — / 1.00 |
| teastore | files-nonames | 9.3 | 8.7 | 0.7 | 4.3 | 0.62 (0.00–0.93) | 0.67 (0.00–1.00) | 1.00 / 0.67 | 0.50 / 0.67 | — / 0.67 |
| teastore | **depweaver-static** | 13 | 13 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| online-boutique | knowledge | 15.0 | 12.3 | 2.7 | 4.7 | 0.82 (0.73–0.93) | 0.73 (0.65–0.82) | 0.79 / 0.69 | 1.00 / 1.00 | — / 1.00 |
| online-boutique | files | 27.0 | 17.0 | 10.0 | 0.0 | 0.64 (0.59–0.74) | 1.00 (1.00–1.00) | 0.62 / 1.00 | 0.83 / 1.00 | — / 1.00 |
| online-boutique | files-nonames | 18.3 | 17.0 | 1.3 | 0.0 | 0.94 (0.81–1.00) | 1.00 (1.00–1.00) | 0.93 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| online-boutique | **depweaver-static** | 17 | 17 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| robot-shop | knowledge | 18.7 | 12.0 | 6.7 | 9.0 | 0.66 (0.52–0.75) | 0.57 (0.57–0.57) | 0.00 / 0.00 | 0.52 / 0.54 | — / 0.95 |
| robot-shop | files | 20.0 | 19.7 | 0.3 | 1.3 | 0.98 (0.95–1.00) | 0.94 (0.90–0.95) | 1.00 / 1.00 | 0.96 / 1.00 | — / 0.81 |
| robot-shop | files-nonames | 20.0 | 20.0 | 0.0 | 1.0 | 1.00 (1.00–1.00) | 0.95 (0.95–0.95) | 1.00 / 1.00 | 1.00 / 1.00 | — / 0.86 |
| robot-shop | **depweaver-static** | 19 | 19 | 0 | 2 | **1.00** | **0.90** | 1.00 / 0.60 | 1.00 / 1.00 | — / 1.00 |

輸出被截斷的 run（模型重複輸出同樣的邊直到上限；邊以 regex 撿回、去重後計分）：2 次
- bank-of-anthos files-nonames run2（原始 1417 條、去重後 12）
- bank-of-anthos files-nonames run3（原始 1417 條、去重後 12）

## 每一組的錯與漏（run 1）

### bank-of-anthos · files

全對。

### bank-of-anthos · files-nonames

對不到參考邊集的名字（這些邊不計分）：external-internet, istio-ingressgateway, jwt-key

全對。

### bank-of-anthos · knowledge

錯（LLM 畫了、參考邊集沒有）：
- balancereader -> accounts-db
- contacts -> userservice
- ledgerwriter -> accounts-db

漏（參考邊集有、LLM 沒畫）：
- userservice -> accounts-db
- contacts -> accounts-db
- balancereader -> ledger-db
- frontend -> ledgerwriter

### ecommerce · files

錯（LLM 畫了、參考邊集沒有）：
- service-discovery -> service-discovery
- order-service -> order-service
- payment-service -> payment-service
- product-service -> product-service
- favourite-service -> favourite-service
- shipping-service -> shipping-service
- user-service -> user-service

### ecommerce · files-nonames

漏（參考邊集有、LLM 沒畫）：
- shipping-service -> product-service
- api-gateway -> product-service
- shipping-service -> service-discovery
- proxy-client -> service-discovery
- proxy-client -> order-service
- api-gateway -> shipping-service
- favourite-service -> cloud-config
- favourite-service -> service-discovery
- proxy-client -> user-service
- api-gateway -> order-service
- favourite-service -> product-service
- favourite-service -> user-service
- api-gateway -> favourite-service
- api-gateway -> service-discovery
- proxy-client -> shipping-service
- shipping-service -> order-service
- product-service -> cloud-config
- proxy-client -> favourite-service
- proxy-client -> product-service
- api-gateway -> payment-service
- api-gateway -> proxy-client
- cloud-config -> service-discovery
- api-gateway -> cloud-config
- proxy-client -> cloud-config
- cloud-config -> github.com
- user-service -> cloud-config
- api-gateway -> user-service
- payment-service -> service-discovery
- payment-service -> cloud-config
- shipping-service -> cloud-config
- order-service -> user-service
- proxy-client -> payment-service
- order-service -> cloud-config
- order-service -> service-discovery
- payment-service -> order-service
- product-service -> service-discovery
- user-service -> service-discovery

對不到參考邊集的名字（這些邊不計分）：api-gateway-container, cloud-config-container, ecommerce_dev_db, ecommerce_stage_db, favourite-service-container, order-service-container, payment-service-container, product-service-container, proxy-client-container, service-discovery-container, shipping-service-container, user-service-container, zipkin-container

### ecommerce · knowledge

錯（LLM 畫了、參考邊集沒有）：
- order-service -> product-service
- order-service -> payment-service
- order-service -> shipping-service
- user-service -> github.com

漏（參考邊集有、LLM 沒畫）：
- shipping-service -> product-service
- proxy-client -> order-service
- proxy-client -> user-service
- api-gateway -> service-discovery
- proxy-client -> shipping-service
- shipping-service -> order-service
- proxy-client -> favourite-service
- proxy-client -> product-service
- cloud-config -> service-discovery
- api-gateway -> cloud-config
- proxy-client -> cloud-config
- cloud-config -> github.com
- proxy-client -> payment-service
- payment-service -> order-service

### ewolff-k8s · files

全對。

### ewolff-k8s · files-nonames

全對。

### ewolff-k8s · knowledge

漏（參考邊集有、LLM 沒畫）：
- apache -> customer
- apache -> catalog

### online-boutique · files

錯（LLM 畫了、參考邊集沒有）：
- paymentservice -> paymentservice
- shippingservice -> shippingservice
- emailservice -> emailservice
- currencyservice -> currencyservice
- productcatalogservice -> productcatalogservice
- adservice -> adservice
- cartservice -> cartservice
- checkoutservice -> checkoutservice
- frontend -> frontend
- loadgenerator -> loadgenerator
- recommendationservice -> recommendationservice
- shoppingassistantservice -> shoppingassistantservice

### online-boutique · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- shippingservice -> shippingservice
- paymentservice -> paymentservice
- emailservice -> emailservice
- adservice -> adservice

對不到參考邊集的名字（這些邊不計分）：allow-egress-google-metadata, allow-egress-googleapis, alloydb, external-google-apis, istio-gateway, metadata.google.internal, opentelemetrycollector, spanner

### online-boutique · knowledge

錯（LLM 畫了、參考邊集沒有）：
- paymentservice -> external
- shippingservice -> external
- shoppingassistantservice -> productcatalogservice
- shoppingassistantservice -> recommendationservice

漏（參考邊集有、LLM 沒畫）：
- recommendationservice -> productcatalogservice
- frontend -> checkoutservice
- checkoutservice -> currencyservice
- frontend -> shippingservice
- frontend -> shoppingassistantservice
- checkoutservice -> productcatalogservice

### piggymetrics · files

錯（LLM 畫了、參考邊集沒有）：
- config -> config
- auth-service -> rabbitmq

漏（參考邊集有、LLM 沒畫）：
- gateway -> statistics-service
- gateway -> account-service
- gateway -> notification-service
- turbine-stream-service -> rabbitmq
- statistics-service -> registry
- account-service -> registry
- notification-service -> auth-service
- auth-service -> registry
- notification-service -> registry
- gateway -> registry
- statistics-service -> auth-service
- gateway -> auth-service
- turbine-stream-service -> registry

### piggymetrics · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- monitoring -> registry
- monitoring -> rabbitmq

漏（參考邊集有、LLM 沒畫）：
- account-service -> rabbitmq
- statistics-service -> api.exchangeratesapi.io
- notification-service -> auth-service
- notification-service -> smtp.gmail.com
- statistics-service -> auth-service

對不到參考邊集的名字（這些邊不計分）：external-rates-api

### piggymetrics · knowledge

錯（LLM 畫了、參考邊集沒有）：
- auth-service -> gateway
- account-service -> gateway
- statistics-service -> gateway
- notification-service -> gateway
- gateway -> monitoring
- monitoring -> turbine-stream-service
- turbine-stream-service -> gateway
- account-service -> api.exchangeratesapi.io

漏（參考邊集有、LLM 沒畫）：
- notification-service -> account-service
- statistics-service -> rabbitmq
- monitoring -> config
- account-service -> rabbitmq
- turbine-stream-service -> rabbitmq
- statistics-service -> api.exchangeratesapi.io
- notification-service -> auth-service
- registry -> config
- statistics-service -> auth-service
- account-service -> auth-service
- account-service -> statistics-service

### robot-shop · files

漏（參考邊集有、LLM 沒畫）：
- web -> cart

### robot-shop · files-nonames

漏（參考邊集有、LLM 沒畫）：
- web -> cart

對不到參考邊集的名字（這些邊不計分）：istio-ingressgateway, robotshop-gateway

### robot-shop · knowledge

錯（LLM 畫了、參考邊集沒有）：
- cart -> mongodb
- ratings -> mongodb
- payment -> mysql
- shipping -> rabbitmq
- dispatch -> shipping
- load -> cart
- load -> catalogue
- load -> user
- load -> payment
- load -> shipping
- load -> ratings

漏（參考邊集有、LLM 沒畫）：
- payment -> rabbitmq
- payment -> cart
- cart -> catalogue
- shipping -> mysql
- ratings -> mysql
- shipping -> cart
- ratings -> catalogue
- user -> redis
- payment -> user

### teastore · files

錯（LLM 畫了、參考邊集沒有）：
- teastore-kieker-rabbitmq -> rabbitmq

### teastore · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- teastore-kieker-rabbitmq -> teastore-kieker-rabbitmq

### teastore · knowledge

錯（LLM 畫了、參考邊集沒有）：
- teastore-auth -> teastore-db
- teastore-recommender -> teastore-db
- teastore-image -> teastore-db
- teastore-all -> teastore-auth
- teastore-all -> teastore-persistence
- teastore-all -> teastore-recommender
- teastore-all -> teastore-image
- teastore-all -> teastore-webui
- teastore-all -> rabbitmq
- teastore-kieker-rabbitmq -> rabbitmq
- teastore-all -> teastore-registry
- teastore-kieker-rabbitmq -> teastore-registry

漏（參考邊集有、LLM 沒畫）：
- teastore-image -> teastore-persistence
- teastore-recommender -> teastore-persistence
- teastore-auth -> teastore-persistence
