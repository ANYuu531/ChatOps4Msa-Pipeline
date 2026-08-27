# 真環境驗證：完整步驟與指令（A 對話問人 + B 真 DB + C 分層）

一次跑完，用 **Bank of Anthos** 同時驗三包。選 BoA 的理由：它本來就有兩個真的資料庫
（`accounts-db` / `ledger-db`，PostgreSQL），所以**驗 B 不用先部署 MySQL**；而 A 的場景
（deposit 需要 `account_num`）本來就在 BoA。petclinic + MySQL 是 B 的第二個證據，**可選**，放最後。

## 兩台機器的角色

| | 機器 A（叢集） | 機器 B（工具） |
|---|---|---|
| 位址 | `192.168.100.106` | 跑 ChatOps4Msa 的那台 |
| 上面有 | k3s + Istio + Prometheus(`30090`) + sock-shop | docker compose（chatops4msa / k8s-mcp-server / rabbitmq） |
| 已知值 | ingressgateway http NodePort = **31403** | — |

每個步驟都標了在哪台跑。

---

# 階段 0 · 機器 B：更新工具

### 0-1. 不用重跑 Discord 註冊指令

slash command 的註冊（`CQLRegister` 裡那三行，預設就是註解掉的）只跟 **capability 的
operation 名稱和 parameter** 有關。這次只改了 operation 的 *body*，
`get-dependency-analysis` / `resume-dependency-analysis` 的參數一個都沒動。

新增的 **Button 和 Modal 在 Discord 不需要事先註冊**——是訊息送出時即時帶上的元件。

**所以只要重新 build + 重啟。**

### 0-2. 取得程式碼（注意：不在 `main` 上）

這次的改動在 **`feat/greenfield-static-dependency-graph`** 這個 branch，不是 `main`。
如果機器 B 停在 `main`，`git pull` 會回「Already up to date」——**不是沒更新，是拉錯 branch**。

```bash
# 機器 B
cd <ChatOps4Msa-Pipeline>

git branch --show-current          # 先看現在在哪
git fetch origin
git checkout feat/greenfield-static-dependency-graph
git pull

# 確認拿到了
git log --oneline -1
```

> 這個 branch 上與本次驗證有關的是最新那幾筆（從 `3e0cdb6` 起）：Tier 3 對話問人、
> in-mesh TCP 的 DB 邊、圖分層、DepWeaver 命名、draw.io 圖、Tier 1 開關。

### 0-3. 重建並重啟（**只**重編 chatops4msa）

```bash
# 機器 B
docker compose build chatops4msa
docker compose up -d --no-deps chatops4msa
```

必須重編——不然跑的還是舊 image，新的 `ModalListener`（A）和 TCP 查詢（B）都不會生效。

> ### ⚠ 不要用 `docker compose up -d --build chatops4msa`
>
> Compose v2 的 `--build` 會把 **`depends_on` 的服務一起重編**，也就是連 `k8s-mcp-server`
> 一起。那個 image 是從**另一個 repo 的 fork** build 的（`${K8S_MCP_SRC:-../k8s-mcp-server}`），
> 它的 Dockerfile 會去 GitHub 抓 istio / argocd 這些 CLI：**要跑一個多小時**，而且很容易在
> `curl` 掛掉（實測 `exit 92` = HTTP/2 framing error，抓 argocd 時斷掉），整個 build 就白費。
>
> 這次的改動**一行都沒動 k8s-mcp-server**，它的 image 和容器本來就在跑，
> 所以用 `build chatops4msa` +`up --no-deps` 明確地只碰這一個。
>
> 先確認依賴都活著（兩者都該是 `Up`）：
>
> ```bash
> docker compose ps
> ```
>
> 只有在 `k8s-mcp-server` 或 `rabbitmq` **沒在跑**時才需要動它們：
> `docker compose up -d rabbitmq k8s-mcp-server`（沿用既有 image，不重編）。
> 真的必須重編 k8s-mcp-server 而又一直 `exit 92`，就到那個 fork 的
> `deploy/docker/Dockerfile` 把 curl 改成 `curl -sSLf --http1.1 --retry 5 --retry-delay 3`
> （`--http1.1` 繞開 framing 錯誤，`-f` 讓 404 當場失敗而不是把錯誤頁存成執行檔）。

### 0-4. 確認起來了

```bash
# 機器 B
docker compose logs --tail=80 chatops4msa
```

三個檢查點：

