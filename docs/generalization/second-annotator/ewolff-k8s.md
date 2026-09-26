# 第二標註者：ewolff-k8s

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 6470 token）。標註者**沒有看過** `truth/ewolff-k8s.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 0 |
| 只有作者的 truth 有 | 5 |
| 只有第二標註者有 | 5 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.00** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- apache -> order  ·  README「Apache HTTP is configured as a reverse proxy」+ microservice-kubernetes-demo/apache/000-default.conf:14
- apache -> catalog  ·  microservice-kubernetes-demo/apache/000-default.conf:17
- apache -> customer  ·  microservice-kubernetes-demo/apache/000-default.conf:20
- order -> catalog  ·  README「order … uses catalog」+ microservice-kubernetes-demo/microservice-kubernetes-demo-order/src/main/java/com/ewolff/microservice/order/clients/CatalogClient.java:66
- order -> customer  ·  microservice-kubernetes-demo/microservice-kubernetes-demo-order/src/main/java/com/ewolff/microservice/order/clients/CustomerClient.java:78

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- microservice-kubernetes-demo-order -> microservice-kubernetes-demo-catalog
- microservice-kubernetes-demo-order -> microservice-kubernetes-demo-customer
- apache -> microservice-kubernetes-demo-order
- apache -> microservice-kubernetes-demo-catalog
- apache -> microservice-kubernetes-demo-customer

## 標註者的原始回答

```
microservice-kubernetes-demo-order	microservice-kubernetes-demo-catalog	business	microservice-kubernetes-demo/README.md lines 33-38
microservice-kubernetes-demo-order	microservice-kubernetes-demo-customer	business	microservice-kubernetes-demo/README.md lines 33-38
apache	microservice-kubernetes-demo-order	external	microservice-kubernetes-demo/apache/000-default.conf lines 14-15
apache	microservice-kubernetes-demo-catalog	external	microservice-kubernetes-demo/apache/000-default.conf lines 17-18
apache	microservice-kubernetes-demo-customer	external	microservice-kubernetes-demo/apache/000-default.conf lines 20-21
```
