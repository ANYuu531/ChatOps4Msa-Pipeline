# 依賴分析：使用者下指令後，背後發生什麼

這份文件畫出 `get dependency analysis` 從**使用者在 Discord 打一句話**，到**貼回一張依賴圖 + 覆蓋率 + 報告**的完整流程。內容對照實際程式碼與管線設定（`capability/devops-tool/dependency.yml`、`Service/DependencyAnalysis/*`、`Service/DiscordService/*`、`Service/NLPService/*`），不是示意。

四個關鍵設計：
- **三來源**：runtime（Istio 觀測）、code（tree-sitter 抽取）、doc（DeepWiki）——互補，不是三選一。
- **確定性優先**：圖與覆蓋率盡量用程式碼確定性算，LLM 只處理對不上的殘餘。
- **收集與出圖分離**：收集階段存進 checkpoint（可暫停/補流量/resume），使用者按「Generate report」才從 checkpoint 出圖，不重跑。
- **補值四層、最後才問人**：驅動流量需要語意正確的 payload。① 挖 repo 自己的範例請求 ② 把 4xx 回應餵回去讓 LLM 自己修 ③ **真的推不出來才反問使用者**（Tier 3）④ 連人都給不了才誠實標 UNREACHABLE。

---

## 圖一：端到端總流程

```mermaid
flowchart TD
    User(["使用者 Discord 訊息<br/>get dependency analysis repo/ns/entry"]) --> L["MessageListener /<br/>SlashCommandListener"]
    L --> NLP["NLP 解析<br/>LLMService + CapabilityGenerator<br/>意圖 → capability + 參數"]
    NLP --> ACC{"權限檢查<br/>AccessPermission / 角色"}
    ACC -->|通過| ORCH["CapabilityOrchestrator<br/>→ DependencyAnalysisRunner<br/>(背景執行緒, 避免 Discord 3s 逾時)"]
    ORCH --> START["toolkit-depstate-start<br/>開新 checkpoint"]

    START --> COLLECT

    subgraph COLLECT["收集管線 — get-dependency-analysis (dependency.yml)"]
      direction TB
      subgraph SRC["三來源抽取 (存進 checkpoint 各 stage)"]
        direction LR
        CODE["① CODE 抽取<br/>toolkit-code-extract<br/>StackDetector → tree-sitter<br/>Java/Python 深抽, 其他退 LLM<br/>→ EdgeLedger (stage: code)"]
        DOC["② DOC 抽取<br/>DeepWiki MCP, 問 5 題<br/>職責/同步/非同步/外部/入口<br/>→ LLM 合併 (stage: merged_notes)"]
        K8S["③ K8s/Istio 靜態<br/>kubectl services/deploy/pods/<br/>endpointslices/VS/DR<br/>→ (stage: k8s_raw, k8s)"]
      end
      SRC --> TRAF["驅動流量<br/>LLM 依 API 面+路由+doc 產 journey<br/>→ toolkit-traffic-run (TrafficRunner)"]
      TRAF --> PROM["Prometheus 查詢<br/>istio_requests_total (reporter=destination)<br/>→ (stage: traffic_raw)"]
      PROM --> PROMT["Prometheus 查詢 (in-mesh TCP)<br/>istio_tcp_connections_opened_total (reporter=source)<br/>= DB 邊唯一的 runtime 證據<br/>→ (stage: tcp_raw)"]
      PROMT --> COV1["確定性覆蓋率<br/>CoverageAnalyzer → 貼『round 1 覆蓋率』<br/>(業務邊 % + 另計的 Data layer %)"]
      COV1 --> EGR["Egress 查詢 (外部依賴)<br/>reporter=source, destination_app=unknown<br/>HTTP + TCP → (stage: egress_raw)"]
      EGR --> HEALTH["完整性檢查<br/>LLM dependency_health_check<br/>→ 缺哪些邊 (stage: health)"]
      HEALTH --> SE["toolkit-code-service-entries<br/>外部 host → ServiceEntry 建議"]
      SE --> ASK["toolkit-depstate-ask-button<br/>Tier 3: 有 ASK: 值才貼<br/>(沒有就完全不出現)"]
      ASK --> CKPT["toolkit-depstate-checkpoint<br/>暫停, 貼按鈕"]
    end

    CKPT --> BTN{"使用者按鈕<br/>(ButtonListener)"}
    BTN -->|"Apply ServiceEntries"| APPLY["k8s MCP: kubectl apply<br/>ServiceEntry → 之後 egress 邊可被觀測"]
    APPLY --> RESUME
    BTN -->|"Provide values (Tier 3)"| MODAL["Discord Modal 表單<br/>ModalListener → 存進 checkpoint<br/>(user_values, secret 不回顯)"]
    MODAL --> RESUME
    BTN -->|"Resume (補流量)"| RESUME["resume-dependency-analysis<br/>讀 checkpoint → 針對『缺的邊』產流量<br/>→ 重量測 → 更新覆蓋率 (可多輪)"]
    RESUME --> BTN
    BTN -->|"Generate report"| REPORT

    subgraph REPORT["出圖 + 報告 — DependencyReportService.generateAndPost"]
      direction TB
      BUILD["建圖合併鏈 (見圖二)<br/>runtime + code + doc + k8s → 正規化"]
      BUILD --> EMIT["MermaidEmitter → .mmd<br/>DotEmitter → Graphviz → PNG"]
      EMIT --> COVR["CoverageAnalyzer → 覆蓋率"]
      COVR --> TXT["LLM dependency_analysis<br/>→ 分層文字報告"]
    end

    REPORT --> OUT(["貼回 Discord<br/>PNG + .mmd + 覆蓋率 + 報告"])

    %% checkpoint 是持久化的, 重啟不丟
    CKPT -.持久化.-> STORE[("dep-state<br/>checkpoint (host volume)")]
    STORE -.讀回.-> RESUME
    STORE -.讀回.-> BUILD
```