- 看到 `[DEBUG] JDA START!`
- **沒有** capability / toolkit 的驗證錯誤（啟動時會對 `toolkit_verify.yml` 驗一遍，
  新的 `toolkit-depstate-ask-button`、`toolkit-depstate-supplied-values` 都已註冊）
- 這輪**不該**看到 `example-request harvesting is disabled`（Tier 1 第一輪要開著）

---

# 階段 1 · 機器 A：部署 Bank of Anthos

### 1-0. 兩個前置檢查（做完可能整個階段 1 都不用做）

**(a) kubectl 連得到嗎**

```bash
# 機器 A
kubectl get nodes
```

如果報 `The connection to the server localhost:8080 was refused`，那是**沒有 kubeconfig**
（kubectl 找不到 config 就退回預設的 `localhost:8080`，所以 port 才是 8080 不是 6443）。
k3s 的 config 在 `/etc/rancher/k3s/k3s.yaml`，只有 root 讀得到：

```bash
# 機器 A
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
chmod 600 ~/.kube/config
kubectl get nodes
```

> 這份 config 的 `server:` 是 `https://127.0.0.1:6443`，**在機器 A 本機用是對的，不要改**。
> 只有要給機器 B 的那份（`kube/config`）才需要換成 LAN IP。兩份不要搞混。

**(b) BoA 是不是已經部署過了**

```bash
# 機器 A
kubectl get pods -n bank-of-anthos
kubectl get gateway,virtualservice -A
kubectl -n bank-of-anthos get deploy frontend \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="ENABLE_TRACING")].value}{"\n"}'
```

**如果 8 個 pod 都 `2/2 Running`、`ENABLE_TRACING` 是 `false`** → 部署是好的，
**跳過 1-1 ~ 1-9，直接做 1-10**。

再多看一眼 pod 的 `AGE`：**兩個 db 要比 6 個 app「老」**（先起來），DB 邊才量得到。
反過來的話（app 比 db 舊）就要 `rollout restart` 那幾個 app——理由見 1-7。

**如果 Gateway 的 `HOSTS` 是 `["*"]`**（BoA 自帶的 `extras/istio/frontend-ingress.yaml`，
不是本文 1-9 的專屬 host）→ 那也能用，而且更省事：`entry_url` 直接用
`http://192.168.100.106:31403/`，**階段 2 整個跳過**（不用改 `/etc/hosts`）。
唯一要確認的是**沒有別人也綁 `*`** 在同一個 ingressgateway:80（上面第二條指令看得到），
否則兩邊會互搶路由。

### 1-1. 前置盤點

```bash
# 機器 A
kubectl get svc -A | grep -Ei 'loadbalancer|nodeport'
kubectl get gateway -A -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,HOSTS:.spec.servers[*].hosts

# 確認 ingressgateway 的 http nodePort（已知是 31403，變了就以這裡為準）
kubectl -n istio-system get svc istio-ingressgateway \
  -o custom-columns=TYPE:.spec.type,CLUSTERIP:.spec.clusterIP,PORTS:.spec.ports[*].nodePort
```

### 1-2. clone BoA（沒有的話）

```bash
# 機器 A
git clone https://github.com/GoogleCloudPlatform/bank-of-anthos.git
cd bank-of-anthos
```

### 1-3. namespace + sidecar 注入

```bash
# 機器 A
kubectl create namespace bank-of-anthos
kubectl label namespace bank-of-anthos istio-injection=enabled
```

### 1-4. JWT secret（不建的話 6 個服務會卡 ContainerCreating）

```bash
# 機器 A · 在 BoA repo 根目錄
kubectl apply -n bank-of-anthos -f extras/jwt/jwt-secret.yaml
```

### 1-5. 部署（略過 loadgenerator，frontend 改 ClusterIP）

```bash
# 機器 A · 在 BoA repo 根目錄
kubectl apply -n bank-of-anthos \
  $(ls kubernetes-manifests/*.yaml | grep -v loadgenerator | sed 's/^/-f /' | tr '\n' ' ')

# 關鍵：frontend 預設是 LoadBalancer，在 k3s 上會去搶 host port 80（上次撞 port 的根因）
kubectl patch svc frontend -n bank-of-anthos -p '{"spec":{"type":"ClusterIP"}}'
```

### 1-6. 等**資料庫**先 Ready（順序很重要，見下）

```bash
# 機器 A
kubectl -n bank-of-anthos rollout status statefulset/accounts-db --timeout=300s
kubectl -n bank-of-anthos rollout status statefulset/ledger-db  --timeout=300s
```

### 1-7. 關掉 GCP tracing —— 順便解決連線池的順序問題

