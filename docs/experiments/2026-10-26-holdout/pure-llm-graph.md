# 純 LLM 抽相依圖 vs DepWeaver 靜態層

- model: `gpt-4.1-mini`, temperature 0, JSON mode; 3 runs × 3 arms × 5 projects
- 參考邊集：`docs/generalization/truth/<name>.tsv`（variant 不計）；計分規則同 `GeneralizationScoreTest`
- 輸入預算：`{"descriptor_bytes": 300000, "source_bytes": 500000, "per_file_bytes": 20000, "max_files": 400}`，實際給了什麼見 `budget.md`／`prompts/`
- chat calls: 45 (3494781 prompt + 82969 completion tokens)

## 總表（P / R 為各 run 平均；括號內為最小–最大）

| 專案 | 組別 | 畫（avg） | 對 | 錯 | 漏 | P | R | business P/R（3 次平均） | data P/R（3 次平均） | control P/R（3 次平均） |
|---|---|---|---|---|---|---|---|---|---|---|
| opentelemetry-demo | knowledge | 42.7 | 16.3 | 26.3 | 21.7 | 0.39 (0.35–0.47) | 0.43 (0.37–0.55) | 0.30 / 0.56 | 0.44 / 0.56 | — / 0.28 |
| opentelemetry-demo | files | 43.7 | 35.0 | 8.7 | 3.0 | 0.80 (0.78–0.83) | 0.92 (0.92–0.92) | 0.64 / 1.00 | 1.00 / 1.00 | — / 0.82 |
| opentelemetry-demo | files-nonames | 41.7 | 34.7 | 7.0 | 3.3 | 0.83 (0.80–0.85) | 0.91 (0.89–0.92) | 0.68 / 1.00 | 1.00 / 1.00 | — / 0.80 |
| opentelemetry-demo | **depweaver-static** | 39 | 38 | 1 | 0 | **0.97** | **1.00** | 0.94 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| mall-swarm | knowledge | 27.7 | 16.7 | 11.0 | 7.3 | 0.60 (0.59–0.62) | 0.69 (0.67–0.71) | 0.00 / 0.00 | 0.50 / 0.57 | — / 1.00 |
| mall-swarm | files | 37.0 | 24.0 | 13.0 | 0.0 | 0.66 (0.53–0.77) | 1.00 (1.00–1.00) | 0.16 / 1.00 | 0.94 / 1.00 | — / 1.00 |
| mall-swarm | files-nonames | 23.3 | 21.3 | 2.0 | 2.7 | 0.92 (0.88–0.95) | 0.89 (0.88–0.92) | 0.52 / 1.00 | 1.00 / 0.93 | — / 1.00 |
| mall-swarm | **depweaver-static** | 23 | 23 | 0 | 1 | **1.00** | **0.96** | 1.00 / 1.00 | 1.00 / 0.90 | — / 1.00 |
| hotel-reservation | knowledge | 37.3 | 30.3 | 7.0 | 0.7 | 0.82 (0.78–0.88) | 0.98 (0.97–1.00) | 0.63 / 0.93 | 0.89 / 1.00 | — / 1.00 |
| hotel-reservation | files | 31.3 | 31.0 | 0.3 | 0.0 | 0.99 (0.97–1.00) | 1.00 (1.00–1.00) | 0.97 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| hotel-reservation | files-nonames | 31.0 | 31.0 | 0.0 | 0.0 | 1.00 (1.00–1.00) | 1.00 (1.00–1.00) | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| hotel-reservation | **depweaver-static** | 22 | 22 | 0 | 9 | **1.00** | **0.71** | — / 0.00 | 1.00 / 1.00 | — / 1.00 |
| yas | knowledge | 67.0 | 23.0 | 44.0 | 54.0 | 0.35 (0.30–0.37) | 0.30 (0.29–0.32) | 0.16 / 0.30 | 0.51 / 0.77 | — / 0.07 |
| yas | files | 71.7 | 38.7 | 33.0 | 38.3 | 0.58 (0.35–0.71) | 0.50 (0.44–0.53) | 0.42 / 0.91 | 0.81 / 0.68 | — / 0.22 |
| yas | files-nonames | 51.7 | 38.3 | 13.3 | 38.7 | 0.74 (0.69–0.83) | 0.50 (0.39–0.56) | 0.60 / 0.87 | 0.88 / 0.76 | — / 0.19 |
| yas | **depweaver-static** | 89 | 74 | 15 | 3 | **0.83** | **0.96** | 0.53 / 0.89 | 1.00 / 0.95 | — / 1.00 |
| run-aspnetcore-microservices | knowledge | 17.0 | 7.0 | 10.0 | 4.0 | 0.41 (0.39–0.44) | 0.64 (0.64–0.64) | 0.17 / 1.00 | 0.94 / 0.83 | — / 0.00 |
| run-aspnetcore-microservices | files | 12.0 | 11.0 | 1.0 | 0.0 | 0.92 (0.92–0.92) | 1.00 (1.00–1.00) | 0.67 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| run-aspnetcore-microservices | files-nonames | 11.3 | 11.0 | 0.3 | 0.0 | 0.97 (0.92–1.00) | 1.00 (1.00–1.00) | 0.89 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| run-aspnetcore-microservices | **depweaver-static** | 11 | 11 | 0 | 0 | **1.00** | **1.00** | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |

