# Question refiner on the hold-out

- embedding model: `text-embedding-3-small`; chat model: `gpt-4.1-mini`
- thresholds T=0.51 M=0.10 H=0.80; shortlist 5 intents × 3 examples
- hold-out: 102 questions; router confident on 36; deferred 66 (refiner ran on 57, skipped 9: missing node or edit-like)

## Part 1 — coverage and confident-wrong: router alone vs router + accepted rewordings

| group | n | router confident | router conf-wrong | + accepted | + accepted wrong | coverage before | coverage after | conf-wrong after |
|---|---|---|---|---|---|---|---|---|
| all | 102 | 36 | 0 | 50 | 4 | 35.3% | 84.3% | 4 |
| project=bank-of-anthos | 34 | 14 | 0 | 14 | 2 | 41.2% | 82.4% | 2 |
| project=train-ticket | 34 | 10 | 0 | 18 | 1 | 29.4% | 82.4% | 1 |
| project=sock-shop | 34 | 12 | 0 | 18 | 1 | 35.3% | 88.2% | 1 |
| lang=zh | 51 | 18 | 0 | 24 | 1 | 35.3% | 82.4% | 1 |
| lang=en | 51 | 18 | 0 | 26 | 3 | 35.3% | 86.3% | 3 |
| has_node=yes | 43 | 10 | 0 | 25 | 1 | 23.3% | 81.4% | 1 |
| has_node=no | 59 | 26 | 0 | 25 | 3 | 44.1% | 86.4% | 3 |

## Part 1 — the deferred rows: what each arm made of them

