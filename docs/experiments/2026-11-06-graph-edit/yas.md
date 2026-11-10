# graph-edit probe · yas

graph: 38 nodes / 94 edges

## 圖上少了 kafka-connect 連到 postgres 的線，幫我加上

_edge on the graph before: false_

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

**bot:**
```
確認一下要寫進圖的內容：
• `kafka-connect` → `postgres` · db · 信心 documented（有 repo 出處）
• 出處：kafka/connects/debezium-order.json:6
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

**bot:**
```
✅ 已加進圖：`kafka-connect` → `postgres` (db, documented)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```
_applied: {"sourceKind":"","at":"2026-11-06","refs":["kafka/connects/debezium-order.json:6"],"kind":"","confidence":"documented","by":"probe","action":"add-edge","targetKind":"","source":"kafka-connect","type":"db","target":"postgres"}_


## 圖上少了 storefront-nextjs 連到 storefront 的線，幫我加上

_edge on the graph before: false_

**bot:**
```
好，要在圖上新增一條線：`storefront-nextjs` → `storefront`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 4 處可能的證據（分析時沒有畫成這條線）：
1) `automation-ui/storefront/src/main/resources/application.properties:2` app.url=http://storefront/
   位址裡寫著 `storefront` · 工具把這個檔歸給 `storefront` · 靜態層沒有讀這個檔（格式不在讀取範圍）
2) `storefront/.env.development:1` API_BASE_PATH=http://storefront/api
   位址裡寫著 `storefront` · 工具把這個檔歸給 `storefront` · 靜態層有讀這個檔、但沒畫這條線
3) `storefront/.env.production:1` API_BASE_PATH=http://storefront/api
   位址裡寫著 `storefront` · 工具把這個檔歸給 `storefront` · 靜態層有讀這個檔、但沒畫這條線
4) `yaslocal.yaml:32` - host: storefront
   主機／位址的鍵，值是 `storefront` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** 3

**bot:**
```
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

**bot:**
```
確認一下要寫進圖的內容：
• `storefront-nextjs` → `storefront` · sync-http · 信心 documented（有 repo 出處）
• 出處：storefront/.env.production:1
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

**bot:**
```
✅ 已加進圖：`storefront-nextjs` → `storefront` (sync-http, documented)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```
_applied: {"sourceKind":"","at":"2026-11-06","refs":["storefront/.env.production:1"],"kind":"","confidence":"documented","by":"probe","action":"add-edge","targetKind":"","source":"storefront-nextjs","type":"sync-http","target":"storefront"}_


## 圖上少了 order 連到 payment 的線，幫我加上

_edge on the graph before: false_

**bot:**
```
好，要在圖上新增一條線：`order` → `payment`（前者呼叫或連到後者；方向反了請回「反過來」）。
我依這個要求回 repo 找了一次，找到 2 處可能的證據（分析時沒有畫成這條線）：
1) `.env:77` YAS_SERVICES_PAYMENT=http://payment/payment
   位址裡寫著 `payment` · 靜態層有讀這個檔、但沒畫這條線
2) `k8s/charts/yas-configuration/values.yaml:85` payment: http://payment/payment
   位址裡寫著 `payment` · 靜態層有讀這個檔、但沒畫這條線
回編號採用（可多選，例如 1,2）；或貼你自己的出處（`檔案:行號`、網址或一句說明）；或回「沒有」。
```
**user:** storefront-bff/src/main/resources/application-dev.yaml:11

**bot:**
```
我看了 `storefront-bff/src/main/resources/application-dev.yaml:11`：`uri: http://localhost:8081`，這行沒有寫到 `payment`。先記成你的說明，信心標最低。
這條線是哪一種？
1) sync-http（HTTP／REST 呼叫）
2) grpc（gRPC 呼叫）
3) async（經訊息佇列）
4) db（連資料庫）
5) external（叢集外的主機）
6) startup（只是啟動順序，不是呼叫）
依目標節點建議「sync-http」，回「好」採用，或回編號。
```
**user:** 取消

**bot:**
```
好，取消了，圖沒有改。
```

## 圖上少了 cart 連到 stripe-api 的線，幫我加上

_edge on the graph before: false_

**bot:**
```
好，要在圖上新增一條線：`cart` → `stripe-api`（前者呼叫或連到後者；方向反了請回「反過來」）。
`stripe-api` 不在圖上。你指的是：
回編號或節點名稱；或回「新增」把 `stripe-api` 當成新的節點。
```
**user:** 新增

**bot:**
```
新節點 `stripe-api` 是哪一種？
1) 服務
2) 資料庫
3) 訊息佇列
4) 外部主機
5) 閘道
依名字猜是「服務」，回「好」採用，或回編號。
```
**user:** 4

**bot:**
```
我依這個要求回 repo 找了一次（位址、主機名稱的鍵、名字），沒有找到 `cart` → `stripe-api` 的痕跡。
有出處的話請貼上：`檔案:行號`、文件網址，或一句說明；沒有就回「沒有」，會以你的判斷新增、標成最低信心。
```
**user:** 沒有

**bot:**
```
好，沒有出處，這條線會以你的判斷新增、標成最低信心（點線）。
這條線是哪一種？
1) sync-http（HTTP／REST 呼叫）
2) grpc（gRPC 呼叫）
3) async（經訊息佇列）
4) db（連資料庫）
5) external（叢集外的主機）
6) startup（只是啟動順序，不是呼叫）
依目標節點建議「sync-http」，回「好」採用，或回編號。
```
**user:** 好

**bot:**
```
確認一下要寫進圖的內容：
• `cart` → `stripe-api` · sync-http · 信心 inferred（只有你的判斷，畫成點線）
• 新節點 `stripe-api`（外部主機）
• 出處：（無）
• 標成人工新增（provenance: human，probe）
回「確定」寫進圖，回「取消」放棄，回「反過來」改方向。
```
**user:** 確定

**bot:**
```
✅ 已加進圖：`cart` → `stripe-api` (sync-http, inferred)
這次分析的圖已更新；這個 repo 下次分析時也會自動帶入（記在 repo 的人工修改裡）。 之後跑執行期那一層，若真的觀測到這個呼叫，這條線會升成實線。
```
_applied: {"sourceKind":"","at":"2026-11-06","refs":[],"kind":"","confidence":"inferred","by":"probe","action":"add-edge","targetKind":"external","source":"cart","type":"sync-http","target":"stripe-api"}_


## 圖上少了 cart 連到 product 的線，幫我加上

_edge on the graph before: true_

**bot:**
```
圖上已經有 `cart` → `product` 了（sync-http，信心 documented，出處 `code: cart/src/main/java/com/yas/cart/service/ProductService.java:26`）。
```

## 圖上少了  連到 tax 的線，幫我加上

_edge on the graph before: false_

**bot:**
```
好，要在圖上新增一條線：（未指定） → `tax`（前者呼叫或連到後者；方向反了請回「反過來」）。
哪個服務呼叫或連到 `tax`？回它的名字。
```
**user:** 取消

**bot:**
```
好，取消了，圖沒有改。
```

## 幫我在圖上新增節點 kafka

_edge on the graph before: false_

**bot:**
```
`kafka` 已經在圖上了，不用新增。要加它的線的話，可以說「新增 A → kafka」。
```
