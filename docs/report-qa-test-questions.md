# Report Q&A 測試問題集

> 在報告底下的「Ask DepWeaver · <repo>」thread 內直接打字，不用 @bot。
> 以 Bank of Anthos runtime 跑法為例（namespace `bank-of-anthos`）。
> 每問一題，對照 `docker logs chatops4msa` 的 `[DEBUG] graph query plan: [...]` 看查詢層選了什麼算子。

## 0. Tier 3 表單的值（Provide values）

| 欄位 | 值 | 說明 |
|---|---|---|
| username | `testuser` | BoA 內建測試帳號 |
| password | `bankofanthos` | |
| account_num | `1011226111` | testuser 自己的帳號（登入後首頁可對） |
| 收款帳號（若再問） | `1033623433` | alice；要填別人的帳號才會觸發 ledgerwriter → balancereader 的餘額檢查 |
| routing number（若再問） | `883745000` | |

## 1. 基本查詢：問句有點名節點（走事實表 grounding）

| 問題 | 預期 |
|---|---|
| `frontend 依賴哪些服務？` | 列出 frontend 的 outgoing 邊，每條標明 observed / declared |
| `Who calls balancereader?` | frontend 與 ledgerwriter，並說哪條是 runtime 觀測到的 |
| `ledgerwriter 什麼時候會呼叫 balancereader？證據是什麼？` | 說明只在「從本地帳戶扣款」時才查餘額，引 file:line 或 Istio 指標 |
| `userservice 到 accounts-db 那條邊是 runtime 觀測到的嗎？數字代表什麼？` | 是；數字是 TCP 連線數，不是請求數 |
| `請問frontend依賴誰` | 中文夾英文服務名，仍要抓到 frontend |

## 2. 查詢層：沒點名節點或需要遍歷（走 NL → 圖查詢 DSL）

| 問題 | 預期算子 | 預期回答重點 |
|---|---|---|
| `哪些邊是程式碼有宣告但流量沒跑到的？` | `unobserved-edges` 或 `uncovered` | 列 dashed 邊；數字要和頻道貼的覆蓋率一致 |
| `What is the runtime coverage and which business edges were uncovered?` | `uncovered` | 例如 7 / 7 = 100%，或列出缺的邊 |
| `如果 userservice 掛了，會影響哪些服務？` | `impact-of(userservice)` | frontend（depth 1）、ingress（depth 2） |
| `ledgerwriter 要正常運作，前面得先有哪些東西在跑？` | `startup-needs(ledgerwriter)` | balancereader、ledger-db 等 |
| `建議的部署順序是什麼？` | `deploy-order` | 資料庫最先、ingress 最後；要說明這是依呼叫深度推的 |
| `哪些服務有用到資料庫？各自的證據等級？` | `db-users` | userservice → accounts-db、ledger 三服務 → ledger-db |
| `有沒有外部依賴或訊息佇列？` | `externals`、`async` | BoA 應回沒有（或列出實際有的） |
| `有哪些服務被引用但沒部署？` | `undeployed` | 列出或說全部都有部署 |
| `frontend 怎麼連到 ledger-db？` | `path(frontend, ledger-db)` | frontend → ledgerwriter → ledger-db |
| `有哪些邊只是文件提到、沒有使用證據？` | `mentioned-only` | 列 dotted 邊，說明不計分 |

## 3. 追問：測對話歷史

1. `frontend 依賴誰？`
2. `那其中哪幾條是 runtime 觀測到的？`（要接得上上一題）
3. `那沒觀測到的那條要怎麼補？`（要回「導一次會經過那條邊的流量再 Resume」之類，不能瞎編）

## 4. 不能編造（最重要的一組）

| 問題 | 預期 |
|---|---|
| `paymentservice 依賴誰？` | 圖上沒有這個節點，列出相近的 id，不能編 |
| `frontend 每秒處理多少請求？` | 報告沒有 QPS，只有累計次數；不能算一個數字出來 |
| `accounts-db 用什麼版本的 PostgreSQL？` | 證據裡沒有版本資訊 |
| `frontend 的 pod 有幾個 replica？` | 有的話引 k8s notes；greenfield 要回沒有叢集資訊 |
| `這個系統有用 Kafka 嗎？` | 圖上沒有 broker 節點就回沒有證據，不能因為「微服務常用 Kafka」就說有 |

## 5. 報告本身（走 passages 檢索）

| 問題 | 預期 |
|---|---|
| `這份報告的主要限制是什麼？` | 引 Key limitation 那行與第 9、10 節 |
| `Collection status 裡哪個來源是 partial 或 failed？` | 引第 1 節 |
| `報告跟圖有沒有矛盾的地方？` | 引第 10 節；若有矛盾要說以圖為準 |
| `第 5 節是誰寫的？` | 程式碼從 graph 產生，不是 LLM |

## 6. 語言與格式

| 問題 | 預期 |
|---|---|
| `Which edges does the 圖 say are mentioned only?` | 中英混用照答 |
| 任一題 | 回答用問句的語言、用 bullet、不出現 Markdown 表格 |

## 7. 邊界

| 動作 | 預期 |
|---|---|
| 在**主頻道** @bot 問同樣的問題 | 走舊的 intent 流程，不會當成問答（問答只在 thread） |
| 同一人再跑第二個專案 | 開第二條 thread，兩邊各自回答各自的報告 |
| thread 過 7 天再問 | 回「archive 已過期，請重跑分析」 |

## 記錄格式建議

每題記三欄：問題／查詢層選的算子（從 log 抄）／回答有沒有錯。錯的分兩種：**編造**（最嚴重）與**漏答**（context 裡有但沒講）。
