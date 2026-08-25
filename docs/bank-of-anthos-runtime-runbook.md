# Bank of Anthos — runtime 依賴分析 runbook(不搶 port 版)

目標:把 Bank of Anthos 部署到既有叢集(machine A,k3s + Istio + Prometheus:30090,已跑 sock-shop),
讓 Istio 觀測服務間流量,然後用工具跑**完整 runtime 依賴分析**(namespace 有值 → runtime 模式)。

## 上次撞 port 的根因(只有這一條)

`kubernetes-manifests/frontend.yaml` 的 frontend Service 是 **`type: LoadBalancer`**。
在 k3s 上 LoadBalancer 會叫 klipper/ServiceLB 綁**節點 host port 80**,和既有的 Istio ingress / sock-shop 撞。
**其他所有 Service 都是 ClusterIP(叢集內部,不佔 host port)**。

修法:frontend 改 **ClusterIP**,流量走**既有的 Istio ingressgateway**(port 已開,共用),
以**專屬 host** 路由,不新增任何 host port、也不和 sock-shop 的 `*` 路由互撞。

> 注意:BoA 自帶的 `extras/istio/frontend-ingress.yaml` 用 `hosts: "*"`。若 sock-shop 也用 `*` 綁在
> 同一個 ingressgateway:80,兩者會衝突 → 本 runbook 改用專屬 host `bankofanthos.local`。

---

## 0. 前置盤點(先確認沒有衝突點)

```bash
# 既有會佔 host port 的東西(LoadBalancer / NodePort)
kubectl get svc -A | grep -Ei 'loadbalancer|nodeport'

# 既有的 Gateway,看有沒有人已用 hosts:"*" 綁在 ingressgateway:80
kubectl get gateway -A -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,HOSTS:.spec.servers[*].hosts

# 記下 Istio ingressgateway 的位址與 http port(這是我們「共用、已開」的入口)
kubectl -n istio-system get svc istio-ingressgateway \
  -o custom-columns=TYPE:.spec.type,CLUSTERIP:.spec.clusterIP,PORTS:.spec.ports[*].nodePort
# 記下 http(80)對應的 nodePort(例如 3xxxx),與節點 IP(192.168.100.106)
#  => 之後 INGRESS = http://192.168.100.106:31403
```

## 1. 建 namespace + 開 Istio 注入(runtime 觀測的前提)

```bash
kubectl create namespace bank-of-anthos
kubectl label namespace bank-of-anthos istio-injection=enabled
```

## 2. 建 JWT secret(必須,否則 6 個服務卡 ContainerCreating)

BoA 有現成的:

```bash
# 在 clone 下來的 BoA repo 根目錄
kubectl apply -n bank-of-anthos -f extras/jwt/jwt-secret.yaml
```

## 3. 部署應用(frontend 改 ClusterIP、略過 loadgenerator)

loadgenerator 可略:工具會自己驅動流量,省 250m/1Gi。

```bash
# 套用除 loadgenerator 外的所有 manifest
kubectl apply -n bank-of-anthos \
  $(ls kubernetes-manifests/*.yaml | grep -v loadgenerator | sed 's/^/-f /' | tr '\n' ' ')

# 關鍵:frontend 從 LoadBalancer 改 ClusterIP(這就是上次搶 port 的那條)
kubectl patch svc frontend -n bank-of-anthos -p '{"spec":{"type":"ClusterIP"}}'
```

## 4. 用專屬 host 走既有 ingressgateway(不新增 port、不撞 sock-shop)

把以下存成 `boa-ingress.yaml` 再 apply(**用專屬 host,不用 `*`**):

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: frontend-gateway
  namespace: bank-of-anthos
spec:
  selector:
    istio: ingressgateway          # 共用既有的 ingressgateway,不新開 port
  servers:
  - port: { number: 80, name: http, protocol: HTTP }
    hosts: ["bankofanthos.local"]  # 專屬 host,和 sock-shop 的路由分開
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: frontend-ingress
  namespace: bank-of-anthos
spec:
  hosts: ["bankofanthos.local"]
  gateways: ["frontend-gateway"]
  http:
  - route:
    - destination:
        host: frontend             # -> frontend.bank-of-anthos.svc:80
        port: { number: 80 }
```

```bash
kubectl apply -f boa-ingress.yaml
```

## 5. 等 pods 起來

```bash
kubectl get pods -n bank-of-anthos -w
# 期望:accounts-db / ledger-db 先 Ready,接著 6 個服務都 2/2(含 istio-proxy sidecar)Ready
# 若某服務 CrashLoop:多半是 jwt-key secret 沒建(回步驟 2)或 db 還沒好(再等)
```

### 5b. 確認 DB 邊真的量得到(2026-08-25 新增)

BoA 本來就有兩個真的資料庫(`accounts-db` / `ledger-db`,PostgreSQL StatefulSet),
但 **Istio 只對 HTTP/gRPC 產 `istio_requests_total`**——PostgreSQL 連線是不透明 TCP,
只會出現在 `istio_tcp_*`。所以 DB 邊要另外一條查詢才看得到(工具已經會自動查,
這裡是人工確認資料源是活的):

```bash
# 兩個 db pod 是不是 2/2(有 sidecar)。就算沒有 sidecar 也還量得到——
# 工具查的是 reporter="source"(呼叫端的 sidecar 報的),此時 destination_workload
# 會是 unknown,改用 destination_service_name 認身分。
kubectl get pods -n bank-of-anthos -l app=accounts-db -o wide

