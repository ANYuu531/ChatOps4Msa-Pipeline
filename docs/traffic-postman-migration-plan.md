# 計劃：流量腳本改用 Postman Collection v2.1 標準格式（執行器維持自寫）

> 這份文件是自足的執行計劃，供新對話直接依此實作。
> 相關背景記憶：`memory/` 下的專案記憶（流量覆蓋、predicate 重構等）。

## 0. 一句話

把「LLM 產出的流量腳本」從**自製 JSON schema** 改成 **Postman Collection v2.1**（業界標準、Postman/Newman 可直接消費），但**執行沿用行程內的自寫執行器**（JDK HttpClient），不用 Newman。格式標準化，執行器是有意識的取捨。

## 1. 背景與現況（要改的檔案）

老師的意見：現在等於**自己發明了一個格式**（腳本 DSL），不夠正規。他要的是「用標準格式」，不是「換執行器」或「換成瀏覽器」。（釐清過：不是 (甲) 瀏覽器、不是 (乙) Playwright；問題在**格式軸**，不在執行器軸。）

現況涉及的檔案：

| 檔案 | 角色 | 這次要不要動 |
|---|---|---|
| `Service/DependencyAnalysis/Traffic/TrafficScenario.java` | 自製格式的模型 + `parse()` | **改**：改成解析 Postman Collection v2.1 |
| `Service/DependencyAnalysis/Traffic/TrafficRunner.java` | 執行器（JDK HttpClient、cookie jar、`${var}` 替換、JSONPath capture、report） | **改**：變數語法 `${}`→`{{}}`、capture 來源改讀 pm.* 子集；HTTP/報告邏輯大致保留 |
| `Entity/ToolkitFunction/TrafficToolkit.java` | `toolkitTrafficRun` 入口 + last-scenario/last-report | **微調**：註解/錯誤訊息用語 |
| `resources/prompts/traffic_scenario_generation.txt` | LLM 產出格式的 prompt | **改**：輸出改成 Postman Collection v2.1（含約束的 `pm.*` capture 子集） |
| `resources/capability/devops-tool/dependency.yml` | 低碼流程 traffic 段 + resume 覆蓋率迴圈 | **幾乎不動**：`toolkit-traffic-run` 介面不變 |
| `DependencyAnalysisStateStore`（STAGE_TRAFFIC_SCENARIO/REPORT） | checkpoint | **不動**：仍存標準格式字串 |

核心：`toolkitTrafficRun(scenario_json, entry_url, repeats)` 的**簽名不變**，`scenario_json` 內容從自製 JSON 變成 Postman Collection JSON。上下游（低碼、checkpoint、精修迴圈）不需改。

## 2. 決策與理由（=論文/口試要寫的辯護）

### 2.1 為什麼採用 Postman Collection v2.1（格式）

- 它就是「**有序 HTTP 請求 + 變數 + capture 串接 + auth**」這件事的**業界標準 JSON**，正好對應現有腳本的語意。
- 可辨識、可重用、有生態；LLM 對它的訓練資料充足 → 生成可靠度高。
- 仍是**宣告式 JSON**（不是可執行程式碼），所以「LLM 可靠產出 / 可存 checkpoint / 可顯示 / 可跨 resume 精修」這四個現有優點**全部保留**。
- 直接**證偽**「你發明了自己的格式」：產物是純標準、可被標準工具消費。

### 2.2 為什麼執行器不用 Newman（維持自寫）—— 四個理由，依強度排序

1. **部署 footprint 與 pod 可攜性（最強、且與既有設計一致）**：Newman 是 Node.js 應用，要在 image 加 Node runtime 或開 sibling container（需 docker.sock → 「以 k8s pod 執行、無 Docker daemon」下失效）。這正是當初丟掉 k6 的同一理由（見 `TrafficRunner.java` 類別註解）。自寫執行器是純行程內 JDK HttpClient，compose/pod 行為一致、零額外 runtime。
2. **安全：collection 是 LLM 生成的，Newman 會執行內嵌任意 JS**。自寫執行器只解讀**宣告式子集**（請求 + 變數 + JSONPath capture），不執行任意程式碼 → 面對「作者是 LLM、目標是真叢集」風險面小得多。
3. **流程整合：這是「流量觀測」不是「測試斷言」**。4xx/5xx 不是失敗，它仍證明打到該 endpoint 的邊。自寫執行器把每個回應當**證據**，並輸出「step → status → 想觸發的依賴邊」的結構化報告，回餵覆蓋率精修迴圈、存 checkpoint。Newman 的測試導向模型（斷言/pass-fail/exit code）沒有「依賴邊歸屬」概念。
4. **只需子集且要完全控制語意**：retry、cookie、變數替換、`UNREACHABLE:` 缺口回報、report 截斷、timeout——都為分析調過，通用 runner 要靠設定硬逼近。

