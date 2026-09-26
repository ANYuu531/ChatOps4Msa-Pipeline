# 第二標註者：teastore

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 26088 token；比對規則更新後以 `-Dannotate.replay=true` 重算，未重新呼叫 API）。標註者**沒有看過** `truth/teastore.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 6 |
| 只有作者的 truth 有 | 7 |
| 只有第二標註者有 | 1 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.43** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- teastore-webui -> teastore-auth  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:71 Service.AUTH（webui 內 2 處）；業務呼叫在共用模組 utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/rest/LoadBalancedStoreOperations.java:56 對 Service.AUTH 發 REST，目標由 registry 執行期解析
- teastore-webui -> teastore-image  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:110 multicastRESTOperation(Service.IMAGE（webui 內 3 處）；目標由 registry 執行期解析
- teastore-webui -> teastore-persistence  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:67 loadBalanceRESTOperation(Service.PERSISTENCE（webui 內 21 處）；目標由 registry 執行期解析
- teastore-webui -> teastore-recommender  ·  services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:84 multicastRESTOperation(Service.RECOMMENDER（webui 內 4 處）；目標由 registry 執行期解析
- teastore-auth -> teastore-persistence  ·  services/tools.descartes.teastore.auth/src/main/java/tools/descartes/teastore/auth/rest/AuthUserActionsRest.java:96 sendEntityForCreation(Service.PERSISTENCE（auth 內 5 處）；目標由 registry 執行期解析
- teastore-image -> teastore-persistence  ·  services/tools.descartes.teastore.image/src/main/java/tools/descartes/teastore/image/setup/SetupController.java:158 loadBalanceRESTOperation(Service.PERSISTENCE（image 內 5 處）；目標由 registry 執行期解析
- teastore-recommender -> teastore-persistence  ·  services/tools.descartes.teastore.recommender/src/main/java/tools/descartes/teastore/recommender/servlet/TrainingSynchronizer.java:138 loadBalanceRESTOperation(Service.PERSISTENCE（recommender 內 6 處）；目標由 registry 執行期解析

## 只有第二標註者有（要逐條裁決：truth 漏了，還是標註者看錯）
- teastore-kieker-rabbitmq -> teastore-kieker-rabbitmq

## 標註者也標了、作者標為部署變體（`variant`，不計分也不算分歧）
- teastore-persistence -> teastore-kieker-rabbitmq  ·  同上
- teastore-auth -> teastore-kieker-rabbitmq  ·  同上
- teastore-image -> teastore-kieker-rabbitmq  ·  同上
- teastore-recommender -> teastore-kieker-rabbitmq  ·  同上
- teastore-webui -> teastore-kieker-rabbitmq  ·  examples/kubernetes/teastore-ribbon-kieker*.yaml（Kieker 監控變體）

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
