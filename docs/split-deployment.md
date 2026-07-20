# 拆機部署：ChatOps4Msa 側 ↔ Kubernetes 叢集側

## 為什麼要拆

**原本的部署**：舊實驗機只有 **6 核 / 21GB**，上面同時跑著 k8s + Istio + sock-shop（14 個服務，
每個都多一個 Envoy sidecar），而 ChatOps4Msa 與 k8s-mcp-server **也都以 pod 跑在同一個叢集內**。

也就是說，**量測工具與受測系統共用同一份記憶體與 CPU**。連線本身是通的（k8s Service DNS），
失效模式是**資源耗盡** —— pod 被 OOMKill／驅逐，MCP 連線反覆中斷。

這不只是「不穩」：**被驅逐的 pod 不會產生 Istio 指標**。也就是說，量測工具因為跟受測系統搶資源，
反而劣化了它要蒐集的訊號 —— **量測工具正在擾動它所量測的對象**。

（k8s-mcp-server 當時是靜默 exit 255，什麼都不留下，所以只看得到「又斷線了」。我們替它加上
traceback logging 之後才診斷得動。）

**新機器是 12 核 / 31GB**，比舊機器寬裕，但 train-ticket 有 40+ 個微服務，每個還要塞一個
Envoy sidecar。粗估：

| 項目 | 估算 |
|---|---|
| 40 個 Spring Boot（app 512Mi + sidecar ~80Mi） | ~24 GB |
| ~10 個資料庫（不注入 sidecar） | ~4 GB |
| istiod + ingress gateway | ~1.5 GB |
| k8s 控制平面 | ~2 GB |
| **合計** | **~31 GB** |

**正好把新機器的 31GB 吃滿，沒有餘裕。** 因此 ChatOps4Msa（JVM）與它的支援容器必須搬離實驗機 ——
在舊的 21GB 機器上，train-ticket 是**根本不可能**的。

---

## 架構

```
實驗機（LAN，例如 192.168.100.106）        ChatOps4Msa 機器（同一 LAN）
────────────────────────────────        ──────────────────────────────
k8s 叢集                                 chatops4msa (JVM)
Istio (istiod / ingress gateway)         k8s-mcp-server
Istio 的 Prometheus                      rabbitmq
sock-shop / train-ticket                 k6（透過 docker.sock 起 sibling 容器）
```

**k8s-mcp-server 放在 ChatOps4Msa 這側。** 它只是一個幫忙執行 kubectl 的服務，不需要跑在叢集裡、
也不需要跟叢集同一台機器 —— 它只需要一份 kubeconfig 和連得到 API server。

---

## ChatOps4Msa 側

`docker-compose.yaml` 已改為預設只啟動核心三個服務：

```bash
docker compose up -d          # chatops4msa + k8s-mcp-server + rabbitmq
```

其餘全部收進 profile，不會自動啟動：

```bash
docker compose --profile monitoring up -d   # prometheus/grafana/exporters（ChatOps4Msa 自己的監控）
docker compose --profile bookinfo up -d     # compose 版 bookinfo
docker compose --profile trello up -d       # trello
```

> **注意**：compose 裡的 `productpage` / `details` / `ratings` / `reviews` 是 **compose 版的
> bookinfo**，跟你部署到 Kubernetes 裡的 bookinfo 是兩套不同的東西。依賴分析看的是 k8s 那套，
> 所以這四個預設不啟動。

### kubeconfig

把 kubeconfig 放到 `kube/config`（已 git-ignore）。`server:` **必須是實驗機的 LAN 位址**，
不能是 `127.0.0.1` —— 在容器裡那會指向容器自己。詳見 [`kube/README.md`](../kube/README.md)。

### secret.yml

新增了一個設定，**必須填**：

```yaml
prometheus_host_url:       https://anyu-chatops4msa-prom.soselab.tw   # ChatOps4Msa 自己的監控
istio_prometheus_host_url: http://192.168.100.106:30090                 # ← 叢集裡 Istio 的 Prometheus
```

**這兩個是不同的 Prometheus，不能共用。** `istio_requests_total` 這個指標**只有** Istio 的
Prometheus 有；ChatOps4Msa 自己那個 Prometheus 抓的是 node-exporter / cadvisor，
查 `istio_requests_total` 只會回空結果 —— 而且不會報錯，只是依賴分析的 runtime 邊全部是空的。

---

## 叢集側：必須對外開放三個東西

拆機之後，ChatOps4Msa 那台必須連得到實驗機的三樣東西。**少一個，功能就殘廢：**

