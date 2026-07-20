# 從零架設：完整步驟

兩台機器，同一個 LAN。

```
機器 A：實驗機（重）                    機器 B：ChatOps4Msa（輕）
─────────────────────────              ────────────────────────────
k3s + Istio                            chatops4msa (JVM)
Istio 的 Prometheus                    k8s-mcp-server
sock-shop / train-ticket               rabbitmq
                        ← LAN ─────────  k6
```

**先做 A，全部驗證通過，才做 B。** B 完全依賴 A 提供的三個端口，A 沒好就做 B 只會浪費時間。

本文假設：
- 機器 A 的 LAN IP 是 `192.168.100.106` —— **請把全文的這個位址換成你自己的**
- 兩台都是 Linux x86_64

---

# 機器 A：實驗機（叢集側）

## A-0. 前置

```bash
# 確認 LAN IP，後面到處都要用
hostname -I | awk '{print $1}'

# 關掉 swap。這很重要：
# 開著 swap 的話，記憶體不足會變成「整台機器慢到爆但不會死」（就是你之前遇到的「卡到不行」），
# 極難診斷。關掉之後，記憶體不足會直接 OOM kill，你立刻知道是誰爆了。
sudo swapoff -a
sudo sed -i '/ swap / s/^/#/' /etc/fstab     # 重開機後仍生效
free -h                                       # 確認 Swap 那行是 0
```

## A-1. 安裝 k3s

用 k3s 而不是 kind。kind 是「在 Docker 裡再跑一層 k8s」，多一層開銷；k3s 是原生輕量發行版，
API server 預設就對外，省掉一堆設定。以 train-ticket 的規模，這層開銷省下來是值得的。

```bash
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="\
  --disable=traefik \
  --write-kubeconfig-mode 644 \
  --tls-san 192.168.100.106" sh -
```

三個參數的意思：

| 參數 | 為什麼 |
|---|---|
| `--disable=traefik` | k3s 預設裝 Traefik 並佔用 80/443 埠，**會跟 Istio 的 ingress gateway 打架**。必須關掉 |
| `--tls-san 192.168.100.106` | 把 LAN IP 加進 API server 的憑證 SAN。**沒這個的話，機器 B 連過來會出現憑證錯誤** |
| `--write-kubeconfig-mode 644` | 讓一般使用者讀得到 kubeconfig，不用一直 sudo |

### ✅ 關卡 1

```bash
kubectl get nodes
```

要看到一個 `Ready` 的節點。**沒有就不要往下。**

## A-2. 安裝 Istio

```bash
curl -L https://istio.io/downloadIstio | sh -
cd istio-*/
export PATH=$PWD/bin:$PATH

# 用 default profile，不要用 demo。
# demo 會多裝 egressgateway 並開 debug 級 log，在資源吃緊的機器上是浪費。
istioctl install --set profile=default -y
```

### ✅ 關卡 2

```bash
kubectl -n istio-system get pods
```

`istiod` 和 `istio-ingressgateway` 都要是 `Running`。

k3s 內建的 servicelb 會自動把 `istio-ingressgateway`（LoadBalancer 型別）綁到節點 IP，
所以它應該直接就在 `192.168.100.106:80` 上：

```bash
kubectl -n istio-system get svc istio-ingressgateway
# EXTERNAL-IP 欄位應該出現 192.168.100.106
```

## A-3. 安裝 Istio 的 Prometheus，並對外開放

⚠️ **這是整份文件最容易搞錯的地方。** 這個 Prometheus 跟 ChatOps4Msa 自己那個
Prometheus **是兩個不同的東西**。`istio_requests_total` 這個指標**只有這一個**有。

```bash
# 還在 istio-*/ 目錄裡
kubectl apply -f samples/addons/prometheus.yaml

# 對外開放成 NodePort 30090，讓機器 B 連得到
kubectl -n istio-system patch svc prometheus -p \
  '{"spec":{"type":"NodePort","ports":[{"port":9090,"targetPort":9090,"nodePort":30090}]}}'
```

### ✅ 關卡 3

```bash
curl -s 'http://192.168.100.106:30090/api/v1/query?query=up' | head -c 200
```

要看到 `"status":"success"`。

（現在查 `istio_requests_total` 會是空的 —— 因為還沒有任何流量流過 mesh。這是正常的，
等 A-4 部署完並打流量之後就會有。）

## A-4. 部署受測系統（先 sock-shop）

**先 sock-shop，不要直接上 train-ticket。** sock-shop 是你的對照組：它跑得動，
代表整條管線是好的。直接上 train-ticket 出問題，你會分不清是資源不夠還是設定錯。

```bash
kubectl create namespace sock-shop

# 關鍵：開啟 sidecar 自動注入。
# 沒有這行，Istio 看不到任何服務間的呼叫，依賴分析的 runtime 邊會全部是空的。
kubectl label namespace sock-shop istio-injection=enabled

git clone https://github.com/microservices-demo/microservices-demo.git
kubectl apply -f microservices-demo/deploy/kubernetes/complete-demo.yaml
```

