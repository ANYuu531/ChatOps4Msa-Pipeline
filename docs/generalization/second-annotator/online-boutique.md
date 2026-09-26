# 第二標註者：online-boutique

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 27547 token）。標註者**沒有看過** `truth/online-boutique.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 7 |
| 只有作者的 truth 有 | 9 |
| 只有第二標註者有 | 3 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.37** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- frontend -> adservice  ·  README docs/img/architecture-diagram.png（frontend → ad）
- frontend -> recommendationservice  ·  架構圖
- frontend -> productcatalogservice  ·  架構圖
- frontend -> cartservice  ·  架構圖
- frontend -> shippingservice  ·  架構圖
- frontend -> currencyservice  ·  架構圖
- frontend -> checkoutservice  ·  架構圖
- cartservice -> redis-cart  ·  架構圖（cart → Redis cache）+ kubernetes-manifests/cartservice.yaml REDIS_ADDR
- loadgenerator -> frontend  ·  架構圖（loadgenerator → frontend）

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