---

## 圖二：建圖合併鏈（`postRuntimeGraph` 的核心）

出圖時**確定性**把三來源疊起來，再正規化；LLM 只收殘餘。邊的線型反映信心層級。

```mermaid
flowchart LR
    TR[("traffic_raw<br/>Istio JSON")] --> RB["RuntimeGraphBuilder<br/>fromIstioRequests<br/>= 骨架 (實線 runtime)"]
    ER[("egress_raw")] --> RB2["mergeIstioEgress<br/>已歸因外部 host 併入"]
    RB --> RB2
    TCP[("tcp_raw<br/>Istio TCP JSON")] --> RB3["mergeIstioTcp<br/>DB 邊 (非 HTTP, 只在 TCP 層)<br/>→ 虛線 db 邊升級成實線"]
    RB2 --> RB3
    CE[("code_edges")] --> CM["CodeGraphMerger.merge<br/>feign/http/url/kafka/db<br/>對齊節點, 對不上就建節點<br/>殘餘 → residue"]
    RB3 --> CM
    MN[("merged_notes<br/>DeepWiki JSON")] --> DM["DocGraphMerger.merge<br/>enrich 不 invent<br/>(對不上 workload 的服務丟棄)"]
    CM --> DM
    DM --> PR["promoteReallyUsedDbs<br/>persistence(jpa/SQLAlchemy/Django)<br/>→ DB 邊 inferred → documented"]
    PR --> KE["K8sGraphBuilder.enrich<br/>k8s_raw → deployed / not-deployed"]
    KE --> GN["GraphNormalizer.normalize<br/>清框架 lib 幽靈 / 別名 / 分組"]
    GN --> RES["resolveResidueWithLlm<br/>只有 residue 才問 LLM<br/>(嚴格驗證 source/target)"]
    RES --> G[("DependencyGraph<br/>canonical model")]

    G --> ME["MermaidEmitter → .mmd"]
    G --> DE["DotEmitter → Graphviz → PNG"]
    G --> CA["CoverageAnalyzer<br/>只算可驅動業務邊"]
```

