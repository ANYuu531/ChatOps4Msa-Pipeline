# graph-edit live probe · nashtech-garage/yas

| 情境 | 預期 | 結果 |
|---|---|---|
| 1 · 漏邊：kafka-connect → postgres | 開啟新增、證據列出 Debezium JSON；選 1 → 建議 db → 寫入 | PASS |
| 2 · 漏邊：storefront-nextjs → storefront（換一種說法、貼自己的出處） | 開啟新增；貼 storefront/.env.production:1 → 讀到那一行、documented → 寫入 | PASS |
| 3 · 英文、名字對不上：cart → Stripe API | 問「你指的是…或新增」；新增 → 外部主機 → 沒有出處 → inferred 寫入 | PASS |
| 4 · 沒講來源：→ tax | 反問哪個服務呼叫 tax；取消後圖不變 | PASS |
| 5 · 是問題、不是改圖 | 照常回答，不開新增、圖不變 | PASS |
| 6 · 新增進行中途插問題 | 第二句當問題回答，並提醒新增還開著；取消後圖不變 | PASS |
| 7 · 刪線再撤銷：cart → product | 確認時列出這條線的出處（ProductService.java）；確定後少一條；撤銷後回到原樣 | PASS |
| 8 · 加線再撤銷：kafka-connect → postgres | 寫入後多一條；undo 後回到原樣 | PASS |

8/8 passed

model `gpt-4.1-mini` · graph 38 nodes / 92 edges · router T=0.51

## 1 · 漏邊：kafka-connect → postgres

_預期：開啟新增、證據列出 Debezium JSON；選 1 → 建議 db → 寫入_

**user:** 圖上少了 kafka-connect 連到 postgres 的線，幫我加上

`graph query plan (llm, router subgraph 0.62 (next mentioned-only 0.54) unsure edit-like 0.87): [add-edge(kafka-connect, postgres)]`

**bot:**
```
好，要在圖上新增一條線：`kafka-connect` → `postgres`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 3 處可能的證據（分析時沒有畫成這條線）：
1) `kafka/connects/debezium-order.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
2) `kafka/connects/debezium-product.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
3) `.env:41` POSTGRES_HOST=postgres
   主機／位址的鍵，值是 `postgres` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** 1

`(no plan: the edit dialogue took the message)`

**bot:**
```
這條線是哪一種？
1) sync-http（HTTP／REST 呼叫）
2) grpc（gRPC 呼叫）
3) async（經訊息佇列）
4) db（連資料庫）
5) external（叢集外的主機）
6) startup（只是啟動順序，不是呼叫）
依你選的證據建議「db」，回「好」採用，或回編號。
```
**user:** 好

`(no plan: the edit dialogue took the message)`

**bot:**
```
確認一下要寫進圖的內容：
• `kafka-connect` → `postgres` · db · 信心 documented（有 repo 出處）
• 出處：kafka/connects/debezium-order.json:6
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

`(no plan: the edit dialogue took the message)`

**bot:**
```
✅ 已加進圖：`kafka-connect` → `postgres` (db, documented)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```

**PASS**

## 2 · 漏邊：storefront-nextjs → storefront（換一種說法、貼自己的出處）

_預期：開啟新增；貼 storefront/.env.production:1 → 讀到那一行、documented → 寫入_

**user:** storefront-nextjs 其實有呼叫 storefront，把這條線補上去

`graph query plan (llm, router path 0.66 (next dependents-of 0.57) unsure edit-like 1.00): [add-edge(storefront-nextjs, storefront)]`

**bot:**
```
好，要在圖上新增一條線：`storefront-nextjs` → `storefront`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 3 處可能的證據（分析時沒有畫成這條線）：
1) `storefront/.env.development:1` API_BASE_PATH=http://storefront/api
   位址裡寫著 `storefront` · 工具把這個檔歸給 `storefront` · 靜態層有讀這個檔、但沒畫這條線
2) `storefront/.env.production:1` API_BASE_PATH=http://storefront/api
   位址裡寫著 `storefront` · 工具把這個檔歸給 `storefront` · 靜態層有讀這個檔、但沒畫這條線
3) `yaslocal.yaml:32` - host: storefront
   主機／位址的鍵，值是 `storefront` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** storefront/.env.production:1

`(no plan: the edit dialogue took the message)`

**bot:**
```
我看了 `storefront/.env.production:1`：`API_BASE_PATH=http://storefront/api`，有寫到 `storefront`，算 repo 出處。
這條線是哪一種？
1) sync-http（HTTP／REST 呼叫）
2) grpc（gRPC 呼叫）
3) async（經訊息佇列）
4) db（連資料庫）
5) external（叢集外的主機）
6) startup（只是啟動順序，不是呼叫）
依你選的證據建議「sync-http」，回「好」採用，或回編號。
```
**user:** 好

`(no plan: the edit dialogue took the message)`

**bot:**
```
確認一下要寫進圖的內容：
• `storefront-nextjs` → `storefront` · sync-http · 信心 documented（有 repo 出處）
• 出處：storefront/.env.production:1
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