| # | 服務 | 建議暴露方式 | 誰用 | 沒有的話 |
|---|---|---|---|---|
| 1 | K8s API server (6443) | 見下方 | k8s-mcp-server | 讀不到 services / pods / VirtualService |
| 2 | Istio 的 Prometheus | NodePort 30090 | `istio_prometheus_host_url` | 拿不到 runtime 觀察到的依賴邊 |
| 3 | Istio ingress gateway | NodePort | k6 打流量（`entry_url`） | 沒流量 → Istio 觀察不到任何邊 |

### 1. API server 對外

**kind** 預設只綁 `127.0.0.1`，必須在建叢集時指定：

```yaml
# kind-config.yaml
networking:
  apiServerAddress: "192.168.100.106"   # 實驗機的 LAN IP
  apiServerPort: 6443
```

```bash
kind create cluster --name mcp --config kind-config.yaml
```

**建議改用 k3s**：實驗機是 Linux x86_64，而 kind 是「在 Docker 裡再跑一層 k8s」，
多一層開銷；k3s 是原生輕量發行版，API server 預設就對外，也省掉上面這個設定。
以 train-ticket 的規模，這層開銷省下來是值得的。

### 2. Istio 的 Prometheus 對外

```bash
kubectl -n istio-system patch svc prometheus \
  -p '{"spec":{"type":"NodePort","ports":[{"port":9090,"nodePort":30090}]}}'
```

---

## 驗證順序（不要跳）

```bash
# 1. kubeconfig 的位址對不對
grep server: kube/config           # 不能是 127.0.0.1

# 2. k8s-mcp-server 真的連得到叢集   ← 最關鍵的一步
docker compose up -d k8s-mcp-server
docker compose exec k8s-mcp-server kubectl get ns

# 3. Istio 的 Prometheus 連得到，而且真的有 istio 指標
curl 'http://192.168.100.106:30090/api/v1/query?query=istio_requests_total' | head -c 300
#    ↑ 如果 result 是空陣列，代表還沒有流量流過 mesh，或你連錯 Prometheus

# 4. 才跑依賴分析
```

第 2 步如果 `kubectl get ns` 列得出 namespace，整條鏈路就是通的。
如果連不上，就是 kubeconfig 裡的位址不對 —— **先修那個，不要往下查別的**。

---

## train-ticket 之前一定要先修的事

**Spring Boot 的 JVM heap 沒設上限。** 如果 pod 沒設 `resources.limits.memory`，
容器看到的是整台機器的記憶體，而 JVM 預設 `MaxRAMPercentage` 是 25%：

```
每個 JVM 最大 heap = 31GB × 25% ≈ 7.7GB   （新機器）
```

一個服務就敢要 7.7GB。train-ticket 有 40 個 Spring Boot，全都這樣的話，GC 會變得極度懶惰、
記憶體一路膨脹。**這一條不修，train-ticket 100% 不可能跑起來。**

（在舊的 21GB 機器上這也是個放大器：sock-shop 的 Java 服務 —— carts、orders、shipping、
queue-master —— 加上 ChatOps4Msa 自己，每個 JVM 都能要走 21GB × 25% ≈ 5.2GB。但那台的
主因仍是**容量本來就不夠**：6 核要扛 14 個服務 × 2 容器，再加上量測工具自己。）

每個 Java 服務都要加：

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
resources:
  requests: { memory: "320Mi", cpu: "100m" }
  limits:   { memory: "512Mi" }
```

（`MaxRAMPercentage=60` 是相對於 **limit** 的 60%，即 512Mi × 60% ≈ 300MB heap。
小 heap 用 `UseSerialGC` 比 G1 省。）

其他：

- 所有服務副本數設為 1
- 資料庫**不要**注入 sidecar（它們不是分析目標）
- ⚠️ **但受分析的服務不能拿掉 sidecar** —— Istio 就是靠 sidecar 觀察依賴的
- ⚠️ **分批部署**：40 個 Envoy 光 request 就 4 核，加上 40 個 JVM 同時啟動會把 12 核打爆。
  一次上 10 個服務，不要 `kubectl apply` 全部一次上

---

## 驗證順序（系統層級）

1. 修好 heap 設定，在實驗機重測 **sock-shop** → 確認不再 swap
2. 拆機，確認上面三個端口都通，跑完整一輪依賴分析（含 Pause / Resume）
3. **才**上 train-ticket，而且分批部署

不要直接跳 train-ticket。sock-shop 是對照組：它跑得動代表管線是好的，
直接上 train-ticket 出問題你會分不清是資源不夠還是設定錯。