### 2.3 互通性「反殺」（關鍵防守）

只要產出的是**完全合法**的 Postman Collection（連 capture 都用標準 `pm.collectionVariables.set(...)` 寫在 `event.test` 裡），就能講：

> 「我的 collection 標準到 **Newman/Postman App 也能直接跑**——自寫執行器不是因為格式綁死，而是為了上述四個工程理由。互通性我保留著。」

這把「你自創格式」的指控徹底證偽：格式是標準的、可攜、可被標準工具消費；自製的只是 runtime，而 runtime 是實作自由。

### 2.4 誠實的限制（先講，別被問倒）

- 執行器**只實作 `pm.*` API 的宣告式子集**（見 §3）。若某份 collection 用了子集以外的任意 JS，需交給 Newman。誠實說法：「我保證產出合法 Postman collection；執行器實作我需要的子集，其餘標準工具可接手。」
- 因此 prompt 要**約束 LLM 只用該 capture 子集**（等於把現有宣告式 capture 換皮成 Postman 的 `pm.*` 寫法）。

## 3. 設計：支援的 Postman 子集 + 舊→新對照

### 3.1 欄位對照

| 現有自製格式 | Postman Collection v2.1 對應 |
|---|---|
| 根 `variables: {k:v}` | 根 `variable: [{key,value}]`（collection variables） |
| `steps: [...]` | `item: [...]` |
| step `name` | item `name` |
| step `method`/`path` | item `request.method` / `request.url`（見 §3.3） |
| step `headers: {k:v}` | item `request.header: [{key,value}]` |
| step `body`（JSON） | item `request.body: {mode:"raw", raw:"<json>", options:{raw:{language:"json"}}}` |
| step `capture: {var: "$.jsonpath"}` | item `event: [{listen:"test", script:{exec:["pm.collectionVariables.set(\"var\", pm.response.json()<access>)"]}}]`（見 §3.2） |
| step `exercises`（邊註記） | item `request.description`（自由字串，標準欄位）或 item `description` |
| `UNREACHABLE:` 特殊 step | item `name` 以 `UNREACHABLE:` 開頭、`description` 放原因；執行器辨識、不發送 |
| 變數引用 `${var}` | Postman `{{var}}`（**雙大括號**，執行器替換規則要改） |

### 3.2 capture 子集（要能被自寫執行器解析、又是合法 pm JS）

只支援這個形狀（prompt 需強制）：

```js
pm.collectionVariables.set("VAR", pm.response.json()<ACCESS>)
```

- `<ACCESS>` = 由 `.name`、`[int]`、`["name"]` 串成的存取鏈，例如：
  - `pm.response.json().token` → JSONPath `$.token`
  - `pm.response.json().data.id` → `$.data.id`
  - `pm.response.json()[0].id` → `$[0].id`
- 執行器實作：用 regex 抓出 `VAR` 與 `<ACCESS>`，把 `<ACCESS>` **轉成 JSONPath**，重用既有 `com.jayway.jsonpath`（已是相依）對 `pm.response.json()`（= 回應 body）求值。
- 也支援 `pm.environment.set(...)`（等價處理）。
- 其餘 `pm.test(...)`／斷言：**忽略**（不影響流量觀測），但不報錯。

### 3.3 URL 處理

- `request.url` 建議用物件：`{raw, host, path, query}`；但**接受字串 raw**（較簡單）。
- 相對路徑仍相對 `entry_url` 解析（沿用現有邏輯）。`{{var}}` 可出現在 url/header/body。

### 3.4 collection 骨架範例（sock-shop 登入→加車→結帳）

```json
{
  "info": {
    "name": "dependency-analysis traffic: <namespace>",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "variable": [
    {"key": "username", "value": "user"},
    {"key": "password", "value": "password"}
  ],
  "item": [
    {
      "name": "log in",
      "request": {
        "method": "POST",
        "header": [{"key": "Content-Type", "value": "application/json"}],
        "url": {"raw": "{{baseUrl}}/login", "host": ["{{baseUrl}}"], "path": ["login"]},
        "body": {"mode": "raw", "raw": "{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}", "options": {"raw": {"language": "json"}}},
        "description": "front-end -> user"
      },
      "event": [
        {"listen": "test", "script": {"type": "text/javascript",
          "exec": ["pm.collectionVariables.set(\"token\", pm.response.json().token)"]}}
      ]
    },
    {
      "name": "add item to cart",
      "request": {
        "method": "POST",
        "header": [{"key": "Authorization", "value": "Bearer {{token}}"}],
        "url": {"raw": "{{baseUrl}}/cart", "host": ["{{baseUrl}}"], "path": ["cart"]},
        "body": {"mode": "raw", "raw": "{\"id\":\"{{itemId}}\"}", "options": {"raw": {"language": "json"}}},
        "description": "front-end -> carts"
      }
    }
  ]
}
```
（`{{baseUrl}}` 可由執行器注入 entry_url，或維持相對路徑；二選一，實作時定案。）