`(no plan: the edit dialogue took the message)`

**bot:**
```
✅ 已加進圖：`storefront-nextjs` → `storefront` (sync-http, documented)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```

**PASS**

## 3 · 英文、名字對不上：cart → Stripe API

_預期：問「你指的是…或新增」；新增 → 外部主機 → 沒有出處 → inferred 寫入_

**user:** add an edge from cart to Stripe API

`graph query plan (llm, router subgraph 0.40 (next mentioned-only 0.38) unsure edit-like 0.69): [add-edge(cart, Stripe API)]`

**bot:**
```
OK — adding an edge `cart` → `Stripe API` (the first calls or connects to the second; reply "reverse" if that is backwards).
`Stripe API` is not on the graph. Did you mean:
Reply with a number or a node id, or "new" to add `stripe-api` as a node of its own.
```
**user:** new

`(no plan: the edit dialogue took the message)`

**bot:**
```
What is the new node `stripe-api`?
1) service
2) database
3) message broker
4) external host
5) gateway
From its name: service. Reply "ok" to take that, or a number.
```
**user:** 4

`(no plan: the edit dialogue took the message)`

**bot:**
```
I searched the repository for `cart` → `stripe-api` (addresses, host-named keys, the name) and found no trace.
If you have a reference, paste it: `file:line`, a document link, or a sentence; otherwise reply "none" and it is added on your word, at the lowest confidence.
```
**user:** none

`(no plan: the edit dialogue took the message)`

**bot:**
```
OK, no reference: the edge is added on your word, at the lowest confidence (dotted).
What kind of edge is it?
1) sync-http (an HTTP/REST call)
2) grpc (a gRPC call)
3) async (through a message broker)
4) db (a database connection)
5) external (a host outside the cluster)
6) startup (start-up order only, not a call)
Suggested from the target node: sync-http. Reply "ok" to take it, or a number.
```
**user:** ok

`(no plan: the edit dialogue took the message)`

**bot:**
```
Please confirm the change:
• `cart` → `stripe-api` · sync-http · confidence inferred (your word only; drawn dotted)
• new node `stripe-api`（external host）
• references: (none)
• marked as a human edit (provenance: human, by probe)
Reply "yes" to write it, "cancel" to drop it, "reverse" to flip the direction.
```
**user:** yes

`(no plan: the edit dialogue took the message)`

**bot:**
```
✅ Added: `cart` → `stripe-api` (sync-http, inferred)
This report's graph is updated, and the next analysis of this repository applies it again (kept with the repository's human edits). If a runtime run later observes the call, the edge turns solid like any other.
```

**PASS**

## 4 · 沒講來源：→ tax

_預期：反問哪個服務呼叫 tax；取消後圖不變_

**user:** 幫我加一條連到 tax 的線

`graph query plan (llm, router mentioned-only 0.60 (next subgraph 0.58) unsure edit-like 0.85): [add-edge(, tax)]`

**bot:**
```
好，要在圖上新增一條線：（未指定） → `tax`（前者呼叫或連到後者；方向反了請回「反過來」）。
哪個服務呼叫或連到 `tax`？回它的名字。
```
**user:** 取消

`(no plan: the edit dialogue took the message)`

**bot:**
```
好，取消了，圖沒有改。
```

**PASS**

## 5 · 是問題、不是改圖

_預期：照常回答，不開新增、圖不變_

**user:** cart 會不會呼叫 product？

`graph query plan (router path 1.00 (next dependencies-of 0.76)): [path(cart, product)]`

**bot:**
```
根據本報告的靜態分析結果：

- cart 會呼叫 product，且是同步 HTTP 呼叫。
- 此呼叫在程式碼中有明確宣告（confidence=documented），來源於 cart/src/main/java/com/yas/cart/service/ProductService.java 第 26 行。
- 但因為是 greenfield 模式（無執行期觀察），此呼叫尚未被執行期流量觀察到。

總結：cart 會呼叫 product，但目前只在程式碼中宣告，尚無執行期呼叫紀錄。
```

**PASS**

## 6 · 新增進行中途插問題

_預期：第二句當問題回答，並提醒新增還開著；取消後圖不變_

**user:** 圖上少了 kafka-connect 連到 postgres 的線，幫我加上

`graph query plan (llm, router subgraph 0.62 (next mentioned-only 0.54) unsure edit-like 0.87): [add-edge(kafka-connect, postgres)]`