輸出被截斷的 run（模型重複輸出同樣的邊直到上限；邊以 regex 撿回、去重後計分）：2 次
- mall-swarm files-nonames run2（原始 511 條、去重後 25）
- yas files run2（原始 574 條、去重後 58）

## 每一組的錯與漏（run 1）

### hotel-reservation · files

全對。

### hotel-reservation · files-nonames

全對。

### hotel-reservation · knowledge

錯（LLM 畫了、參考邊集沒有）：
- frontend -> rate
- search -> attractions
- search -> recommendation
- search -> review
- search -> reservation
- user -> profile
- user -> rate
- user -> review
- jaeger -> consul

### mall-swarm · files

錯（LLM 畫了、參考邊集沒有）：
- mall-portal -> oss-cn-shenzhen.aliyuncs.com
- mall-portal -> minio
- mall-portal -> mall-portal
- mall-portal -> mall-member
- mall-portal -> mall-cart
- mall-portal -> mall-order
- mall-portal -> mall-product
- mall-portal -> mall-coupon
- mall-search -> mall-search
- mall-admin -> mall-admin
- mall-monitor -> mall-monitor

### mall-swarm · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- mall-portal -> minio

漏（參考邊集有、LLM 沒畫）：
- mall-admin -> minio
- mall-admin -> oss-cn-shenzhen.aliyuncs.com
- mall-portal -> openapi-sandbox.dl.alipaydev.com

對不到參考邊集的名字（這些邊不計分）：alipay.com, aliyun-oss, oss

### mall-swarm · knowledge

錯（LLM 畫了、參考邊集沒有）：
- mall-auth -> mysql
- mall-search -> redis
- mall-monitor -> elasticsearch
- mall-monitor -> kibana
- mall-monitor -> logstash
- mall-admin -> rabbitmq
- mall-demo -> rabbitmq
- mall-auth -> redis
- mall-portal -> minio
- mall-portal -> oss-cn-shenzhen.aliyuncs.com

漏（參考邊集有、LLM 沒畫）：
- mall-gateway -> redis
- mall-admin -> redis
- mall-auth -> mall-admin
- mall-auth -> mall-portal
- mall-portal -> mongo
- mall-admin -> oss-cn-shenzhen.aliyuncs.com
- mall-search -> mysql
- mall-portal -> redis

### opentelemetry-demo · files

錯（LLM 畫了、參考邊集沒有）：
- agent -> product-catalog
- frontend -> quote
- frontend -> image-provider
- frontend -> flagd
- load-generator -> frontend
- opamp-server -> otel-collector
- valkey-cart -> cart

漏（參考邊集有、LLM 沒畫）：
- frontend-proxy -> flagd
- frontend-proxy -> image-provider
- load-generator -> frontend-proxy

