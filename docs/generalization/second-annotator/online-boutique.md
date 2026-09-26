# 第二標註者：online-boutique

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 27547 token；比對規則更新後以 `-Dannotate.replay=true` 重算，未重新呼叫 API）。標註者**沒有看過** `truth/online-boutique.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 7 |
| 只有作者的 truth 有 | 10 |
| 只有第二標註者有 | 3 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.35** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- frontend -> adservice  ·  src/frontend/main.go:138 mustMapEnv(&svc.adSvcAddr, "AD_SERVICE_ADDR")；值在 kubernetes-manifests/frontend.yaml:82 value: "adservice:9555"
- frontend -> recommendationservice  ·  src/frontend/main.go:135 RECOMMENDATION_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:76 value: "recommendationservice:8080"
- frontend -> productcatalogservice  ·  src/frontend/main.go:132 PRODUCT_CATALOG_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:70 value: "productcatalogservice:3550"
- frontend -> cartservice  ·  src/frontend/main.go:134 CART_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:74 value: "cartservice:7070"
- frontend -> shippingservice  ·  src/frontend/main.go:137 SHIPPING_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:78 value: "shippingservice:50051"
- frontend -> currencyservice  ·  src/frontend/main.go:133 CURRENCY_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:72 value: "currencyservice:7000"
- frontend -> checkoutservice  ·  src/frontend/main.go:136 CHECKOUT_SERVICE_ADDR；kubernetes-manifests/frontend.yaml:80 value: "checkoutservice:5050"
- frontend -> shoppingassistantservice  ·  src/frontend/main.go:139 mustMapEnv(&svc.shoppingAssistantSvcAddr, "SHOPPING_ASSISTANT_SERVICE_ADDR")；值在 kubernetes-manifests/frontend.yaml:84 value: "shoppingassistantservice:80"（2026-09-26 補：README 的架構圖沒畫這個服務，照圖標註時漏了，是工具從 manifest 抓到的）
- cartservice -> redis-cart  ·  kubernetes-manifests/cartservice.yaml:50 REDIS_ADDR，值 "redis-cart:6379"
- loadgenerator -> frontend  ·  kubernetes-manifests/loadgenerator.yaml:82 FRONTEND_ADDR，值 "frontend:80"（locust 的 --host 由它組成，不在 locustfile.py 裡）

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- emailservice -> checkoutservice
- paymentservice -> checkoutservice
- adservice -> frontend

## 標註者的原始回答

```
checkoutservice	productcatalogservice	business	release/kubernetes-manifests.yaml:211-212
checkoutservice	shippingservice	business	release/kubernetes-manifests.yaml:213-214
checkoutservice	paymentservice	business	release/kubernetes-manifests.yaml:215-216
checkoutservice	emailservice	business	release/kubernetes-manifests.yaml:217-218
checkoutservice	currencyservice	business	release/kubernetes-manifests.yaml:219-220
checkoutservice	cartservice	business	release/kubernetes-manifests.yaml:221-222
recommendationservice	productcatalogservice	business	helm-chart/templates/recommendationservice.yaml:86-87
emailservice	checkoutservice	business	helm-chart/templates/emailservice.yaml:136-137
paymentservice	checkoutservice	business	helm-chart/templates/paymentservice.yaml:134-135
adservice	frontend	business	helm-chart/templates/adservice.yaml:121-124
```