BoA 預設把 trace 送 GCP Cloud Trace，離開 GCP 沒憑證會讓所有服務 `1/2 CrashLoopBackOff`。
`ENABLE_TRACING` 是寫死在各 deployment 的 env（改 configmap 沒用）：

```bash
# 機器 A
kubectl set env deployment --all -n bank-of-anthos ENABLE_TRACING=false ENABLE_METRICS=false
```

> **這一步同時解決了 B 的順序問題**：它會讓 6 個 app Deployment 滾動重啟，而此時
> 資料庫（步驟 1-6）已經 Ready。連線池只在服務啟動時建一次——app 必須在 DB 之後起來，
> 那條連線才會被 sidecar 看見。所以**先等 DB Ready、再 set env**，順序不能反。
> （DB 是 StatefulSet，不受 `deployment --all` 影響。）

### 1-8. 等全部 2/2

```bash
# 機器 A
kubectl get pods -n bank-of-anthos -w
# 期望：6 個服務都 2/2 Running（app + istio-proxy sidecar），兩個 db 也 Running
```

### 1-9. 走既有 ingressgateway（專屬 host，不新增 port）

```bash
# 機器 A · 存成 boa-ingress.yaml
cat > boa-ingress.yaml <<'EOF'
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: frontend-gateway
  namespace: bank-of-anthos
spec:
  selector:
    istio: ingressgateway          # 共用既有的 ingressgateway，不新開 port
  servers:
  - port: { number: 80, name: http, protocol: HTTP }
    hosts: ["bankofanthos.local"]  # 專屬 host，和 sock-shop 的路由分開
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
        host: frontend
        port: { number: 80 }
EOF

kubectl apply -f boa-ingress.yaml
```

### 1-10. ★ 確認 DB 邊量得到（B 的關鍵檢查點）

```bash
# 機器 A（或任何連得到 Prometheus 的機器）
curl -s -G 'http://192.168.100.106:30090/api/v1/query' \
  --data-urlencode 'query=sum by(source_workload,destination_workload,destination_service_name)(istio_tcp_connections_opened_total{reporter="source",source_workload_namespace="bank-of-anthos"})' | head -c 800
```

**期望**：看到 `userservice` / `contacts` → `accounts-db`，
`ledgerwriter` / `balancereader` / `transactionhistory` → `ledger-db`。

**如果 `result` 是空的**（服務都健康卻沒有 TCP 指標）→ 順序問題，重啟 app 再查：

```bash
# 機器 A
kubectl -n bank-of-anthos rollout restart deploy \
  userservice contacts balancereader transactionhistory ledgerwriter frontend
kubectl -n bank-of-anthos rollout status deploy/frontend --timeout=300s
# 等 30 秒讓 Prometheus scrape，再跑一次上面那條 curl
```

---

# 階段 2 · 機器 B：讓工具打得到入口

> **Gateway 的 HOSTS 是 `["*"]` 的話，整段跳過**——任何 Host 都會路由到 frontend，
> `entry_url` 直接用 `http://192.168.100.106:31403/` 就行（見 1-0(b)）。
> 下面這段只在用了 1-9 那個專屬 host `bankofanthos.local` 時才需要。

```bash
# 機器 B
echo "192.168.100.106  bankofanthos.local" | sudo tee -a /etc/hosts

# 驗證入口通
curl -s -o /dev/null -w "%{http_code}\n" http://bankofanthos.local:31403/
# 期望 200
```

> 工具跑在容器裡，容器不吃主機的 `/etc/hosts`。如果 bot 說連不到，改用
> `entry_url = http://192.168.100.106:31403/` 並在指令裡帶 Host——或直接在
> compose 的 `chatops4msa` 加 `extra_hosts: ["bankofanthos.local:192.168.100.106"]` 再重啟。

---

# 階段 3 · 第一輪：正常跑（Tier 1 開著）→ 驗 B 和 C

在 Discord 下指令：

```
repo_name = GoogleCloudPlatform/bank-of-anthos
namespace = bank-of-anthos
entry_url = http://bankofanthos.local:31403/
auth_hint = none
```

跑完（收集約數分鐘）按 **Generate report**。檢查三件事：