### opentelemetry-demo · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- currency -> flagd
- frontend -> image-provider
- frontend -> flagd
- load-generator -> frontend
- flagd-ui -> flagd
- agent -> product-catalog

漏（參考邊集有、LLM 沒畫）：
- frontend-proxy -> flagd
- frontend-proxy -> image-provider
- load-generator -> frontend-proxy
- checkout -> flagd

### opentelemetry-demo · knowledge

錯（LLM 畫了、參考邊集沒有）：
- frontend -> quote
- cart -> currency
- checkout -> fraud-detection
- payment -> accounting
- payment -> currency
- fraud-detection -> firepit
- firepit -> kafka
- firepit -> astronomy-db
- recommendation -> kafka
- ad -> kafka
- quote -> currency
- shipping -> currency
- shipping -> kafka
- email -> external
- frontend -> flagd
- flagd-ui -> flagd
- firepit -> otel-collector
- frontend -> jaeger
- checkout -> jaeger
- firepit -> jaeger
- frontend -> prometheus
- checkout -> prometheus
- firepit -> prometheus
- frontend -> local-llm.com
- chatbot -> local-llm.com
- frontend -> frontend-proxy

漏（參考邊集有、LLM 沒畫）：
- product-catalog -> flagd
- frontend -> checkout
- ad -> flagd
- frontend-proxy -> flagd-ui
- checkout -> product-catalog
- frontend-proxy -> flagd
- cart -> flagd
- frontend-proxy -> load-generator
- email -> flagd
- payment -> flagd
- accounting -> astronomy-db
- frontend-proxy -> image-provider
- fraud-detection -> flagd
- accounting -> kafka
- frontend -> shipping
- checkout -> kafka
- load-generator -> frontend-proxy
- frontend-proxy -> telemetry-docs
- frontend -> currency
- shipping -> quote
- load-generator -> flagd
- shipping -> flagd
- recommendation -> flagd
- checkout -> currency

### run-aspnetcore-microservices · files

錯（LLM 畫了、參考邊集沒有）：
- discount.grpc -> discount.grpc

### run-aspnetcore-microservices · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- ordering.api -> ordering.api

對不到參考邊集的名字（這些邊不計分）：discountdb

### run-aspnetcore-microservices · knowledge

錯（LLM 畫了、參考邊集沒有）：
- ordering.api -> basket.api
- ordering.api -> catalog.api
- ordering.api -> discount.grpc
- shopping.web -> basket.api
- shopping.web -> catalog.api
- shopping.web -> ordering.api
- basket.api -> yarpapigateway
- catalog.api -> yarpapigateway
- ordering.api -> yarpapigateway
- discount.grpc -> yarpapigateway

漏（參考邊集有、LLM 沒畫）：
- basket.api -> messagebroker
- yarpapigateway -> ordering.api
- yarpapigateway -> catalog.api
- yarpapigateway -> basket.api

### yas · files

錯（LLM 畫了、參考邊集沒有）：
- backoffice-bff -> identity
- backoffice-bff -> redis
- backoffice-bff -> backoffice-nextjs
- backoffice -> backoffice-bff
- cart -> media
- delivery -> identity
- media -> sampledata
- nginx -> backoffice-bff
- nginx -> storefront-bff
- payment-paypal -> payment
- product -> rating
- rating -> product
- recommendation -> customer
- recommendation -> order
- sampledata -> product
- sampledata -> media
- storefront-bff -> identity
- storefront-bff -> redis
- storefront-bff -> storefront-nextjs
- storefront -> storefront-bff

漏（參考邊集有、LLM 沒畫）：
- sampledata -> identity
- backoffice -> redis
- identity -> postgres
- nginx -> rating
- nginx -> identity
- nginx -> tax
- backoffice -> identity
- storefront-nextjs -> storefront
- storefront -> identity
- backoffice -> nginx
- nginx -> storefront
- nginx -> cart
- nginx -> pgadmin
- nginx -> backoffice
- nginx -> customer
- kafka-connect -> postgres
- nginx -> location
- kafka-connect -> kafka
- nginx -> product
- storefront -> nginx
- storefront -> redis
- backoffice -> backoffice-nextjs
- nginx -> media
- nginx -> promotion
- nginx -> payment
- kafka -> zookeeper
- nginx -> order
- nginx -> sampledata
- nginx -> kafka
- storefront -> storefront-nextjs
- location -> postgres
- nginx -> swagger-ui
- nginx -> inventory
- nginx -> search
- nginx -> elasticsearch
- location -> identity