**bot:**
```
好，要在圖上新增一條線：`kafka-connect` → `postgres`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 3 處可能的證據（分析時沒有畫成這條線）：
1) `kafka/connects/debezium-order.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
2) `kafka/connects/debezium-product.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
3) `.env:41` POSTGRES_HOST=postgres
   主機／位址的鍵，值是 `postgres` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** postgres 被誰用？

`graph query plan (router dependents-of 0.84 (next dependencies-of 0.66)): [dependents-of(postgres)]`

**bot:**
```
根據圖和分析，postgres 被以下 16 個服務用作資料庫（db邊，宣告於程式碼或設定中，未觀察到執行期流量）：

- sampledata
- tax
- customer
- inventory
- location
- recommendation
- order
- media
- webhook
- product
- payment
- cart
- promotion
- rating
- identity
- keycloak

這些服務在程式碼或設定檔中宣告連接 postgres，但本次分析為靜態（greenfield），無執行期流量觀察。

_(add edge kafka-connect -> postgres is still open — answer its question, or reply "cancel".)_
```
**user:** 取消

`(no plan: the edit dialogue took the message)`

**bot:**
```
好，取消了，圖沒有改。
```

**PASS**

## 7 · 刪線再撤銷：cart → product

_預期：確認時列出這條線的出處（ProductService.java）；確定後少一條；撤銷後回到原樣_

**user:** 把 cart 到 product 那條線拿掉

`graph query plan (llm, router path 0.61 (next dependents-of 0.57) unsure edit-like 0.95): [remove-edge(cart, product)]`

**bot:**
```
好，要把 `cart` → `product` 這條線從圖上拿掉（方向反了請回「反過來」）。
確認要從圖上拿掉這條線：
• `cart` → `product` · sync-http · 信心 documented · 來源 code
• 出處：code: cart/src/main/java/com/yas/cart/service/ProductService.java:26
• 拿掉後，這個 repo 下次分析若再抽到這條線，也會自動拿掉（記成人工修改，by probe）；回「撤銷」可以復原。
回「確定」拿掉，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

`(no plan: the edit dialogue took the message)`

**bot:**
```
✅ 已從圖上拿掉：`cart` → `product`。
這次分析的圖已更新；這個 repo 下次分析若再抽到這條線，也會自動拿掉（記在 repo 的人工修改裡）。想復原就回「撤銷」。
```
**user:** 撤銷

`(no plan: the edit dialogue took the message)`

**bot:**
```
↩️ 已撤銷：removed `cart` → `product`。圖回到這筆修改之前；這個 repo 記住的人工修改也拿掉了這一筆。
```

**PASS**

## 8 · 加線再撤銷：kafka-connect → postgres

_預期：寫入後多一條；undo 後回到原樣_

**user:** 圖上少了 kafka-connect 連到 postgres 的線，幫我加上

`graph query plan (llm, router subgraph 0.62 (next mentioned-only 0.54) unsure edit-like 0.87): [add-edge(kafka-connect, postgres)]`

**bot:**
```
好，要在圖上新增一條線：`kafka-connect` → `postgres`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 3 處可能的證據（分析時沒有畫成這條線）：
1) `kafka/connects/debezium-order.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
2) `kafka/connects/debezium-product.json:6` "database.hostname": "postgres",
   主機／位址的鍵，值是 `postgres` · 工具把這個檔歸給 `kafka` · 靜態層有讀這個檔、但沒畫這條線
3) `.env:41` POSTGRES_HOST=postgres
   主機／位址的鍵，值是 `postgres` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** 1

`(no plan: the edit dialogue took the message)`

**bot:**
```
這條線是哪一種？
1) sync-http（HTTP／REST 呼叫）
2) grpc（gRPC 呼叫）
3) async（經訊息佇列）
4) db（連資料庫）
5) external（叢集外的主機）
6) startup（只是啟動順序，不是呼叫）
依你選的證據建議「db」，回「好」採用，或回編號。
```
**user:** 好

`(no plan: the edit dialogue took the message)`

**bot:**
```
確認一下要寫進圖的內容：
• `kafka-connect` → `postgres` · db · 信心 documented（有 repo 出處）
• 出處：kafka/connects/debezium-order.json:6
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

`(no plan: the edit dialogue took the message)`

**bot:**
```
✅ 已加進圖：`kafka-connect` → `postgres` (db, documented)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```
**user:** undo

`(no plan: the edit dialogue took the message)`

**bot:**
```
↩️ Undone: `kafka-connect` → `postgres` (db, documented). The graph is back to before this edit, and the repository's remembered edits no longer carry it.
```

**PASS**

