# Sock Shop — get-dependency-analysis 測試計畫

## 狀態
- Sock Shop 已部署於 minikube，namespace: `sock-shop`，全部 Pod Running（55d）
- 尚未執行 Discord 指令測試

---

## 部署資訊

| 服務 | 類型 | Port |
|------|------|------|
| front-end | NodePort | 80:30001 |
| catalogue | ClusterIP | 80 |
| catalogue-db | ClusterIP | 3306 (MySQL) |
| carts | ClusterIP | 80 |
| carts-db | ClusterIP | 27017 (MongoDB) |
| orders | ClusterIP | 80 |
| orders-db | ClusterIP | 27017 (MongoDB) |
| payment | ClusterIP | 80 |
| shipping | ClusterIP | 80 |
| queue-master | ClusterIP | 80 |
| rabbitmq | ClusterIP | 5672, 9090 |
| session-db | ClusterIP | 6379 (Redis) |
| user | ClusterIP | 80 |
| user-db | ClusterIP | 27017 (MongoDB) |

---

## 安裝指令（已完成，僅供參考）

```bash
kubectl create namespace sock-shop
kubectl apply -n sock-shop -f https://raw.githubusercontent.com/microservices-demo/microservices-demo/master/deploy/kubernetes/complete-demo.yaml
```

### 清除指令
```bash
kubectl delete namespace sock-shop
```

---

## 測試指令（在 Discord 輸入）

```
get-dependency-analysis repo_name=microservices-demo/microservices-demo namespace=sock-shop
```

---

## 預期 Discord Bot 輸出（Ground Truth）

Bot 最後應輸出格式如下：

```
## Microservice Dependency Analysis Report
**Repository:** `microservices-demo/microservices-demo` | **Namespace:** `sock-shop`

（以下為 LLM 生成內容）
```

### 正確輸出必須包含的依賴關係

**同步呼叫（HTTP/REST）：**
- `front-end` → `catalogue`（瀏覽商品）
- `front-end` → `carts`（購物車）
- `front-end` → `orders`（訂單）
- `front-end` → `payment`（付款）
- `front-end` → `user`（使用者/登入）
- `front-end` → `shipping`（運送）
- `orders` → `carts`（結帳時讀取購物車）
- `orders` → `user`（取得用戶資料）
- `orders` → `payment`（付款）
- `orders` → `shipping`（建立出貨）

**非同步通訊（AMQP/RabbitMQ）：**
- `shipping` → `queue-master` → `rabbitmq`（出貨事件）

**資料庫依賴（各服務獨立）：**
- `catalogue` → `catalogue-db`（MySQL）
- `carts` → `carts-db`（MongoDB）
- `orders` → `orders-db`（MongoDB）
- `user` → `user-db`（MongoDB）
- `front-end` → `session-db`（Redis，session 儲存）

**注意事項：**
- `catalogue-db` 是 MySQL（port 3306），其餘 db 是 MongoDB（port 27017）
- `session-db` 是 Redis（port 6379）
- `rabbitmq` 有兩個 port：5672（AMQP）、9090（管理介面）
- K8s 中沒有 Istio VirtualService / DestinationRule（Sock Shop 不用 Istio）

### 判定標準
| 項目 | 必須正確 |
|------|---------|
| front-end 的 6 個下游服務 | ✅ |
| orders 的 4 個下游服務 | ✅ |
| shipping → rabbitmq 非同步鏈 | ✅ |
| 各服務對應正確的 DB 類型 | ✅ |
| 沒有虛構不存在的依賴 | ✅ |
