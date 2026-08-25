# 老師反饋執行計劃（2026-08-25）

對應 8/14 會議後老師給的五點。每包都寫清楚：**老師的話 → 我的解讀 → 為什麼 → 怎麼做（改哪些檔） → 驗收標準 → 風險**。

## 執行狀態（2026-08-25 · 五包程式碼都已完成）

| 包 | 老師那點 | 狀態 | commit | 還缺什麼 |
|---|---|---|---|---|
| **A** | ⑤ 先補對話問人 | ✅ 完成 | `3e0cdb6` | **真叢集 demo**（BoA 關掉 Tier 1 範例 → 讓它問 → 填 → 覆蓋率補滿） |
| **B** | ① 實際部署資料庫 | ✅ 程式碼完成 | `852de69` | **機器上驗證**：mysql profile 吃不吃 `MYSQL_URL` 那組 env；部署順序（DB 先 Ready） |
| **C** | ④ 依賴圖分層 | ✅ 完成 | `d194cfa` | 真跑一次 petclinic / BoA 看分層圖（train-ticket 已用既有資料驗過） |
| **D** | ② 取名字 | ✅ 定案 DepWeaver | `ac7713b` | — |
| **E** | ③ 流程圖改 draw.io | ✅ 完成 | `f2ecf35` | **匯出 PNG/SVG**（這台機器沒裝 draw.io，要你在有的環境匯出） |

測試 71 → **102 通過**（唯一失敗的 `McpToolkitCallToolTest` 需要真的 k8s-mcp-server，與本次改動無關）。

實作過程中比計劃更明確 / 有出入的地方，各包內文已就地更新，主要三處：
1. **B 的根因比計劃寫的更具體**：`istio_tcp_*` 原本**只**用在 egress 查詢、條件寫死 `destination_app="unknown"`，所以叢集內的 DB 一條查詢都沒有 —— 光部署 MySQL 也畫不出來。新查詢用 `reporter="source"`，**DB 有沒有 sidecar 都量得到**。
2. **C 被真實資料逼出兩個修正**：沒有任何邊的節點不是入口（train-ticket 入口層 31 → 12），層號要壓成連續（否則渲染出空白帶）。
3. **A 的安全設計簡化了**：使用者填的值不進 prompt（只給變數名），所以不需要對答案跑 prompt injection 檢查；改為基本 sanitize + secret 遮罩。

## 執行順序與理由

老師明講「**先補對話問人這一層**」，所以 A 排第一。其餘按「相依關係」排，不是按老師講的順序：

```
A 對話問人 (Tier 3) ──┐
B 實際部署 DB ────────┤
C 依賴圖分層 ─────────┴──> E 流程圖改 draw.io（畫的是「改完之後」的流程）
D 取名字 ────────────────> E（圖上要寫工具名）
```

E 放最後是刻意的：流程圖要畫進「對話補值迴圈」跟「TCP/DB 觀測」，A、B 沒定案就畫，等於畫兩次。D 很便宜但要先拍板，因為圖上要有名字。

| 週 | 工作包 | 產出 |
|---|---|---|
| 第 1 週 | **A**（主）+ **D**（順手定案） | Discord 能問人拿值 → resume 補上邊；工具名 + 圖名定稿 |
| 第 2 週 | **B** + **C** | petclinic 真 MySQL、service→db 實線；三個專案的分層圖 |
| 第 3 週 | **E** + 回歸驗證 | `.drawio.svg` 流程圖；BoA / train-ticket 全流程重跑 |

---

## 工作包 A · 先補「對話問人」這一層（老師第 5 點）★最優先

**老師的話**：先補對話問人這一層。
**我的解讀**：不是先做「用自然語言查依賴圖」（那是 A2，之後做），而是 P12 講的**四層補值策略裡的 Tier 3**——Tier 1（挖 repo 範例）、Tier 2（失敗回饋）都做完了，剩下真正不可約的 domain 值（帳號、token、某筆存在的 ID），工具應該**回頭問使用者**，而不是直接標 UNREACHABLE 放棄。

**為什麼**：這是覆蓋率最後那幾 % 的唯一解（BoA 現在 6/7，缺的那條就是這類）。而且它是「對話結合」這條線最小、最能 demo 的一步——工具第一次會**主動開口問人**，而不是只有人問工具。

### 怎麼做