# 這條就是 DB 邊的證據(工具用的是同一條)
curl -s 'http://192.168.100.106:30090/api/v1/query?query=sum%20by(source_workload,destination_workload,destination_service_name)(istio_tcp_connections_opened_total{reporter="source",source_workload_namespace="bank-of-anthos"})' | head -c 600
# 期望看到 userservice/contacts -> accounts-db、ledgerwriter/balancereader/transactionhistory -> ledger-db
```

**注意連線池**:計數是「開了幾條連線」不是「查了幾次」,而且連線池在服務啟動時就建好。
所以 **DB 一定要比應用先 Ready**;若是先跑起應用才有 DB,`rollout restart` 那些服務再量。

## 6. 讓工具打得到入口(Host 對應)

VirtualService 以 `bankofanthos.local` 比對,所以**跑工具的那台機器**要能把該 host 解到 ingress:

```bash
# 在「跑 ChatOps4Msa bot」的機器上加一行 hosts(node IP = ingressgateway 所在節點)
echo "192.168.100.106  bankofanthos.local" | sudo tee -a /etc/hosts

# 驗證入口通(用步驟 0 記下的 nodePort)
curl -s -o /dev/null -w "%{http_code}\n" http://bankofanthos.local:31403/
# 期望 200
```

## 7. 跑 runtime 依賴分析(namespace 有值 → runtime 模式)

在 Discord 下指令(或對應入口):

```
repo_name = GoogleCloudPlatform/bank-of-anthos
namespace = bank-of-anthos          # 有值 → 走 runtime(非 greenfield)
entry_url = http://bankofanthos.local:31403/
auth_hint = none
```

工具會:抽程式碼 → DeepWiki → 抓 k8s/Istio → 驅動流量 → Prometheus 量 istio_requests_total → 出圖 + 覆蓋率。
期望能看到的業務邊(對照靜態 probe 的結果):
`frontend → ledgerwriter / balancereader / transactionhistory / contacts / userservice`、`ledgerwriter → balancereader`,
外加 runtime 才有的 `istio-ingressgateway → frontend`。

**外加 DB 邊(實線)**:`userservice/contacts → accounts-db`、
`ledgerwriter/balancereader/transactionhistory → ledger-db`。這幾條以前只能是虛線
(程式碼宣告),現在因為多查了 in-mesh TCP,會升級成 runtime 觀測到的實線。
報告裡它們算成**獨立的一個「Data layer」覆蓋率**,不混進業務邊那個百分比。

## 8. 收掉(釋放資源/確保不殘留)

```bash
kubectl delete namespace bank-of-anthos
kubectl delete -f boa-ingress.yaml --ignore-not-found
# frontend 已是 ClusterIP,沒有 host-level 殘留;namespace 刪掉即清乾淨
```

---

## 實測踩到的兩個坑(2026-08-12,已驗證修法)

### 坑 1:上次「撞 port」根因是 **Docker 容器佔 host 80/443,不是 k8s**
`kubectl get svc` 只看得到 k8s 管的 host port;真正佔 80/443 的是直接跑在主機上的 **docker 容器**(`docker-proxy`),`kubectl` 完全看不到。要看主機層:

```bash
sudo ss -tlnp        # 或 sudo lsof -i -P -n | grep LISTEN
# 本案例:0.0.0.0:80 / :443 都是 docker-proxy 佔著
```

BoA 上次用 `type: LoadBalancer` → k3s klipper 去搶 host port 80 → 撞到那個 docker 容器。
**本 runbook 的做法(frontend ClusterIP + 走既有 istio-ingressgateway 的 NodePort 31403)本來就不碰任何 host port**(NodePort 走 iptables DNAT,不是 userspace listener),所以與 docker 的 80/443 完全無關、不會撞。

### 坑 2:全服務 `1/2 CrashLoopBackOff` = **BoA 預設送 GCP Cloud Trace,離開 GCP 沒憑證會崩**
Java(`StackdriverTraceAutoConfiguration` → `stackdriverSender`)和 Python(`CloudTraceSpanExporter`)都因 `Your default credentials were not found`(GCP ADC)在啟動初始化 tracing 時崩。`ENABLE_TRACING`/`ENABLE_METRICS` 是**寫死在各 deployment 內嵌 env=`"true"`**(不是 configmap,改 configmap 沒用)。修法:

```bash
kubectl set env deployment --all -n bank-of-anthos ENABLE_TRACING=false ENABLE_METRICS=false
# 只動 6 個 app Deployment(DB 是 StatefulSet 不受影響),自動滾動重啟 → 全部 2/2 Running
```

與依賴分析無關:我們的觀測靠 **Istio sidecar 的 `istio_requests_total`**,不是 BoA 自己的 Stackdriver 追蹤,所以關掉毫無影響。

> 已知環境值(本叢集):istio-ingressgateway http NodePort = **31403**,Prometheus = **30090**,無任何既有 Gateway(不撞)。

---

## 備忘

- **資源**:略過 loadgenerator 後,requests 約 ~1.3 CPU / ~2Gi(limits 較高,ledger-db 記憶體 limit 1Gi 最大)。若節點吃緊,先確認 sock-shop 的用量。
- **為何不是 greenfield**:BoA 這條要驗 runtime(Istio 觀測 + 覆蓋率)。工具端的 greenfield gating 只在 **namespace 留空** 時啟用;這裡 namespace 有值,runtime 路徑照舊、完全不受影響。
- **fallback**:若最後仍無法部署(資源/權限),可退而用 **greenfield**(namespace 留空)只出靜態圖——但那不是這次 BoA 的目標。
- **k8s-mcp-server**:工具靠它跑 kubectl,確認它在叢集內可連(dependency.yml 預設 `http://k8s-mcp-server:8000`)。
