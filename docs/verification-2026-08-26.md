# 真環境驗證清單（A 對話問人 + B 真 DB + C 分層）

一次跑完，用 **Bank of Anthos** 同時驗三包。BoA 是最好的選擇：它本來就有兩個真的資料庫
（`accounts-db` / `ledger-db`，PostgreSQL StatefulSet），所以**不用先部署 MySQL 就能驗 B**；
而 A 的場景（deposit 需要 `account_num`）本來就在 BoA。

petclinic + MySQL（`kube/petclinic/15-mysql.yaml`）是 B 的第二個證據，**可選**，放在最後。

---

## 0. 前置：要不要重跑 Discord 註冊指令？

**不用。** slash command 的註冊（`CQLRegister` 裡那三行，預設是註解掉的）只跟
**capability 的 operation 名稱和 parameter** 有關。這次改的都是 operation 的 *body*
（多幾個步驟），`get-dependency-analysis` / `resume-dependency-analysis` 的參數一個都沒動。

而這次新增的互動元件——**Button 和 Modal——在 Discord 都不需要事先註冊**，是訊息送出時
即時帶上的元件。

**要做的只有重新 build + 重啟**（新的 `ModalListener` 才會掛上 JDA，新的 toolkit 才會載入）：

```bash
# 機器 B（跑 ChatOps4Msa 的那台）
git pull
docker compose up -d --build chatops4msa

# 確認新的 listener 掛上了、capability 驗證有過（啟動時會驗 toolkit_verify.yml）
docker compose logs -f chatops4msa | head -60
# 期望看到 "JDA START!"，且沒有 toolkit / capability 的驗證錯誤
```

---

## 1. 部署 BoA（照既有 runbook）

`docs/bank-of-anthos-runtime-runbook.md` 步驟 0~6 照跑。這次多做**步驟 5b**（新加的）：
確認 DB 邊量得到。

```bash
# DB 邊的證據（工具現在會自動查同一條）
curl -s 'http://192.168.100.106:30090/api/v1/query?query=sum%20by(source_workload,destination_workload,destination_service_name)(istio_tcp_connections_opened_total{reporter="source",source_workload_namespace="bank-of-anthos"})' | head -c 600
```

**期望**：看到 `userservice`/`contacts` → `accounts-db`，
`ledgerwriter`/`balancereader`/`transactionhistory` → `ledger-db`。

**如果是空的**（服務都健康卻沒有 TCP 指標）→ 幾乎一定是**順序**問題：連線池只在服務啟動時
建一次，如果 app 比 DB 先起來，那條連線可能沒被 sidecar 歸因。重啟服務再查：

```bash
kubectl -n bank-of-anthos rollout restart deploy \
  userservice contacts balancereader transactionhistory ledgerwriter
# 等 2/2 Ready 後再查一次上面那條
```

---

## 2. 第一輪：正常跑（Tier 1 開著）→ 驗 B 和 C

Discord 下指令：

```
repo_name = GoogleCloudPlatform/bank-of-anthos
namespace = bank-of-anthos
entry_url = http://bankofanthos.local:31403/
auth_hint = none
```

跑完按 **Generate report**。檢查三件事：

| 驗什麼 | 期望看到 |
|---|---|
| **B · DB 邊** | 覆蓋率訊息多一段 **Data layer**（獨立百分比，不混進業務邊那個數字）；圖上 `userservice → accounts-db` 等是**實線**、標籤 `db` |
| **C · 分層** | 圖從左右散開變成**上下分層**：ingress → frontend → 後端服務 → data stores。PNG 的標題列現在寫 `DepWeaver — dependency graph · namespace: bank-of-anthos` |
| **A · 不濫發** | 大概率**不會**出現「Provide values」按鈕 —— Tier 1 從 loadgenerator 抓到了 deposit 的欄位，就不該問人。**這是正確行為**，不是失敗 |

> 上一輪 BoA 的覆蓋率是 6/7，缺 `ledgerwriter → balancereader`（後端內部邊，難從外部觸發）。
> 這輪數字應該差不多——**DB 邊不會讓業務覆蓋率變動**，那是刻意的（見下方「會被問到」）。

---

## 3. 第二輪：關掉 Tier 1 → 驗 A（工具開口問人）

這是**對照實驗**，不是為了 demo 硬湊：同一個專案、同一段旅程，有沒有 repo 自己的範例請求，
差別在哪。關掉之後 deposit 的欄位就沒人給了，才會走到 Tier 3。

```bash
# 機器 B：加一個環境變數重啟即可（Spring relaxed binding，不用改檔案）
docker compose stop chatops4msa
DEPENDENCY_EXAMPLE_REQUESTS_ENABLED=false docker compose up -d chatops4msa

# 確認生效
docker compose logs chatops4msa | grep "example-request harvesting is disabled"
```