**三層信心（`DependencyGraph.addEdge` 取 max，線型對應）**：

| 線型 | 意義 | 來源 |
|---|---|---|
| **實線** | runtime observed（最高信心） | Istio `istio_requests_total` 真的看到；**DB 這種非 HTTP 的相依則是 `istio_tcp_connections_opened_total`**（Istio 只對 HTTP/gRPC 產 requests 指標，資料庫連線是不透明 TCP，只在這條看得到） |
| **虛線 dashed** | code/doc 證實「真的用」（documented） | 有 repository/@Entity/持久化 code、或 doc configured |
| **點線 dotted `?`** | 只宣告、未證實（inferred，最弱） | 只有 datasource URL / pom 宣告，無使用證據 |

未部署節點（K8sGraphBuilder 判定）= 灰底紅框虛線標 `(not deployed)`。

---

## 各階段對應的程式碼

| 階段 | 觸發/實作 |
|---|---|
| 進入 & NLP | `DiscordService/{MessageListener,SlashCommandListener}` → `NLPService/{LLMService,CapabilityGenerator}` → `CapabilityOrchestrator` |
| 背景執行 | `DiscordService/DependencyAnalysisRunner`（+ `UserContextHolder`），解 Discord 3 秒 ack |
| 管線編排 | `capability/devops-tool/dependency.yml`（`get-` 與 `resume-` 兩個 operation） |
| CODE 抽取 | `CodeExtraction/{StackDetector,TreeSitterExtractor,*.scm,LlmCodeExtractor,EdgeLedger,ConfigExtractor,ExternalHostDetector}` |
| DOC 抽取 | DeepWiki MCP（`toolkit-mcp-*`）+ `prompts/deepwiki_*` |
| K8s/Istio | k8s MCP fork（`toolkit-mcp-call-tool execute_kubectl`）+ `prompts/k8s_runtime_notes.txt` |
| 流量 | `prompts/traffic_scenario_generation.txt` + `Traffic/{TrafficRunner,TrafficScenario}` |
| 對話問人（Tier 3） | `Traffic/AskItem` + `DepstateToolkit.toolkitDepstateAskButton` → `ButtonListener`（開 Modal）→ `DiscordService/ModalListener`（存值 + 自動 resume）；checkpoint 兩個 stage：`pending_asks` / `user_values` |
| runtime/egress | `toolkit-prometheus-query` + `prompts/{istio_runtime_edges,istio_egress_edges}.txt` |
| checkpoint | `DependencyAnalysisStateStore` + `DepstateToolkit`（`dep-state` host volume） |
| 按鈕 | `DiscordService/ButtonListener`（Apply / Resume / Generate report） |
| 建圖 & 報告 | `DependencyReportService.generateAndPost` → `Graph/*` + `prompts/dependency_analysis.txt` |

> 註：**Tier 3「對話問人」是唯一由工具主動開口的一步。** 產生器只有在確定值推不出來時，才在 collection 裡宣告一個空值 + `ASK:` 描述的變數（仍是合法 Postman collection，和 `UNREACHABLE:` 同一種手法）。需要那個值的請求**不會半填就送出**——runner 標成 `[WAIT]` 扣住，等使用者從 Modal 填完，自動 resume 重送。已回答的值存在 checkpoint，整個 run 只問一次。
>
> 註：`resume-dependency-analysis` 是**覆蓋率導向迴圈**——用確定性覆蓋率算出「還沒被觀測到的 service→service 邊」當權威目標，產針對性流量、重量測、更新覆蓋率，可多輪，直到使用者按 Generate report。昂貴階段（repo clone、DeepWiki 問答）從 checkpoint 取回、不重跑。
