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

### 3. 渲染 —— 自包含互動式 HTML

- 用 **Cytoscape.js**(適合篩選/大圖;備選 vis-network / D3),**inline 進單一 HTML**,不吃外部 CDN。
- 視覺編碼:
  - 節點形狀/色:service(圓)、db(方/圓柱)、queue(菱形)、external(六角)。
  - 邊**線型依 provenance**:runtime-observed=實線粗、code-only=虛線、doc-only=點線。
  - 邊**顏色依 type**:sync(藍)、async(紫)、db(灰)、external(橘)。
  - 邊寬/透明度依 confidence 或 request count。
- **互動(這就是「邊太多」的解法)**:
  - 篩選開關:依 provenance(runtime/code/doc)、依 type、依信心度門檻、依 request-count 門檻。
  - 搜尋/highlight 某服務 + 其鄰居。
  - Layout:依 namespace 分群;force-directed 或階層(fcose / dagre)。
  - 點節點/邊 → 顯示 evidence(file:line、promql count)。
  - 圖例。

### 4. 送到 Discord

- **方案 A(建議):用 app 的 HTTP server serve。**
  加一個 `@RestController`(`GET /graph/{token}`)回傳該次分析的自包含 HTML;
  分析結束時把 graph JSON 存起來(token 為 key,可放 `dep-state` 目錄),
  在 Discord 貼網址 `http://192.168.100.150:8080/graph/<token>`。
  - ⚠️ 這個網址只在 LAN 可達。要給老師/遠端看,之後比照現有 soselab.tw tunnel,
    加一條 `anyu-chatops4msa-graph.soselab.tw`(見 [[split-deploy-machine-a]] 的 tunnel 收尾)。
- **方案 B(可當補充):貼靜態圖片**(PNG/SVG)當 Discord 附件,Discord 內就能看縮圖預覽。
  server 端 render 較費工(Java image 要裝 renderer,或 Graphviz DOT→SVG)。
- 建議:**A 為主(互動)**,B 之後可加當 inline 預覽縮圖。

### 5. 動到哪些地方

- 新 prompt(若走 LLM-emit-JSON):`prompts/dependency_graph_json.txt`(嚴格 JSON schema)。
- 新 service/toolkit:從 checkpoint 各 stage 建 graph JSON(照 `DependencyReportService` 的模式,
  它已經在讀所有 stage)。最自然:**在產生最終報告時,順手建 graph JSON + 貼連結**。
- 新 web 層:`@RestController` serve HTML + graph JSON;graph 存 `dep-state`(token 為 key)。
- 接入點:`get-dependency-analysis`(最後一步)、`resume`(重建)、「Generate report」按鈕
  (`DependencyReportService.generateAndPost`)。

---

## 分階段(MVP → 完整)

- **Phase 1(MVP,高價值小改動)**:runtime-only 圖。
  確定性 parse Istio Prometheus 結果 → graph JSON → 加 `@RestController` serve 互動 HTML →
  Discord 貼連結。這一版就能把「runtime 觀察到的邊」畫出來 —— 最想看、且最準。
- **Phase 2**:合併 code + doc 邊(LLM emit JSON)、provenance 線型 + 篩選開關。
- **Phase 3**:靜態圖片附件、tunnel 對外、點擊看 evidence、layout 打磨。

---

## 新對話開工前要決定的事

1. **互動 HTML(要 URL/tunnel)vs 靜態圖片(Discord 內直接看,較簡單)?** → 建議互動。
2. **graph JSON:確定性 parse vs LLM-emit?** → 建議 MVP runtime 用確定性,之後再加 LLM 補 code/doc。
3. **給老師看的 hosting:先 LAN 就好,還是一開始就上 tunnel?**
4. **圖表庫:Cytoscape.js(建議,篩選/大圖強)vs vis-network vs D3?**

## 起手第一步(給新對話)

1. 確認 8080 controller 可行:加一個最小 `@RestController` 回 "hello",compose 起來後
   `curl http://192.168.100.150:8080/hello` 通 → web 層 OK。
2. 做 Phase 1 MVP:Istio 查詢結果 → graph JSON → Cytoscape HTML → 貼連結。
3. 拿現在 sock-shop 那份跑過的分析當測資(runtime 邊已有:front-end→catalogue/carts/user)。
