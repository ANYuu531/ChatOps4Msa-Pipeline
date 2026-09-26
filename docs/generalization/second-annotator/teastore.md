# 第二標註者：teastore

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 26088 token）。標註者**沒有看過** `truth/teastore.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 6 |
| 只有作者的 truth 有 | 7 |
| 只有第二標註者有 | 6 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.32** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- teastore-webui -> teastore-auth  ·  services/tools.descartes.teastore.webui：Service.AUTH 引用 2 處（loadBalanceRESTOperation）
- teastore-webui -> teastore-image  ·  webui：Service.IMAGE 引用 3 處
- teastore-webui -> teastore-persistence  ·  webui：Service.PERSISTENCE 引用 21 處
- teastore-webui -> teastore-recommender  ·  webui：Service.RECOMMENDER 引用 4 處
- teastore-auth -> teastore-persistence  ·  auth：Service.PERSISTENCE 引用 5 處
- teastore-image -> teastore-persistence  ·  image：Service.PERSISTENCE 引用 5 處
- teastore-recommender -> teastore-persistence  ·  recommender：Service.PERSISTENCE 引用 6 處

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- teastore-persistence -> teastore-kieker-rabbitmq
- teastore-auth -> teastore-kieker-rabbitmq
- teastore-image -> teastore-kieker-rabbitmq
- teastore-recommender -> teastore-kieker-rabbitmq
- teastore-webui -> teastore-kieker-rabbitmq
- teastore-kieker-rabbitmq -> teastore-kieker-rabbitmq

## 標註者的原始回答

```
teastore-persistence	teastore-db	data	examples/kubernetes/teastore-ribbon.yaml:108-111
teastore-persistence	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:106-107
teastore-auth	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:139-140
teastore-image	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:168-169
teastore-recommender	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:197-198
teastore-webui	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:226-227
teastore-webui	teastore-registry	business	examples/kubernetes/teastore-ribbon.yaml:226-227
teastore-persistence	teastore-db	data	examples/kubernetes/teastore-ribbon-kieker.yaml:108-111
teastore-persistence	teastore-registry	business	examples/kubernetes/teastore-ribbon-kieker.yaml:106-107
teastore-persistence	teastore-kieker-rabbitmq	data	examples/kubernetes/teastore-ribbon-kieker.yaml:112-113
teastore-auth	teastore-registry	business	examples/kubernetes/teastore-ribbon-kieker.yaml:141-142
teastore-auth	teastore-kieker-rabbitmq	data	examples/kubernetes/teastore-ribbon-kieker.yaml:143-144
teastore-image	teastore-registry	business	examples/kubernetes/teastore-ribbon-kieker.yaml:172-173
teastore-image	teastore-kieker-rabbitmq	data	examples/kubernetes/teastore-ribbon-kieker.yaml:174-175
teastore-recommender	teastore-registry	business	examples/kubernetes/teastore-ribbon-kieker.yaml:203-204
teastore-recommender	teastore-kieker-rabbitmq	data	examples/kubernetes/teastore-ribbon-kieker.yaml:205-206
teastore-webui	teastore-registry	business	examples/kubernetes/teastore-ribbon-kieker.yaml:234-235
teastore-webui	teastore-kieker-rabbitmq	data	examples/kubernetes/teastore-ribbon-kieker.yaml:236-237
teastore-kieker-rabbitmq	teastore-kieker-rabbitmq	external	examples/kubernetes/teastore-rabbitmq.yaml:4-7
teastore-kieker-rabbitmq	teastore-kieker-rabbitmq	external	examples/kubernetes/teastore-rabbitmq.yaml:20-45
```
