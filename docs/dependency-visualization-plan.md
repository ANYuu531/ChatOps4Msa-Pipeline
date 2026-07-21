# 依賴分析視覺化 —— 規劃

## 為什麼做

老師回饋:目前 note / 報告的**資訊量太大**,依賴分析尤其「邊太多」,純文字難讀。
最終報告(`prompts/dependency_analysis.txt`)刻意產出一大篇 Markdown(還規定「不准用表格」),
在 Discord 就是一長串文字 —— 這正是資訊過載的來源。

**目標:把依賴圖畫成一張可互動、可篩選的圖**(節點=服務/DB/佇列/外部,邊=依賴),
文字報告保留(不取代),視覺化是「另一種讀法」。

「邊太多」的真正解法不是把圖畫小,而是**讓使用者能篩選**(依來源證據、依類型、依信心度)
—— 一次只看想看的那層。

---

## 現況(已盤點)

| 面向 | 現況 |
|---|---|
| 邊的資料 | 分散在多個 stage:code 抽取是**結構化的** `EdgeLedger`;runtime 邊來自 Istio Prometheus 查詢(`source_workload→destination_workload`,結構化)但被 LLM 轉成**文字** ledger;doc 邊是 LLM 純文字 |
| 最終報告 | `DependencyReportService.generateAndPost()` 讀 checkpoint 各 stage → LLM(`dependency_analysis` prompt)→ **純文字 Markdown** 貼到 Discord |
| Web 層 | **`spring-boot-starter-web` 已在依賴**(pom.xml:37),8080 有 embedded Tomcat 在跑,但**目前 0 個 controller** → 可直接加 endpoint serve 網頁 |
| 資料模型 | `EdgeLedger.Edge`:section(feign/http-client/rabbit-*/…)+ fields(key=value)+ file:line + confidence |
| checkpoint stages | MERGED_NOTES(deepwiki+code)、K8S、TRAFFIC(runtime 邊)、EGRESS(外部) |

關鍵:**圖需要的資料都在,但大多被 render 成文字了。** 要先有一個「結構化圖模型」。

---

## 設計

### 1. 標準圖模型(Canonical Graph JSON) —— 單一資料來源

所有 render 都吃這個 JSON:

```json
{
  "namespace": "sock-shop",
  "nodes": [
    { "id": "front-end", "kind": "service", "namespace": "sock-shop" },
    { "id": "catalogue-db", "kind": "db" },
    { "id": "rabbitmq", "kind": "queue" },
    { "id": "api.github.com", "kind": "external" }
  ],
  "edges": [
    {
      "source": "front-end", "target": "catalogue",
      "type": "sync-http",                      // sync-http | async | db | external | grpc
      "provenance": ["runtime", "code"],        // runtime | code | doc(可多個)
      "confidence": "observed",                 // observed | documented | inferred
      "runtimeObserved": true,
      "count": 123,                             // runtime 請求數(有才填)
      "evidence": ["istio_requests_total front-end->catalogue = 123",
                   "FeignClient CatalogueClient at Foo.java:42"]
    }
  ]
}
```

節點 `kind`、邊 `provenance`/`type`/`confidence` 是視覺編碼的依據(見下)。

### 2. 怎麼建這個 JSON

三層證據,建議「確定性優先 + LLM 補名字/doc 邊」:

- **runtime 邊(確定性,最可靠)**:直接 parse Istio Prometheus 的原始結果
  (`source_workload → destination_workload` + count)。這層不經 LLM。
- **code 邊(確定性)**:從 `EdgeLedger` 取;source = 該 repo 的服務,target = 各 edge 的目標字串。
  需要一步**名稱正規化**(程式碼裡的名字 → k8s service 名)。
- **doc 邊(LLM)**:從 deepwiki notes 補,信心較低,可選。
- **合併**:以 (source,target) union,合併 provenance 旗標。同時被 runtime + code 命中 = 高信心。

> **MVP 取捨**:第一版可先只做 **runtime 邊**(純確定性、最準、最想看),用一個新 prompt
> `dependency_graph_json` 讓 LLM 把「所有 ledger」吐成上面的 JSON schema 也行(能順便處理名稱對齊),
> 但要對 JSON 做嚴格 schema 驗證(參考 workflow 的 structured-output 做法)。
> 建議:**MVP 用 runtime-only 確定性**,Phase 2 再加 code/doc 合併。

### 3. 渲染 —— 一個模型,多個輸出器(emitter)

**關鍵設計**:標準圖 JSON 是唯一資料來源,render 只是把它「序列化」成不同格式。
每個 emitter 都是純函式 `graphJson -> 某格式`,彼此獨立、可逐步加。老師的期待
(先靜態/Mermaid,後互動前端)剛好對應難度由低到高的三個 emitter:

**Emitter 1 — Mermaid 語法(最低成本,老師的第一期待)**
- 從 graph model 產 Mermaid `flowchart LR` 文字:`front-end -->|sync| catalogue` 之類。
- 純字串轉換,**零基礎設施、零新依賴**。
- Discord 送法:貼成 code block(```mermaid ... ```)。Discord 本身不渲染 Mermaid,
  但老師可貼到 mermaid.live / GitHub / HackMD / 論文文件即渲染。
- 視覺編碼(Mermaid 能做的範圍):
  - `subgraph` 依 namespace 分群;節點文字標 kind。
  - 邊 label 標 type(sync/async/db);線型 `-->` vs `-.->` 區分 provenance
    (實線=runtime-observed、虛線=code/doc-only)。
  - classDef 上色(runtime 邊 highlight)。