| 驗什麼 | 期望看到 |
|---|---|
| **B · DB 邊** | 覆蓋率訊息多一段 **Data layer**（獨立百分比）；圖上 `userservice → accounts-db` 等是**實線**、標籤 `db` |
| **C · 分層** | 圖從左右散開變成**上下分層**：ingress → frontend → 後端服務 → data stores |
| **D · 名字** | PNG 標題列寫 `DepWeaver — dependency graph · namespace: bank-of-anthos`；Discord 標題是 `DepWeaver · Dependency Graph` |
| **A · 不濫發** | 大概率**不會**出現「Provide values」按鈕——Tier 1 從 loadgenerator 抓到了 deposit 欄位，就不該問人。**這是正確行為，不是失敗** |

> 業務邊覆蓋率應該和上次差不多（6/7，缺 `ledgerwriter → balancereader` 這條難從外部觸發的
> 後端內部邊）。**DB 邊不會讓這個數字變動**——那是刻意的，見文末。

**把這一輪的圖和覆蓋率數字留著**，它是第二輪的對照組。

---

# 階段 4 · 第二輪：關掉 Tier 1 → 驗 A（工具開口問人）

這是**對照實驗**：同一個專案、同一段旅程，有沒有 repo 自己的範例請求，差別在哪。
關掉之後 deposit 的欄位就沒人給了，才會走到 Tier 3。

```bash
# 機器 B
DEPENDENCY_EXAMPLE_REQUESTS_ENABLED=false docker compose up -d --no-deps chatops4msa

# 確認生效（沒有這行就是沒吃到，別往下做）
docker compose logs --tail=200 chatops4msa | grep "example-request harvesting is disabled"
```

> compose 只轉發它知道的變數，所以 `docker-compose.yaml` 裡已經宣告了
> `DEPENDENCY_EXAMPLE_REQUESTS_ENABLED=${DEPENDENCY_EXAMPLE_REQUESTS_ENABLED:-true}`，
> 預設 on。上面那行指令是把它覆寫成 false。
> （注意：那行 grep 要在**跑完一次分析之後**才會出現——訊息是在抽取階段印的。）

用**完全一樣的四個參數**再跑一次（checkpoint 是每個使用者一份，新的 run 會覆蓋舊的）。

期望的流程：

1. 流量報告裡出現 **`[WAIT]`** 的步驟——那個請求被扣住了，**沒有半填就送出**
2. 收集結束時多一顆 **「Provide values」** 按鈕，訊息列出它要什麼，例如
   `account_num — an existing account number to deposit into (e.g. 1011226111)`
3. 點按鈕 → 跳出 **Modal 表單** → 填值 → 送出
4. Bot 回「Got it — re-driving traffic with: …」，然後**自動 resume**（不用再按 Resume）
5. 那條 deposit 從 4xx 變 200，覆蓋率補上

**BoA 可以填的真值**（來自它自己的 loadgenerator，這正是被關掉的那份）：

| 要問的 | 可填 |
|---|---|
| `account_num` | `1011226111` |
| `routing_num` | `883745000` |
| `username` / `password` | `testuser` / `bankofanthos` |

**三個刻意的行為**（看到了就是對的）：

- 按鈕**不會**被 disable——填錯可以再點一次改
- 表單一次最多 5 格（Discord 上限），超過的下一輪再問
- 名字像密碼/token 的值，Bot 回話時會**遮罩**，而且值**從不進 prompt**（只有變數名進去）

**如果「Provide values」沒出現**：先看流量報告有沒有 `[WAIT]`。兩者都沒有，代表 LLM 這輪
自己猜對了或走了別條路徑——**把那次的流量報告留著**，那就是「Tier 3 沒被觸發」的結果，
也是對照實驗的一種答案（不是 bug）。

### 驗完把 Tier 1 開回來

```bash
# 機器 B
docker compose up -d --no-deps chatops4msa   # 不帶那個變數 = 回到預設 true
```

---

# 階段 5 ·（可選）petclinic + MySQL：B 的第二個證據

BoA 已經證明 DB 邊機制可行。petclinic 的價值在於它是**我們自己改的目標系統**，而且是
Java/JPA 這一側。

**唯一需要你確認的事**：petclinic 的 `mysql` profile 到底讀哪幾個環境變數。datasource 定義在
**沒改過的 config repo** 裡，我照 petclinic 慣例用了 `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASS`
（`15-mysql.yaml`、`20-services.yaml` 已設好）。

```bash
# 機器 A · 在 ChatOps4Msa-Pipeline repo 的 kube/petclinic 下
kubectl apply -f 00-namespace.yaml
kubectl apply -f 10-config-server.yaml
kubectl -n petclinic rollout status deploy/config-server --timeout=300s

# DB 必須比服務先 Ready（同樣是連線池的理由）
kubectl apply -f 15-mysql.yaml
kubectl -n petclinic rollout status deploy/mysql --timeout=300s

kubectl apply -f 20-services.yaml
kubectl -n petclinic rollout status deploy/api-gateway --timeout=300s

kubectl apply -f 30-gateway.yaml
kubectl apply -f 40-egress-serviceentry.yaml
kubectl -n petclinic rollout restart deploy/config-server
```

