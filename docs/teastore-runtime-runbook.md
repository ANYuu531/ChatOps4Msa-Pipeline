# TeaStore — runtime 依賴分析 runbook（機器 A，不搶 port 版）

目標：把 TeaStore 部署到既有叢集（機器 A，k3s + Istio + Prometheus:30090，已跑 sock-shop / 曾跑 Bank of Anthos），
讓 Istio 觀測服務間流量，用工具跑**完整 runtime 依賴分析**（namespace 有值 → runtime 模式）。

## 為什麼是 TeaStore

靜態層在它身上是最誠實的失敗（`docs/generalization-2026-09-22.md`：業務邊 recall 0.08）：五個服務的所有呼叫都寫成
`loadBalanceRESTOperation(Service.PERSISTENCE, …)`，目標是 enum、經自家 registry 動態解析，程式碼裡沒有任何 host 字串。
這正是 runtime 層存在的理由。期望：**靜態 1/13 → runtime 13/13**，而且不改工具、不改 TeaStore 程式碼。

真實邊（`docs/generalization/truth/teastore.tsv`）：webui→auth/image/persistence/recommender、auth/image/recommender→persistence、
五服務→registry、persistence→teastore-db（TCP）。

## 0. 前置盤點（和 BoA runbook 一樣）

```bash
kubectl get svc -A | grep -Ei 'loadbalancer|nodeport'          # 誰佔 host port
kubectl get gateway -A -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,HOSTS:.spec.servers[*].hosts
kubectl -n istio-system get svc istio-ingressgateway -o custom-columns=TYPE:.spec.type,PORTS:.spec.ports[*].nodePort
# 已知：ingressgateway http NodePort = 31403、Prometheus = 30090、節點 IP = 192.168.100.106
```

資源：7 個 Deployment（registry、db、persistence、auth、image、recommender、webui），manifest 沒寫 requests/limits；
實測經驗每個約 300–600Mi、JVM 起來 1–2 分鐘。比 BoA 輕，比 train-ticket 輕很多。

## 1. namespace + Istio 注入

```bash
kubectl create namespace teastore
kubectl label namespace teastore istio-injection=enabled
```

## 2. 部署（用 ClusterIP 版，把 webui 的 NodePort 改掉）

TeaStore 自帶 `examples/kubernetes/teastore-clusterip.yaml`：六個 Service 都是 ClusterIP，**只有 webui 是 `type: NodePort` 綁 30080**（第 291 行起）。
30080 不撞 31403/30090，但為了和 BoA 一致（走既有 ingressgateway、專屬 host），改成 ClusterIP：

```bash
git clone --depth 1 https://github.com/DescartesResearch/TeaStore.git
cd TeaStore
kubectl apply -n teastore -f examples/kubernetes/teastore-clusterip.yaml
kubectl patch svc teastore-webui -n teastore -p '{"spec":{"type":"ClusterIP"}}'
```

> 不要用 `teastore-ribbon*.yaml`（Ribbon 客戶端負載平衡會繞過 Service，Istio 一樣看得到但 destination_workload 會散成 pod）；
> `teastore-all.yaml` 是單體版，不用。

## 3. 專屬 host 走既有 ingressgateway

存成 `teastore-ingress.yaml`：

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: teastore-gateway
  namespace: teastore
spec:
  selector:
    istio: ingressgateway
  servers:
  - port: { number: 80, name: http, protocol: HTTP }
    hosts: ["teastore.local"]
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: teastore-webui
  namespace: teastore
spec:
  hosts: ["teastore.local"]
  gateways: ["teastore-gateway"]
  http:
  - route:
    - destination:
        host: teastore-webui
        port: { number: 8080 }