1. **產生端拆兩類 gap**（`prompts/traffic_scenario_generation.txt`）
   現在只有一種 `UNREACHABLE:`。拆成：
   - `ASK:` — 缺的是一個**具體值**，問人就能解（欄位名、範例格式一起講）。
   - `UNREACHABLE:` — 問人也沒用（要外部系統、要真實金流），維持誠實標記。
   新增輸出欄位 `ask: [{key, question, hint, example, edge, secret}]`，一輪最多 5 個（Discord Modal 上限）。

2. **資料模型**（`Traffic/TrafficScenario.java`）
   解析 `ask` → `List<AskItem>`；`secret=true` 的值不進 log、不回填 prompt。

3. **狀態**（`DependencyAnalysisStateStore` + `DepstateToolkit`）
   checkpoint 新增兩個 stage：`pending_asks`（工具想問什麼）、`user_values`（人答了什麼）。走 checkpoint 是必要的——對話是非同步的，人可能十分鐘後才回。

4. **Discord 互動**（`DiscordService/`）
   - checkpoint 訊息多一顆按鈕 `Provide missing values`（id 常數放 `DepstateToolkit`，跟現有三顆同一套）。
   - `ButtonListener` → `event.replyModal(...)`，依 `pending_asks` 動態組 Modal（每個 ask 一個 TextInput，label 用 `question`、placeholder 用 `example`）。
   - 新增 `ModalListener extends ListenerAdapter`（`onModalInteraction`）→ 寫入 `user_values` → 直接觸發 `resume-dependency-analysis`。

5. **注入**（`Traffic/TrafficRunner.java`）
   TrafficRunner 已有 `${var}` 變數替換與 capture 鏈，`user_values` 只要在跑之前 seed 進同一張變數表即可；resume 的 prompt 告訴 LLM「這些變數名已有值，直接引用、不要自己編」——**值本身不進 prompt**（避免 LLM 改寫、也避免敏感值外流）。

6. **安全**：答案先過 `prompts/prompt_injection_detection.txt`；長度上限；只允許出現在 body / header / query 的值位置。

### 驗收
- 單元測試：`ask` 解析、`user_values` 寫回 checkpoint、變數注入、secret 遮罩（現有 71 測試全過 + 新增約 4~6 條）。
- 端到端 demo（BoA）：把 Tier 1 的 loadgenerator 範例**故意關掉** → 工具在 Discord 問「deposit 需要 account_num（例：1011226111）」→ 填 Modal → 自動 resume → 該邊由 400 變 200、覆蓋率 6/7 → 7/7。**這就是下次會議的主 demo。**

### 風險
- Modal 上限 5 欄、觸發 Modal 必須是「按鈕互動的直接回應」（不能先 defer）——所以那顆按鈕要走專屬分支，不能吃現有 `deferEdit` 那條路。
- LLM 可能濫用 ASK（什麼都問人）→ prompt 要硬性規定：**Tier 1/2 能解的一律不准問**，並在報告裡列出「本輪問了什麼、為什麼非問不可」。

---

## 工作包 B · 測試時要實際部署資料庫（老師第 1 點）

**老師的話**：測試的時候要實際部署資料庫。
**我的解讀**：兩件事。① petclinic 現在跑 **in-memory HSQLDB、根本沒部署 DB**（`kube/petclinic/README.md:122`），所以 DB 邊永遠只有 code 靜態證據；② 因此**圖上 service→db 那條箭頭一直是虛線 / 甚至沒畫**——這正是我還欠的那項。

**為什麼**：現在「DB 相依」這一層只有靜態證據，等於三層信心裡最強的那層（runtime）對 DB 完全空白。要能宣稱「DB 相依也驗證過」，就得真的部署 DB、真的觀測到。

### 怎麼做

1. **部 MySQL**：新增 `kube/petclinic/15-mysql.yaml`（Deployment + PVC + Service + Secret）。三個資料服務（customers / vets / visits）改 `SPRING_PROFILES_ACTIVE=mysql` + `MYSQL_URL/USER/PASS` env override（config repo 不用動，沿用去 Eureka 那次的作法）。
   - **Service port 名稱必須是 `tcp-mysql`**，否則 Istio 會猜錯協定、TCP metric 出不來。
   - DB Pod 要有 sidecar（在 mesh 內）才觀測得到。