### yas · files-nonames

錯（LLM 畫了、參考邊集沒有）：
- product -> rating
- cart -> media
- rating -> product
- payment-paypal -> postgres
- sampledata -> product
- sampledata -> media
- recommendation -> customer
- recommendation -> order
- payment-paypal -> payment

漏（參考邊集有、LLM 沒畫）：
- backoffice -> redis
- identity -> postgres
- nginx -> rating
- nginx -> identity
- nginx -> tax
- backoffice -> identity
- storefront-nextjs -> storefront
- storefront -> identity
- backoffice -> nginx
- nginx -> storefront
- nginx -> cart
- nginx -> pgadmin
- nginx -> backoffice
- nginx -> customer
- kafka-connect -> postgres
- nginx -> location
- kafka-connect -> kafka
- nginx -> product
- storefront -> nginx
- storefront -> redis
- backoffice -> backoffice-nextjs
- nginx -> media
- order -> promotion
- nginx -> promotion
- nginx -> payment
- kafka -> zookeeper
- nginx -> order
- nginx -> sampledata
- nginx -> kafka
- storefront -> storefront-nextjs
- nginx -> swagger-ui
- nginx -> inventory
- nginx -> search
- nginx -> elasticsearch

對不到參考邊集的名字（這些邊不計分）：backoffice-bff, storefront-bff

### yas · knowledge

錯（LLM 畫了、參考邊集沒有）：
- backoffice -> keycloak
- backoffice -> postgres
- cart -> inventory
- cart -> promotion
- cart -> redis
- collector -> kafka
- collector -> elasticsearch
- customer -> keycloak
- customer -> redis
- identity -> keycloak
- inventory -> redis
- jaeger -> tempo
- media -> redis
- nginx -> backoffice-nextjs
- nginx -> storefront-nextjs
- order -> payment
- payment -> payment-paypal
- payment-paypal -> webhook
- storefront -> cart
- storefront -> customer
- storefront -> order
- storefront -> payment
- storefront -> product
- storefront -> promotion
- storefront -> rating
- storefront -> recommendation
- storefront-nextjs -> nginx
- grafana -> jaeger
- prometheus -> postgres
- prometheus -> redis
- prometheus -> kafka
- prometheus -> zookeeper
- loki -> kafka
- tempo -> kafka
- pgadmin -> postgres
- swagger-ui -> nginx
- zookeeper -> kafka

漏（參考邊集有、LLM 沒畫）：
- search -> kafka
- promotion -> identity
- payment -> media
- product -> identity
- sampledata -> identity
- nginx -> rating
- inventory -> location
- payment -> identity
- nginx -> identity
- nginx -> tax
- rating -> order
- backoffice -> identity
- storefront -> identity
- media -> identity
- backoffice -> nginx
- nginx -> storefront
- nginx -> cart
- rating -> identity
- nginx -> pgadmin
- nginx -> backoffice
- nginx -> customer
- tax -> location
- kafka-connect -> postgres
- rating -> customer
- order -> tax
- nginx -> location
- inventory -> identity
- nginx -> product
- product -> media
- search -> product
- storefront -> redis
- nginx -> media
- tax -> identity
- payment -> order
- customer -> identity
- inventory -> product
- nginx -> promotion
- nginx -> payment
- kafka -> zookeeper
- cart -> postgres
- nginx -> order
- nginx -> sampledata
- nginx -> kafka
- storefront -> storefront-nextjs
- promotion -> product
- customer -> location
- order -> product
- nginx -> swagger-ui
- nginx -> inventory
- search -> identity
- nginx -> search
- cart -> identity
- nginx -> elasticsearch
- order -> identity
- location -> identity