還要開一個 Gateway 讓流量從 ingress 進得來（k6 要用）：

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: sock-shop-gateway
  namespace: sock-shop
spec:
  selector:
    istio: ingressgateway
  servers:
    - port: { number: 80, name: http, protocol: HTTP }
      hosts: ["*"]
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: sock-shop
  namespace: sock-shop
spec:
  hosts: ["*"]
  gateways: [sock-shop-gateway]
  http:
    - route:
        - destination:
            host: front-end
            port: { number: 80 }
EOF
```

### ✅ 關卡 4

```bash
# 每個 pod 要是 2/2（app + istio-proxy sidecar）。
# 如果是 1/1，代表 sidecar 沒注入 → 回去檢查 A-4 的 label
kubectl -n sock-shop get pods

# 從 ingress 進得去
curl -I http://192.168.100.106/
```

**`READY` 欄位必須是 `2/2`。** 這是最常出錯的地方，是 `1/1` 的話整個依賴分析都是白做的。

## A-5. 打流量，確認 Istio 真的觀察到了

```bash
for i in $(seq 1 50); do curl -s http://192.168.100.106/ > /dev/null; done
```

### ✅ 關卡 5（最關鍵的一關）

```bash
curl -s 'http://192.168.100.106:30090/api/v1/query?query=istio_requests_total' \
  | python3 -m json.tool | head -30
```

**`result` 陣列必須有東西。** 如果是空的 `[]`，代表：
- sidecar 沒注入（回關卡 4），或
- 流量沒真的流過（回 A-5），或
- 你連到錯的 Prometheus

**這一關過不了，機器 B 做再多也沒用** —— 依賴分析的 runtime 邊就是從這個查詢來的。

## A-6. 匯出 kubeconfig 給機器 B

```bash
# k3s 寫出來的 kubeconfig 裡 server 是 127.0.0.1，對機器 B 沒用，要換成 LAN IP
sed 's|https://127.0.0.1:6443|https://192.168.100.106:6443|' \
  /etc/rancher/k3s/k3s.yaml > /tmp/kubeconfig-for-chatops

grep server: /tmp/kubeconfig-for-chatops     # 確認是 192.168.100.106，不是 127.0.0.1
```

把 `/tmp/kubeconfig-for-chatops` 複製到機器 B。

---

# 機器 B：ChatOps4Msa 側

## B-1. 前置

```bash
# Docker + compose plugin
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git openjdk-17-jdk maven
sudo usermod -aG docker $USER
newgrp docker      # 或重新登入

git clone <你的 ChatOps4Msa-Pipeline repo>
cd ChatOps4Msa-Pipeline
```

## B-2. kubeconfig

```bash
mkdir -p kube
cp /path/to/kubeconfig-for-chatops kube/config

grep server: kube/config     # 必須是 192.168.100.106，不能是 127.0.0.1
```

⚠️ **不能是 `127.0.0.1`** —— 在 k8s-mcp-server 容器裡面，`127.0.0.1` 指的是容器自己，不是你的叢集。

### ✅ 關卡 6

```bash
kubectl --kubeconfig kube/config get ns
```

從機器 B 直接連得到機器 A 的叢集。**連不上就不要往下**（防火牆？`--tls-san` 忘了加？）。

## B-3. application.properties

```bash
cp src/main/resources/application-template.properties src/main/resources/application.properties
```

編輯，把空白的填起來：

```properties
discord.application.token=<你的 Discord bot token>
discord.application.id=<...>
discord.guild.id=<...>
discord.channel.chatops.id=<...>

openai.api.key=<你的 OpenAI key>
openai.api.model=gpt-4o          # 依賴分析會做結構化抽取，3.5 品質不夠

# RabbitMQ 跑在同一個 compose 網路，用 service 名稱
rabbitmq.host.url=http://rabbitmq:15672
spring.rabbitmq.host=rabbitmq
spring.rabbitmq.username=admin
spring.rabbitmq.password=soselab

# 斷點檢查點（已對應 compose 的 volume 掛載）
dependency.state.dir=./dep-state
```

## B-4. secret.yml

```bash
cp src/main/resources/capability/secret_template.yml \
   src/main/resources/capability/secret.yml
```

編輯：

```yaml
k6_test_script_path: /k6                                    # compose 把 ./k6 掛在 /k6

prometheus_host_url: https://your-chatops-prometheus        # ChatOps4Msa 自己的監控（跟依賴分析無關）
istio_prometheus_host_url: http://192.168.100.106:30090       # ← 機器 A 的 Istio Prometheus