2. **補 mesh 內 TCP 查詢**（`capability/devops-tool/dependency.yml`）
   現在 `istio_tcp_connections_opened_total` **只用在 egress**（條件是 `destination_app="unknown"`，也就是 mesh 外）。mesh 內的 DB 完全沒查。新增 stage `tcp_raw`：
   ```promql
   sum by(source_workload,destination_workload)(
     istio_tcp_connections_opened_total{reporter="source",
       source_workload_namespace="${namespace}", destination_workload!="unknown"})
   sum by(source_workload,destination_workload)(
     istio_tcp_sent_bytes_total{reporter="source", source_workload_namespace="${namespace}"})
   ```
   `get-` 與 `resume-` 兩個 operation 都要加。

3. **併進圖**（`Graph/RuntimeGraphBuilder.java`）
   新增 `mergeIstioTcp`：service→db 邊 `runtimeObserved=true`、`type=db`、count=連線數，標籤寫 `db (tcp)`——誠實表示「TCP 層看到連線，不是 HTTP 層看到查詢」。

4. **覆蓋率語意**（`Graph/CoverageAnalyzer.java`）
   **不動**現有「可驅動業務邊」的定義（上次跟老師講的 4/4=100% 語意要維持），改為**另加一個獨立指標**：資料層邊觀測率（N 條 db 邊中觀測到幾條）。

5. **BoA 一併驗**：`accounts-db` / `ledger-db` 已是 PostgreSQL StatefulSet，只要確認有 sidecar（`kubectl get pod` 看 2/2），就能一次驗證 Java(JPA) 與 Python(SQLAlchemy) 兩邊的 db 邊都轉成實線。

6. 更新 `kube/petclinic/README.md`（刪掉 HSQLDB 那段）與 `docs/bank-of-anthos-runtime-runbook.md`。

### 驗收
petclinic 圖上 `customers-service → mysql`、`vets-service → mysql`、`visits-service → mysql` 三條**實線**、帶連線數；BoA 的兩條 db 邊同樣轉實線。before/after 兩張圖直接當投影片。

### 風險
- **連線池是長連線**：`connections_opened_total` 只在建池時 +1，跑流量不會再長。所以要**先部署 DB、再重啟 app、再導流量**，或改看 `sent_bytes_total`（有活動就會長）。這點會影響 demo 順序，先寫進 runbook。
- mTLS：DB 若在 STRICT 下、client 沒有 sidecar 會連不上 → 先確認 namespace 的 PeerAuthentication 是 PERMISSIVE。
- 資源：多一個 MySQL（約 512Mi），機器 A 上和 sock-shop 共存要先看用量。

---

## 工作包 C · 依賴圖分層（老師第 4 點）

**老師的話**：最後產生的依賴圖嘗試分層。
**我的解讀**：現在圖是 `rankdir=LR` 的自由佈局，train-ticket 52 條邊那張基本上是一團麻。要按**角色 / 呼叫深度**排成上下分層，一眼看得出「入口 → 業務 → 資料」。

**為什麼**：可讀性是老師連續兩次提到的（上次是「流程圖太長」）。而且分層結果**就是部署順序的雛形**——這條直通後面 DeployPlanner（依賴圖 → 拓樸排序 → 部署階段），順帶把「依賴分析為什麼有用」講死。

### 怎麼做

1. **新增 `Graph/GraphLayerAssigner.java`**（確定性，不用 LLM）
   - 硬分層：`gateway` → 最上層；`db` / `queue` → 最下層；`external` → 最下層另一欄。
   - 業務服務：從入口節點 BFS 取**最長路徑深度**分層。
   - **有環照樣要能跑**：Tarjan 求 SCC → 縮點 → 對 DAG 拓樸排序取深度 → 同一個 SCC 內的節點放同一層（微服務互相呼叫很常見，train-ticket 一定有環）。
   - **沒有 gateway 的 greenfield 圖**（BoA / train-ticket 靜態）：改用入度為 0 的節點當 root；全部都有入度時取 SCC 拓樸序的 source。
2. `DependencyGraph.Node` 加 `Integer layer`。
3. `DotEmitter`：`rankdir=TB` + 每層一組 `{rank=same; ...}`（不用 cluster，cluster 會把圖擠爆）。
4. `MermaidEmitter`：每層一個 `subgraph`。
5. 插在 `GraphNormalizer.normalize` 之後、emit 之前（正規化完才分層，否則幽靈節點會佔層）。

### 驗收
- 三個專案重出圖：petclinic（ingress → gateway → 業務 → mysql，4 層）、BoA（5 層）、train-ticket（52 邊那張要明顯變得能讀）。
- 單元測試：有環不爆、無 gateway 也能分層、孤立節點有歸屬。

---