**Emitter 2 — 靜態圖片(PNG/SVG,Discord 內直接看)**
- 兩條路:
  - **(a) Graphviz DOT → PNG**:graph model 產 DOT,`dot -Tpng` 渲染。
    需在 chatops4msa 的 Docker image `apt install graphviz`(小)。**自包含、不外洩**。
    Graphviz 的 layout 對這種圖品質好。
  - **(b) Mermaid → 圖片**:把 Emitter 1 的 Mermaid 丟給渲染器出圖。
    - 自包含:mermaid-cli(mmdc)但要 Node + headless chromium(重),不建議塞進 Java image。
    - 外部服務:mermaid.ink / kroki.io(base64 塞進 URL,GET 回 PNG),Java 一個 HTTP call 就好。
      ⚠️ **但這會把服務名/架構送到第三方**,實驗室/論文可接受再用;或自架 kroki container。
- 建議靜態圖片走 **(a) Graphviz 自包含**,不外洩。
- Discord 送法:當**附件**貼上,頻道內直接看到圖(比 Mermaid 文字更直觀)。

**Emitter 3 — 互動前端(後期,「邊太多」的完整解)**
- 用 **Cytoscape.js** inline 進單一自包含 HTML(不吃外部 CDN)。
- 視覺編碼:節點形狀/色依 kind;邊線型依 provenance、顏色依 type、寬/透明度依 confidence/count。
- **互動 = 篩選**:依 provenance / type / 信心度 / request-count 門檻開關;搜尋 highlight 某服務+鄰居;
  依 namespace 分群 layout(fcose/dagre);點節點/邊看 evidence(file:line、promql count);圖例。
- Discord 送法:見 §4 方案 A。

### 4. 送到 Discord(依 emitter 不同)

- **Mermaid(Emitter 1)**:直接貼 ```mermaid code block``` —— 零基礎設施。
- **靜態圖片(Emitter 2)**:render 成 PNG 當**附件**貼上,頻道內直接看。
- **互動 HTML(Emitter 3)**:用 app 的 HTTP server serve。
  加 `@RestController`(`GET /graph/{token}`)回傳自包含 HTML;graph JSON 存 `dep-state`(token 為 key);
  Discord 貼網址 `http://192.168.100.150:8080/graph/<token>`。
  - ⚠️ 此網址只在 LAN 可達。給老師/遠端看要比照現有 soselab.tw tunnel,加一條
    `anyu-chatops4msa-graph.soselab.tw`(見 [[split-deploy-machine-a]] 的 tunnel 收尾)。

### 5. 動到哪些地方

- 新 prompt(若走 LLM-emit-JSON):`prompts/dependency_graph_json.txt`(嚴格 JSON schema)。
- 新 service/toolkit:從 checkpoint 各 stage 建 graph JSON(照 `DependencyReportService` 的模式,
  它已經在讀所有 stage)。最自然:**在產生最終報告時,順手建 graph JSON + 貼連結**。
- 新 web 層:`@RestController` serve HTML + graph JSON;graph 存 `dep-state`(token 為 key)。
- 接入點:`get-dependency-analysis`(最後一步)、`resume`(重建)、「Generate report」按鈕
  (`DependencyReportService.generateAndPost`)。

---

## 分階段(依老師期待:先靜態/Mermaid,後互動前端)

**每一階段都吃同一個標準圖 JSON,只是換一個 emitter。所以模型只做一次,後面純加輸出格式。**

- **Phase 1(MVP,老師的第一期待):Mermaid 語法。**
  先做 **runtime-only** 的 graph model(確定性 parse Istio Prometheus 結果)→ Emitter 1 產 Mermaid →
  Discord 貼 code block。零基礎設施、最快看到成果。老師可貼進 mermaid.live/文件即渲染。
- **Phase 2:靜態圖片。** 加 Emitter 2(Graphviz DOT → PNG,image 裝 graphviz)→ Discord 貼附件,
  頻道內直接看圖。同時把 code + doc 邊合併進 model(LLM emit JSON),邊帶 provenance 線型。
- **Phase 3:互動前端。** 加 Emitter 3(Cytoscape.js @RestController serve HTML)+ 篩選/搜尋/點擊看
  evidence + tunnel 對外。這才是「邊太多」的完整解。

> 為什麼這個順序好:Phase 1 幾天內就有東西給老師看且風險最低;model 一旦建好,Mermaid→圖片→互動
> 只是換 emitter,不用重做資料層。互動前端(原本我排 Phase 1)成本最高、擺最後最合理。

---

## 新對話開工前要決定的事

1. **Phase 1 的 model 範圍:runtime-only 先做,還是一次就 runtime+code+doc 合併?** → 建議 runtime-only 起步。
2. **graph JSON:確定性 parse vs LLM-emit?** → 建議 MVP runtime 用確定性,Phase 2 再加 LLM 補 code/doc。
3. **Phase 2 靜態圖片:Graphviz 自包含(建議,不外洩)vs Mermaid 外部渲染(mermaid.ink,會外洩架構)?**
4. **Phase 3 圖表庫:Cytoscape.js(建議,篩選/大圖強)vs vis-network vs D3?** + hosting(LAN vs tunnel)。

## 起手第一步(給新對話)

1. 定義標準圖 JSON 的 Java model(node/edge POJO 或直接 JSON)。
2. 做 **runtime-only** 建 model:確定性 parse Istio Prometheus 查詢結果
   (`source_workload→destination_workload` + count)。
3. 寫 **Emitter 1(Mermaid)**:model → `flowchart LR` 字串,接進「Generate report」流程,
   Discord 多貼一則 ```mermaid``` code block。
4. 拿現在 sock-shop 那份跑過的分析當測資(runtime 邊已有:front-end→catalogue/carts/user) —— 
   先確認產出的 Mermaid 貼到 mermaid.live 畫得出來。
5. (Phase 2 才)image 裝 graphviz + Emitter 2;(Phase 3 才)加 `@RestController` + Cytoscape。