github_access_token: Bearer ghp_xxxx
grafana_url: https://your-grafana
```

⚠️ **`prometheus_host_url` 和 `istio_prometheus_host_url` 是兩個不同的 Prometheus，不要填一樣。**
填錯的話依賴分析的 runtime 邊會全部是空的，而且**不會報錯**，只會看起來像「沒觀察到依賴」。

## B-5. 建置並啟動

```bash
mvn package -DskipTests
docker compose up -d --build
```

預設只會起三個：`chatops4msa`、`k8s-mcp-server`、`rabbitmq`。

（如果還要 ChatOps4Msa 自己的監控：`docker compose --profile monitoring up -d`）

### ✅ 關卡 7（最關鍵的一關）

```bash
docker compose exec k8s-mcp-server kubectl get ns
```

**這行要列得出機器 A 的 namespace（含 `sock-shop`、`istio-system`）。**

通了，代表 `ChatOps4Msa → k8s-mcp-server → 機器 A 的 API server` 整條鏈路都是好的。
不通的話，先修這個，不要去查別的地方。

### ✅ 關卡 8

```bash
docker compose logs chatops4msa | grep -E "tree-sitter ready|Started ChatOps4Msa"
```

兩行都要有。`tree-sitter ready` 代表原生庫載入成功（這是刻意設計成開機就檢查的，
不然會等到分析跑到一半才炸）。

---

# 最後：跑第一次依賴分析

在 Discord 頻道下：

```
/get-dependency-analysis
  repo_name=microservices-demo/microservices-demo
  namespace=sock-shop
  entry_url=http://192.168.100.106/
```

會依序看到：

1. `DEBUG: extracting dependencies from source code` → 程式碼抽取（clone + tree-sitter / LLM）
2. `DEBUG: connected DeepWiki` → 文件分析
3. `DEBUG: connected K8s MCP` → 讀 k8s / Istio 資源
4. k6 打流量 → 查 Prometheus
5. **完整性檢查 + 兩顆按鈕**（Generate report / Pause & supplement）

如果完整性檢查說 runtime 邊有缺，按 **Pause & supplement** → 自己去多打一些流量 →
按 **Resume**。Resume 只會重查 Prometheus 和重跑完整性檢查，**DeepWiki 五問和 repo clone
不會再跑一次**。

---

# 之後上 train-ticket 前，一定要先做的事

train-ticket 有 40+ 個 Spring Boot 微服務。**在部署之前**，先確認每個服務都有設記憶體上限：

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
resources:
  requests: { memory: "320Mi", cpu: "100m" }
  limits:   { memory: "512Mi" }
```

**為什麼非做不可**：pod 沒設 `resources.limits.memory` 的話，容器看到的是整台機器的記憶體
（31GB），而 JVM 預設 `MaxRAMPercentage` 是 25% → **每個 JVM 最大 heap ≈ 7.7GB**。
40 個服務都這樣，GC 會變得極度懶惰，記憶體一路膨脹。

這極可能就是你先前 sock-shop「卡到不行」的真正原因 —— 12 核 / 31GB 跑只有 ~14 個服務的
sock-shop 本來不該卡。（現在 swap 已經關掉了，同樣的問題會直接 OOM kill，而不是拖垮整台機器。）

其他：

- 所有服務副本數設 1
- 資料庫**不要**注入 sidecar（不是分析目標，省一批 Envoy）
- ⚠️ **但受分析的服務不能拿掉 sidecar** —— Istio 就是靠 sidecar 觀察依賴的
- ⚠️ **分批部署**：40 個 JVM 同時啟動會把 12 核打爆。一次上 10 個服務

記憶體預算（修好 heap 之後）：40 × ~600Mi + 資料庫 + istiod + 控制平面 ≈ **31GB，剛好吃滿**。
這就是為什麼 ChatOps4Msa 必須搬到機器 B。

---

# 排錯速查

| 症狀 | 先查哪裡 |
|---|---|
| `docker compose exec k8s-mcp-server kubectl get ns` 連不上 | kubeconfig 的 `server:` 是不是 127.0.0.1？機器 A 建叢集時有沒有加 `--tls-san`？ |
| k8s-mcp-server 連得到叢集，但 `toolkit-mcp-connect` 失敗 | `K8S_MCP_TRANSPORT=streamable-http` 有沒有設？沒設的話它是 stdio，**根本不開 HTTP 埠** |
| runtime 邊全部是空的，但沒報錯 | `istio_prometheus_host_url` 是不是誤填成 `prometheus_host_url`？pod 是不是 `1/1`（sidecar 沒注入）？ |
| pod 是 `1/1` 不是 `2/2` | namespace 少了 `istio-injection=enabled`；補上後要重建 pod |
| 程式碼抽取回 `Collection status: FAILED` | 看 Ledger 裡的 clone 指令輸出。私有 repo 需要憑證 |
| 機器慢到不行 | `free -h` 看 swap（應該是 0）；`kubectl get pods -A -o custom-columns='NAME:.metadata.name,LIM:.spec.containers[*].resources.limits.memory'` 看有沒有一片 `<none>` |