（要寫進 compose 也行：`chatops4msa.environment` 加一行
`- DEPENDENCY_EXAMPLE_REQUESTS_ENABLED=false`。驗完記得移除。）

然後**用同樣的參數再跑一次**（checkpoint 是每個使用者一份，新的 run 會覆蓋舊的）。

期望的流程：

1. 流量報告裡出現 **`[WAIT]`** 的步驟 —— 那個請求被扣住了，**沒有半填就送出**。
2. 收集結束時多一顆 **「Provide values」** 按鈕，訊息會列出它要什麼，例如
   `account_num — an existing account number to deposit into (e.g. 1011226111)`。
3. 點按鈕 → 跳出 **Modal 表單** → 填值 → 送出。
4. Bot 回「Got it — re-driving traffic with: …」，然後**自動 resume**（不用再按 Resume）。
5. 那條 deposit 從 400 變 200，覆蓋率補上。

**要留意的三個點**（這些是實作上刻意的行為，看到了就是對的）：

- 按鈕**不會**被 disable —— 值填錯可以再點一次改。
- 表單一次最多 5 格（Discord 上限）；超過的會在下一輪再問。
- 名字看起來像密碼/token 的值，Bot 回話時會**遮罩**，而且值**從不進 prompt**（只有變數名進去）。

**如果 Provide values 沒出現**：先看流量報告有沒有 `[WAIT]`。都沒有的話，代表 LLM 這輪
自己猜對了或改用了別的路徑——把那次的流量報告留著，那就是「Tier 3 沒被觸發」的證據，
也是對照實驗的一種結果。

---

## 4.（可選）petclinic + MySQL：B 的第二個證據

BoA 已經證明 DB 邊機制可行。petclinic 這條的價值在於**它是我們自己改的目標系統**，而且
是 Java/JPA 這一側。

需要你確認的一件事：**petclinic 的 `mysql` profile 到底讀哪幾個環境變數。**
datasource 定義在**沒改過的 config repo** 裡，我照 petclinic 慣例用了
`MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASS`（`15-mysql.yaml` 和 `20-services.yaml` 已設好）。

```bash
# 部署（順序是關鍵：DB 必須比服務先 Ready）
kubectl apply -f kube/petclinic/00-namespace.yaml
kubectl apply -f kube/petclinic/10-config-server.yaml
kubectl -n petclinic rollout status deploy/config-server --timeout=300s
kubectl apply -f kube/petclinic/15-mysql.yaml
kubectl -n petclinic rollout status deploy/mysql --timeout=300s
kubectl apply -f kube/petclinic/20-services.yaml
kubectl -n petclinic rollout status deploy/api-gateway --timeout=300s

# 驗證真的連上 MySQL 而不是預設的 in-memory HSQLDB
kubectl -n petclinic exec deploy/mysql -c mysql -- \
  mysql -upetclinic -ppetclinic -e 'use petclinic; show tables;'
# 期望：看得到 owners / pets / vets / visits 這些表
```

**如果表是空的 / 服務起不來**：多半是 profile 沒吃到那組 env。備援做法（不依賴 config repo
內容）是改用 Spring 標準屬性，並且要擋掉 Spring Cloud Config 的覆蓋——**它預設會蓋掉環境變數**：

```yaml
- name: SPRING_DATASOURCE_URL
  value: jdbc:mysql://mysql:3306/petclinic?useSSL=false
- name: SPRING_DATASOURCE_USERNAME
  value: petclinic
- name: SPRING_DATASOURCE_PASSWORD
  value: petclinic
- name: SPRING_CLOUD_CONFIG_OVERRIDE_NONE
  value: "true"
```

---

## 會被問到，先備好

**「加了 DB 邊，覆蓋率怎麼沒變？」**
刻意的。DB 邊算成**獨立的 Data layer 百分比**，不混進業務邊那個比率——db 連線不是被使用者
旅程「踩到」的（它是服務處理請求時順帶發生的），混在一起會改變那個數字的意思，也會讓它
跟之前跟老師報告過的 4/4=100% 不可比。

**「TCP 的那個數字是什麼？」**
是**連線數，不是查詢數**。連線池在服務啟動時建好，之後基本不動。所以它證明的是
「這個服務**真的**會連那個資料庫」，不是「用了多少」——而這正是相依圖要的答案。

**「為什麼 DB 要另外查一條？」**
Istio 只對它看得懂的協定（HTTP/gRPC）產 `istio_requests_total`。資料庫連線是不透明 TCP，
只在 `istio_tcp_*` 看得到。而原本那條 TCP 查詢**只用在 egress**（條件寫死
`destination_app="unknown"`，也就是 mesh 外），所以叢集內的資料庫一條查詢都沒有——
這就是為什麼以前 DB 邊永遠只能是虛線。