驗證真的連上 MySQL 而不是預設的 in-memory HSQLDB：

```bash
# 機器 A
kubectl -n petclinic exec deploy/mysql -c mysql -- \
  mysql -upetclinic -ppetclinic -e 'use petclinic; show tables;'
# 期望：看得到 owners / pets / vets / visits 這些表
```

**如果表是空的 / 服務起不來** → profile 沒吃到那組 env。備援（不依賴 config repo 內容）是改用
Spring 標準屬性，並且**要擋掉 Spring Cloud Config 的覆蓋——它預設會蓋掉環境變數**：

```bash
# 機器 A
kubectl -n petclinic set env deploy/customers-service deploy/vets-service deploy/visits-service \
  SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/petclinic?useSSL=false' \
  SPRING_DATASOURCE_USERNAME=petclinic \
  SPRING_DATASOURCE_PASSWORD=petclinic \
  SPRING_CLOUD_CONFIG_OVERRIDE_NONE=true
```

然後在 Discord 用 `namespace = petclinic`、`entry_url = http://192.168.100.106/` 跑一次。

---

# 階段 6 · 收尾

```bash
# 機器 A
kubectl delete namespace bank-of-anthos
kubectl delete -f boa-ingress.yaml --ignore-not-found
# petclinic 若不留：kubectl delete namespace petclinic
```

```bash
# 機器 B：確認 Tier 1 已開回預設
docker compose config | grep DEPENDENCY_EXAMPLE_REQUESTS_ENABLED
# 期望 "true"
```

---

# 出問題怎麼查

| 症狀 | 多半是 | 怎麼辦 |
|---|---|---|
| 服務 `1/2 CrashLoopBackOff` | GCP tracing 沒憑證 | 步驟 1-7 的 `set env ENABLE_TRACING=false` 沒做 |
| 服務卡 `ContainerCreating` | jwt-key secret 沒建 | 回步驟 1-4 |
| TCP 查詢 `result` 空的 | app 比 DB 早啟動，連線池沒被歸因 | 步驟 1-10 的 `rollout restart` |
| 圖上 db 邊還是虛線 | 同上，或報告是用舊 checkpoint 出的 | 先確認 1-10 的 curl 有東西，再重跑一次收集 |
| 圖沒有分層 | 跑的是舊 image | 步驟 0-3 的 `--build` 沒帶到 |
| 「Provide values」沒出現 | Tier 1 還開著 | 步驟 4 的 grep 沒看到 disabled 那行 |
| bot 連不到 `bankofanthos.local` | 容器不吃主機 `/etc/hosts` | 見階段 2 的註記（`extra_hosts` 或直接用 IP） |

---

# 老師會問，先備好

**「加了 DB 邊，覆蓋率怎麼沒變？」**
刻意的。DB 邊算成**獨立的 Data layer 百分比**，不混進業務邊那個比率——db 連線不是被使用者
旅程「踩到」的（它是服務處理請求時順帶發生的），混在一起會改變那個數字的意思，也會讓它
跟之前報告過的 4/4=100% 不可比。

**「TCP 那個數字是什麼？」**
是**連線數，不是查詢數**。連線池在服務啟動時建好，之後基本不動。所以它證明的是
「這個服務**真的**會連那個資料庫」，不是「用了多少」——而這正是相依圖要的答案。

**「為什麼 DB 要另外查一條？」**
Istio 只對它看得懂的協定（HTTP/gRPC）產 `istio_requests_total`。資料庫連線是不透明 TCP，
只在 `istio_tcp_*` 看得到。而原本那條 TCP 查詢**只用在 egress**（條件寫死
`destination_app="unknown"`，也就是 mesh 外），所以叢集內的資料庫一條查詢都沒有——
這就是為什麼以前 DB 邊永遠只能是虛線。

**「為什麼要關掉 Tier 1 才問得到人？不是造假嗎？」**
反過來：Tier 1 開著時工具**不問人**，才證明 Tier 3 是真的最後手段、沒有濫用。關掉是
**對照實驗的另一組**——同一個專案、同一段旅程，量「repo 自己的範例請求」到底值多少。
兩組的覆蓋率數字放在一起，就是這個四層策略的證據。