## 工作包 D · 幫流程圖取名字（老師第 2 點）

**老師的話**：幫流程圖取名字。
**我的解讀**：兩層——① 這套**方法/工具**要有正式名字（論文、投影片、README 統一稱呼，不能一直講「我們的工具」）；② 每張**圖**要有固定圖名與編號（Fig.1/2/3），老師才好指「你第幾張圖」。

### 定案：**DepWeaver** — Multi-Evidence Dependency Weaver

副標固定成一句：**「多證據融合的微服務相依圖建構——確定性優先、LLM 只補殘餘」**。

*weave*（織）講的就是核心設計：runtime / code / doc 三種證據**織成同一張圖**，不是三選一。
（曾考慮過的其他候選：TriDep、MeshLens、MEDGE。）

名字定義在 `DependencyGraph.TOOL_NAME` 一處，工具的每個產出都帶著它：Graphviz PNG 的標題、
`.mmd` 檔頭、Discord 上的報告與覆蓋率標題——所以圖離開 Discord 之後還認得出來源。

### 圖名（先定，跟工具名無關）

| 編號 | 圖名 | 目前位置 |
|---|---|---|
| Fig.1 | 端到端流程總覽（Pipeline Overview） | `docs/dependency-analysis-flow.md` 圖一 |
| Fig.2 | 證據合併鏈（Evidence Merge Chain） | 同上 圖二 |
| Fig.3 | 分層相依圖（Layered Dependency Graph） | 工作包 C 產出 |
| Fig.4 | 對話補值迴圈（Interactive Gap-Filling Loop） | 工作包 A 產出 |

**做法**：名字定案後，一次掃過 `README.md`、`docs/*.md`、投影片講稿，統一稱呼。成本半天以內。

---

## 工作包 E · 流程圖改用 draw.io（老師第 3 點）

**老師的話**：流程圖改用 Draw.io。
**我的解讀**：**只有「說明用的流程圖」（Fig.1 / Fig.2）改 draw.io**——那是給人看的文件圖，本來就該手工排版。**工具自動產出的依賴圖維持 Graphviz / Mermaid**，那是程式即時生成的，不可能也不該改成 draw.io。這個界線我會在會議上先講明，免得誤會成「整套繪圖換掉」。

### 怎麼做
1. 新增 `docs/diagrams/`，檔名對齊圖號：`fig1-pipeline-overview.drawio.svg`、`fig2-evidence-merge-chain.drawio.svg`。
   - 用 **`.drawio.svg`** 格式：**單一檔案既是可編輯原始檔、也是能直接內嵌的圖**，git diff 也還算可讀。用 VS Code 的 Draw.io Integration 擴充直接編。
2. `docs/dependency-analysis-flow.md` 改成內嵌這兩張圖；原本的 Mermaid 版移到文末附錄保留（資訊不遺失，也留一份純文字可 grep 的版本）。
3. 排版規範（沿用 8/14 講稿老師已熟悉的標記）：
   - 節點標 **〔程式碼〕/〔AI〕**，兩色固定（程式碼=藍、AI=紫）。
   - 邊的線型與依賴圖一致：實線=runtime、虛線=code/doc、點線=僅宣告。
   - 圖左上角放**工具名 + 圖號 + 圖名**。
4. 同步匯出 PNG 供投影片用。

### 驗收
Fig.1 / Fig.2 兩張 `.drawio.svg` 進 repo、`flow.md` 引用得到、投影片換成新圖；圖上含工作包 A 的對話迴圈與工作包 B 的 TCP/DB 觀測。

---

## 下次會議 demo 清單（預期）

1. **對話問人**（A）：Discord 上工具主動問值 → 填 → 自動 resume → 覆蓋率補滿。**主秀**。
2. **真 DB**（B）：petclinic 部了 MySQL，service→db 從虛線變實線，before/after 對照。
3. **分層圖**（C）：train-ticket 52 邊圖 before/after。
4. **命名 + draw.io**（D、E）：新的 Fig.1 流程圖，順便回答「這套叫什麼」。

## 尚未處理、之後排

- **A2 自然語言查依賴圖**（NL → 圖查詢 → 確定性執行 → LLM 講結果）：老師說「先補對話問人」，所以這條排在 A 之後。工作包 A 做出來的 Discord 互動骨架（Modal / 狀態回寫）可以直接重用。
- **DeployPlanner**：工作包 C 的 layer 就是部署階段的輸入，C 做完這條的成本會小很多。