```

```bash
kubectl apply -f teastore-ingress.yaml
```

## 4. 等 pods，並確認 registry 有人註冊

```bash
kubectl get pods -n teastore -w        # 期望 7 個都 2/2
# TeaStore 的服務啟動時向 registry 註冊；註冊靠 HOST_NAME env（manifest 已寫成 Service 名）
kubectl -n teastore port-forward svc/teastore-registry 18080:8080 &
curl -s http://localhost:18080/tools.descartes.teastore.registry/rest/services/ | head -c 400
# 期望列出 tools.descartes.teastore.{auth,image,persistence,recommender,webui}
```

**坑（預期）**：
- persistence 要等 db Ready 才會 Ready；剛起來會對 `teastore-db:3306` 重試，正常。
- webui 第一次載入會叫 persistence 生資料（`/tools.descartes.teastore.webui/` 首頁看到商品才算好）。
- 若服務互相找不到：TeaStore 靠 registry 回傳「host:port」再直連；有 sidecar 時 host 是 Service 名（HOST_NAME），沒問題；
  若你看到 registry 回的是 pod IP，代表 HOST_NAME 沒帶到，補 `kubectl set env deployment/teastore-auth -n teastore HOST_NAME=teastore-auth`（其餘同理）。

## 5. 入口對應（跑 bot 的那台）

```bash
echo "192.168.100.106  teastore.local" | sudo tee -a /etc/hosts
curl -s -o /dev/null -w "%{http_code}\n" http://teastore.local:31403/tools.descartes.teastore.webui/
# 期望 200；注意 context path，TeaStore 的 webui 不在 /
```

## 6. 跑 runtime 依賴分析

```
repo_name = DescartesResearch/TeaStore
namespace = teastore
entry_url = http://teastore.local:31403/tools.descartes.teastore.webui/
auth_hint = none     # 登入是 user/password（persistence 產的假帳號：user2 / password）
```

流量腳本要打到的頁面（都在 webui，SSR 形狀，見 `traffic_scenario_generation.txt` 的 FRONTEND SHAPE (B)）：
`/`（首頁：webui→persistence、image、recommender）、`/category?category=2&page=1`（persistence、image）、
`/product?id=7`（persistence、image、recommender）、`/loginAction`（POST，webui→auth→persistence）、
`/cartAction`（加入購物車→auth）、`/order`（結帳→persistence）。
TeaStore 自帶 JMeter 腳本 `examples/jmeter/teastore_browse_nogui.jmx`，`ExampleRequestHarvester` 認得 `jmeter`/`loadtest` 慣例，會餵給流量 prompt。

## 7. 對照 ground truth

```bash
curl -s -G 'http://192.168.100.106:30090/api/v1/query' \
  --data-urlencode 'query=sum by(source_workload,destination_workload)(istio_requests_total{reporter="destination",destination_workload_namespace="teastore"})'
# 期望 13 條：webui→{auth,image,persistence,recommender,registry}、auth/image/recommender→{persistence,registry}、persistence→registry
curl -s -G 'http://192.168.100.106:30090/api/v1/query' \
  --data-urlencode 'query=sum by(source_workload,destination_service_name)(istio_tcp_connections_opened_total{reporter="source",source_workload_namespace="teastore"})'
# 期望 persistence→teastore-db
```

報告要能講的一句話：**靜態層 1/13 不是工具壞了，是這個系統把依賴放在 registry 裡；runtime 層量到 13/13，圖上實線。**
把 runtime 那張圖存到 `docs/generalization/teastore-runtime.mmd`，和靜態版並排。

## 8. 收掉

```bash
kubectl delete namespace teastore
kubectl delete -f teastore-ingress.yaml --ignore-not-found
```

## 備忘

- TeaStore 沒有 JWT secret、沒有 GCP tracing，比 BoA 少兩個坑。
- Kieker 監控變體（`*-kieker*.yaml`）會多一個 RabbitMQ，這次不要，靜態圖裡那幾條 `→ teastore-kieker-rabbitmq` 是變體邊。
- 若 bot 在機器 B：照 BoA 的做法，`git pull` 本分支後 `docker compose build --no-cache chatops4msa && up -d --force-recreate`，因為這次改了抽取與合併程式碼。