## 4. 實作步驟（可逐項執行）

1. **`TrafficScenario.parse()` 改寫**：解析 Postman Collection v2.1 → 內部模型（沿用現有 `Step` 結構即可，只是來源不同）。
   - 讀 `info`（驗證 schema 大致正確，容錯）、`variable`→variables、`item[]`→steps。
   - 每個 item：name、request.method、request.url（物件或字串都吃）、header[]→map、body.raw→body、description→exercises。
   - `event` 裡 `listen:"test"` 的 `script.exec` join 起來，抓 `pm.(collectionVariables|environment).set("VAR", pm.response.json()<ACCESS>)`，轉成 `capture: {VAR: "$.<jsonpath>"}`。
   - `UNREACHABLE:` 開頭的 item → 標記為不發送、保留描述。
   - 容錯：非預期結構的 item 跳過並記一筆 warning，不整份炸掉（比照現況 `parse` 的寬容度）。
2. **`TrafficRunner` 調整**：
   - 變數替換 regex：`\\$\\{([^}]+)}` → `\\{\\{([^}]+)}}`（Postman `{{var}}`）。
   - capture 仍用 jayway JsonPath（來源改為 parse 階段轉好的 JSONPath）。
   - `UNREACHABLE` step：不發送、在報告標記為「未觸及，原因：…」。
   - HTTP 送出、cookie jar、report render、4xx/5xx 當證據——**維持不變**。
3. **prompt `traffic_scenario_generation.txt` 改寫**：
   - 輸出改為「**只回一個合法的 Postman Collection v2.1 JSON**」。
   - 明列 capture **只能**用 `pm.collectionVariables.set("VAR", pm.response.json()<access>)` 這個形狀。
   - 保留原本所有「打深層邊、payload 要對、UNREACHABLE 慣例、≤25 步、不要破壞性操作」等指引，只是換到 Postman 結構上表達（`exercises`→`description`）。
4. **`TrafficToolkit` / `dependency.yml`**：介面不變；只更新註解與錯誤訊息用語（「scenario」→「Postman collection」）。
5. **checkpoint**：`STAGE_TRAFFIC_SCENARIO` 仍存整份 collection JSON 字串；last-scenario/last-report 精修迴圈不動。

## 5. 測試與驗證

- **單元測試（純 Java，不需 Spring/native）**：
  - 給一份範例 Postman Collection（含 `pm.collectionVariables.set` capture），`TrafficScenario.parse()` 應正確得到 steps + capture（JSONPath 轉換正確：`.a.b`、`[0]`、`["k"]` 三種存取）。
  - 變數 `{{var}}` 替換正確；`UNREACHABLE:` item 不發送。
  - 容錯：壞 item 跳過、不整份失敗。
- **互通性交叉驗證（可選、需 Node）**：拿同一份生成的 collection 丟 `newman run collection.json`，證明「Newman 也能跑」——這是 §2.3 防守的實證，做一次截圖存證即可，不進 CI。
- **端到端**：對一個有 UI/API 的目標（bookinfo productpage 或 sock-shop）跑一輪，確認 Istio 觀測到的邊與改版前一致（行為不回歸）。

## 6. 風險 / 非目標

- **非目標**：不換成 Newman 執行器、不上瀏覽器 (甲)、不用 Playwright (乙)。這次**只換格式 + 執行器適配輸入**。
- **風險**：Postman capture 是 JS，執行器只支援子集——prompt 沒約束好時 LLM 可能寫出子集外的 JS。緩解：prompt 明確限制 + parse 階段對未知 capture 記 warning（而非靜默略過），讓使用者看得到。
- **相容性**：Node driver / 瀏覽器等重量級相依**都不引入**，維持 pod 可攜與 glibc base 現狀。

## 7. 給老師/口試的定稿說法

- 一句話：「流量腳本改用 **Postman Collection v2.1** 標準格式（Newman/Postman 都能跑），執行沿用行程內輕量執行器——理由是 **pod 可攜性、不執行 LLM 生成的任意程式碼、把回應當依賴邊證據而非測試斷言**。格式標準化了，執行器是有意識的取捨。」
- 被追問「那跟自製格式差在哪」→ 差在辨識度/互通性/可重用：產物現在是標準、可被標準工具消費，不再是自創 DSL。
- 被追問「為何不乾脆用 Newman/Playwright」→ 老師的問題在**格式軸**，該在格式軸解（換 Postman）；換執行器是答非所問，還多背 runtime、破壞 pod 可攜、且要執行 LLM 任意碼。
