# 第二標註者：bank-of-anthos

由 `SecondAnnotatorTest` 產生（模型 `gpt-4.1-mini`，temperature 0，1 次呼叫，prompt 24187 token）。標註者**沒有看過** `truth/bank-of-anthos.tsv`，也沒有看過工具的輸出；它讀的是部署描述、README 與含位址的原始碼行。

量的是**兩個獨立標註者的一致度**，不是誰對：一致度高只代表作者的 truth 不是個人特有的讀法。

| | 條數 |
|---|---|
| 兩人都認為存在 | 11 |
| 只有作者的 truth 有 | 1 |
| 只有第二標註者有 | 0 |
| 一致度（交集 ÷ 聯集，Jaccard） | **0.92** |

## 只有作者的 truth 有（第二標註者沒找到；附作者寫的出處，逐條可查）
- loadgenerator -> frontend  ·  src/loadgenerator/Dockerfile:49 ENTRYPOINT locust --host="http://${FRONTEND_ADDR}"；FRONTEND_ADDR 的值在 src/loadgenerator/k8s/base/loadgenerator.yaml:50（host 在命令列參數裡，不在 locustfile.py）

## 標註者的原始回答

```
frontend	userservice	external	src/frontend/frontend.py:587
frontend	contacts	external	src/frontend/frontend.py:363
frontend	ledgerwriter	external	src/frontend/frontend.py:334
frontend	balancereader	external	src/frontend/frontend.py:118
frontend	transactionhistory	external	src/frontend/frontend.py:124
userservice	accounts-db	data	src/accounts/userservice/userservice.py:245
contacts	accounts-db	data	src/accounts/contacts/contacts.py:220
ledgerwriter	ledger-db	data	src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/LedgerWriterController.java:86
ledgerwriter	balancereader	business	src/ledger/ledgerwriter/src/main/java/anthos/samples/bankofanthos/ledgerwriter/LedgerWriterController.java:85
balancereader	ledger-db	data	src/ledger/balancereader/src/main/java/anthos/samples/bankofanthos/balancereader/BalanceReaderController.java:68
transactionhistory	ledger-db	data	src/ledger/transactionhistory/src/main/java/anthos/samples/bankofanthos/transactionhistory/TransactionHistoryController.java:74
```