(planner = status quo; rewrite = reworded and re-routed, accepted or rejected; pipeline = rewrite if accepted, else planner; pick = the model's label as it is)

| group | deferred | planner correct | planner wrong | planner none | rewrite accepted | accepted wrong | pipeline correct | pipeline wrong | pipeline none | pick correct | pick wrong | pick none |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| all | 66 | 60 | 3 | 3 | 50 | 4 | 62 | 4 | 0 | 53 | 4 | 0 |
| project=bank-of-anthos | 20 | 20 | 0 | 0 | 14 | 2 | 18 | 2 | 0 | 16 | 2 | 0 |
| project=train-ticket | 24 | 21 | 1 | 2 | 18 | 1 | 23 | 1 | 0 | 19 | 1 | 0 |
| project=sock-shop | 22 | 19 | 2 | 1 | 18 | 1 | 21 | 1 | 0 | 18 | 1 | 0 |
| lang=zh | 33 | 29 | 2 | 2 | 24 | 1 | 32 | 1 | 0 | 27 | 1 | 0 |
| lang=en | 33 | 31 | 1 | 1 | 26 | 3 | 30 | 3 | 0 | 26 | 3 | 0 |
| has_node=yes | 33 | 33 | 0 | 0 | 25 | 1 | 32 | 1 | 0 | 29 | 1 | 0 |
| has_node=no | 33 | 27 | 3 | 3 | 25 | 3 | 30 | 3 | 0 | 24 | 3 | 0 |

### Why rewordings were rejected (deferred rows)

- rewrite routes to another intent: 1
- model: none of these: 6

### Accepted rewordings that were WRONG

- `which dependencies are still waiting for a request to cross them?` expected [uncovered] → "which dependencies were confirmed by real traffic?" → observed-edges 1.00 (next externals 0.50)
- `有哪些依賴是經過實測確認存在的？` expected [observed-edges] → "哪些依賴有使用證據？" → mentioned-only 0.90 (next db-users 0.67)
- `how does queue-master get its work, via a broker?` expected [async] → "which services does queue-master use?" → dependencies-of 1.00 (next dependents-of 0.94)
- `can you translate the report into Japanese?` expected [none] → "這份報告是怎麼產生的？" → about-report 0.90 (next async 0.42)

### Accepted rewordings (correct)

- `orders 會對哪些服務發出請求？` → "orders 會呼叫誰？" → dependencies-of 1.000
- `ts-preserve-service 送出的呼叫都落在哪些服務？` → "ts-preserve-service 會呼叫誰？" → dependencies-of 1.000
- `which backends does front-end rely on?` → "which services does front-end use?" → dependencies-of 1.000
- `what are the outgoing calls of ts-travel-service?` → "what does ts-travel-service call?" → dependencies-of 1.000
- `balancereader 的請求是從哪些服務來的？` → "balancereader 的上游是誰？" → dependents-of 1.000
- `有哪些服務會去呼叫 payment？` → "哪些服務會用到 payment？" → dependents-of 1.000
- `ts-station-service 是被哪些服務呼叫的？` → "ts-station-service 被誰呼叫？" → dependents-of 1.000
- `name the services that send requests into userservice` → "which services use userservice?" → dependents-of 1.000
- `who are the consumers of the catalogue API?` → "which services use the catalogue API?" → dependents-of 0.869
- `accounts-db 當機的話，前端哪些功能會受牽連？` → "accounts-db 出問題誰會受影響？" → impact-of 1.000
- `carts 如果升級改壞了，連帶會壞掉哪些服務？` → "改了 carts 會波及哪些服務？" → impact-of 1.000
- `ts-basic-service 一旦無法回應，會拖垮哪些服務？` → "ts-basic-service 停掉之後哪些功能會壞？" → impact-of 1.000
- `if ledger-db went offline, which services would feel it?` → "if ledger-db is unavailable, what stops working?" → impact-of 1.000
- `what is the fallout of a user service outage?` → "who is affected if user service fails?" → impact-of 0.853
- `balancereader 開起來之前，哪些依賴要先就緒？` → "balancereader 啟動前需要哪些東西在跑？" → startup-needs 1.000
- `ts-security-service 上線前必須先確認哪些服務可用？` → "ts-security-service 啟動前需要哪些東西在跑？" → startup-needs 1.000
- `for ts-rebook-service to come up cleanly, which services must already be up?` → "what must be running before ts-rebook-service works?" → startup-needs 1.000
- `front-end 和 shipping 之間隔了幾個服務？` → "front-end 跟 shipping 之間怎麼走？" → path 1.000
- `trace the hops between contacts and accounts-db` → "is there a path from contacts to accounts-db?" → path 1.000
- `what chain of calls leads from ts-preserve-service to ts-price-service?` → "is there a path from ts-preserve-service to ts-price-service?" → path 1.000
- `這次導流量後還有幾條宣告的邊是空的？` → "有宣告但沒有流量經過的呼叫有哪些？" → uncovered 1.000
- `有哪些依賴關係目前還缺流量佐證？` → "哪些邊沒有被流量覆蓋？" → uncovered 1.000
- `what share of the declared edges did the load test actually hit?` → "which declared calls never got traffic during the load test?" → uncovered 0.810
- `give me the edges no request has touched yet` → "which declared edges have no traffic?" → uncovered 1.000
- `列出實際跑流量時真的有發生的呼叫` → "有流量的邊是哪些？" → observed-edges 1.000
- `show the edges backed by live mesh telemetry` → "which edges did Istio actually observe?" → observed-edges 1.000
- `which of the arrows are solid, i.e. verified at runtime?` → "which calls have runtime evidence?" → observed-edges 1.000
- `哪些微服務有自己的資料表？` → "哪些服務有用到資料庫？" → db-users 1.000
- `上 k8s 的時候哪些服務應該排在最後部署？` → "應該先部署哪個服務？" → deploy-order 1.000
- `圖上有、但叢集裡根本沒跑起來的是哪些？` → "叢集裡缺了哪些服務？" → undeployed 1.000
- `程式碼裡提到但實際沒部署上去的元件有哪些？` → "有引用但沒在跑的服務有哪些？" → undeployed 1.000
- `what is missing from the namespace compared to the code?` → "what is referenced but missing from the namespace?" → undeployed 0.691
- `系統有沒有對叢集外的網址發請求？` → "有沒有打到叢集外面的服務？" → externals 1.000
- `list the third-party endpoints the system relies on` → "what are the external dependencies?" → externals 1.000
- `誰把訊息丟進 rabbitmq、誰從裡面拿？` → "誰在發事件、誰在收？" → async 1.000
- `do any services talk through a queue instead of HTTP?` → "any queues or events?" → async 1.000
- `which services emit or listen for events?` → "any queues or events?" → async 1.000
- `那些用點線畫的依賴代表什麼、有幾條？` → "點線的邊是哪些？" → mentioned-only 1.000
- `show me the dotted, unconfirmed edges` → "which edges are mentioned only?" → mentioned-only 1.000
- `are any dependencies based on docs alone rather than code?` → "哪些依賴沒有使用證據？" → mentioned-only 1.000
- `報告裡哪個收集步驟失敗了？` → "報告是怎麼產生的？" → about-report 1.000
- `這次分析是靜態分析還是有跑叢集？` → "這是 greenfield 還是 runtime？" → about-report 1.000
- `which parts of this report were written by the LLM and which by code?` → "how was this report produced?" → about-report 1.000
- `give me a picture of only orders, payment and what connects them` → "show only orders and payment in a picture" → subgraph 1.000
- `render ledgerwriter together with everything one hop away as a small graph` → "畫出 ledgerwriter 相關的部分圖" → subgraph 1.000
- `show me a diagram of just ts-preserve-service, ts-seat-service and ts-order-service` → "show only ts-preserve-service, ts-seat-service and ts-order-service in a picture" → subgraph 0.888

### Rows where the pipeline and the planner differ

- `有哪些依賴關係目前還缺流量佐證？` expected [uncovered]: planner wrong [mentioned-only], pipeline correct (accepted-correct)
- `which dependencies are still waiting for a request to cross them?` expected [uncovered]: planner correct [uncovered], pipeline wrong (accepted-wrong)
- `有哪些依賴是經過實測確認存在的？` expected [observed-edges]: planner correct [observed-edges], pipeline wrong (accepted-wrong)
- `how does queue-master get its work, via a broker?` expected [async]: planner correct [dependencies-of|async], pipeline wrong (accepted-wrong)
- `那些用點線畫的依賴代表什麼、有幾條？` expected [mentioned-only]: planner wrong [uncovered], pipeline correct (accepted-correct)
- `show me the dotted, unconfirmed edges` expected [mentioned-only]: planner wrong [uncovered], pipeline correct (accepted-correct)
- `報告裡哪個收集步驟失敗了？` expected [about-report]: planner none [], pipeline correct (accepted-correct)
- `這次分析是靜態分析還是有跑叢集？` expected [about-report]: planner none [], pipeline correct (accepted-correct)
- `which parts of this report were written by the LLM and which by code?` expected [about-report]: planner none [], pipeline correct (accepted-correct)
- `can you translate the report into Japanese?` expected [none]: planner correct [], pipeline wrong (accepted-wrong)

## Part 2 — the second-opinion band

- band = confident rows below H whose score is within band_score of T, or whose lead is within band_lead of M; grid band_score 0.00–0.30 × band_lead 0.00–0.20 step 0.01, plus "all below H" (652 points)
- verdict: AGREE = the rewording routes confidently to the same intent; DISAGREE = to another intent (the planner decides); FAILED = no usable rewording (route stands)
- lost = correct route overruled, planner also correct; harm = correct route overruled, planner not; caught = wrong route overruled; missed = wrong route in the band but not overruled; outside = wrong route outside the band
- rule: harm = 0 at the current thresholds; then most caught (current + loose sets); then fewest rows in the band at the current thresholds; ties to the narrower band; nothing caught anywhere → band off

**Selected: 0.06/0.02**

### current (T=0.51 M=0.10 H=0.80)

| band | confident | confident-wrong | in band | agree | disagree | failed | lost | harm | caught | missed | outside |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.00/0.00 | 36 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.03/0.03 | 36 | 0 | 11 | 11 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.05/0.05 | 36 | 0 | 18 | 18 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.10/0.10 | 36 | 0 | 24 | 24 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| all below H | 36 | 0 | 31 | 31 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0.06/0.02 (selected) | 36 | 0 | 9 | 9 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

### ≤1 wrong (T=0.51 M=0.08 H=0.80)

| band | confident | confident-wrong | in band | agree | disagree | failed | lost | harm | caught | missed | outside |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.00/0.00 | 44 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |
| 0.03/0.03 | 44 | 1 | 15 | 14 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| 0.05/0.05 | 44 | 1 | 19 | 18 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| 0.10/0.10 | 44 | 1 | 28 | 27 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| all below H | 44 | 1 | 39 | 38 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| 0.06/0.02 (selected) | 44 | 1 | 12 | 11 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |

### pre-9/14 (T=0.58 M=0.04 H=0.85)

| band | confident | confident-wrong | in band | agree | disagree | failed | lost | harm | caught | missed | outside |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.00/0.00 | 51 | 10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 10 |
| 0.03/0.03 | 51 | 10 | 22 | 15 | 6 | 1 | 0 | 0 | 6 | 2 | 2 |
| 0.05/0.05 | 51 | 10 | 30 | 21 | 8 | 1 | 0 | 0 | 8 | 2 | 0 |
| 0.10/0.10 | 51 | 10 | 36 | 27 | 8 | 1 | 0 | 0 | 8 | 2 | 0 |
| all below H | 51 | 10 | 50 | 41 | 8 | 1 | 0 | 0 | 8 | 2 | 0 |
| 0.06/0.02 (selected) | 51 | 10 | 28 | 19 | 8 | 1 | 0 | 0 | 8 | 2 | 0 |

### The confident-wrong rows of the loose sets: where they sit and what the second opinion said

| thresholds | question | expected | routed | score − T | lead − M | in selected band | verdict |
|---|---|---|---|---|---|---|---|
| ≤1 wrong | `有哪些依賴關係目前還缺流量佐證？` | [uncovered] | mentioned-only 0.619 | +0.109 | +0.013 | true | DISAGREE |
| pre-9/14 | `what are the outgoing calls of ts-travel-service?` | [dependencies-of] | dependents-of 0.784 | +0.204 | +0.003 | true | DISAGREE |
| pre-9/14 | `ts-basic-service 一旦無法回應，會拖垮哪些服務？` | [impact-of] | dependencies-of 0.622 | +0.042 | +0.011 | true | DISAGREE |
| pre-9/14 | `有哪些依賴關係目前還缺流量佐證？` | [uncovered] | mentioned-only 0.619 | +0.039 | +0.053 | true | DISAGREE |
| pre-9/14 | `列出實際跑流量時真的有發生的呼叫` | [observed-edges] | uncovered 0.633 | +0.053 | +0.037 | true | DISAGREE |
| pre-9/14 | `有哪些依賴是經過實測確認存在的？` | [observed-edges] | mentioned-only 0.655 | +0.075 | +0.013 | true | AGREE |
| pre-9/14 | `圖上有、但叢集裡根本沒跑起來的是哪些？` | [undeployed] | uncovered 0.584 | +0.004 | +0.004 | true | DISAGREE |
| pre-9/14 | `誰把訊息丟進 rabbitmq、誰從裡面拿？` | [async] | dependents-of 0.597 | +0.017 | +0.016 | true | DISAGREE |
| pre-9/14 | `show me the dotted, unconfirmed edges` | [mentioned-only] | observed-edges 0.592 | +0.012 | +0.028 | true | DISAGREE |
| pre-9/14 | `are any dependencies based on docs alone rather than code?` | [mentioned-only] | externals 0.582 | +0.002 | +0.019 | true | DISAGREE |
| pre-9/14 | `what programming language is ledgerwriter written in?` | [none] | dependents-of 0.631 | +0.051 | +0.016 | true | FAILED |

### Correct routes the selected band would overrule at the current thresholds

(none)
