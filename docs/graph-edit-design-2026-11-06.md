# 在報告 thread 裡用自然語言加線、加節點（2026-11-06）

回應 11/6 老師的第一點：「human-in-the-loop chat 不是可以改圖嗎？使用者能不能用自然語言要求新增線、節點？系統能不能自然反問線段類別、證據，甚至依使用者的要求，找到先前沒找到的證據？」

## 1. 先測現況：做不到

照程式碼走一遍（`ReportQaService.respond`）：

- thread 裡的訊息一律當成問題：語意路由 → 查詢 DSL → 確定性執行 → LLM 講結果。查詢目錄 16 個運算子**全是讀的**，沒有一個會改圖。
- 打「幫我加一條 kafka-connect → postgres 的線」：兩端都在圖上，路由最像的是 `path`（「X 會不會呼叫 Y？」）或 `subgraph`（「只畫 X 和 Y 那一塊的圖」）。結果是畫一張部分圖，或回「圖上沒有這條邊」，**不會加進圖裡**。
- 現有能「改」的只有兩種：thread 裡改部分圖的範圍（「加上 contacts」＝把節點加進**畫面**，圖本身沒變），以及 Resolve names 表單（答別名，重跑時套用）。

這一節是讀程式碼得到的結論，**沒有在 Discord 實際打過**（會呼叫 OpenAI，要在機器 B 跑，見第 5 節）。

## 2. 現在的做法

```
使用者：「圖上少了 kafka-connect 連到 postgres 的線，幫我加上」
   │  語意路由：比較像改圖的例句（EDIT_EXAMPLES）→ 不直接回答，交給 planner
   │  planner：add-edge(kafka-connect, postgres)（保留使用者的拼法）
   ▼
GraphEditDialogue（確定性狀態機；每個回答都由程式解析）
   ① 哪個節點：名字對不上 → 列最像的節點編號，或回「新增」
   ② 新節點是哪一種：服務／資料庫／訊息佇列／外部主機／閘道（依名字給建議）
   ③ 證據：先自己回 repo 找（EvidenceFinder）→ 列出 file:line，使用者選編號／貼自己的 file:line（會讀那一行來查證）／回「沒有」
   ④ 線段類別：sync-http／grpc／async／db／external／startup（依選到的證據給建議）
   ⑤ 確認整筆（含方向；隨時可回「反過來」「取消」）
   ▼
寫入：這份報告的圖＋這個 repo 的人工修改檔（dep-state/edits/<repo>.json）
     下次分析同一個 repo，合併完會自動帶入（GraphEdit.applyAll）
     thread 裡貼出新邊附近的部分圖
```

設計上的幾個決定：

- **偵測不動已校準的路由意圖表。** 路由的門檻是用 hold-out 問句掃出來的，加新意圖會改掉其他意圖的分數差距。所以改圖的例句另外算分，**只用來否決**：一句話離改圖例句比離任何問句意圖都近，就不在路由層直接回答，交給 planner 判斷。最壞情況是多呼叫一次 planner。
- **反問全部由程式決定**，LLM 只負責把一句話拆成 `add-edge(X, Y)`。名字對不對、證據是哪一行、類別是什麼，都是程式解析使用者的回答（同 Resolve names 表單的回答格式：編號、節點名稱、「新增」）。回答看不懂時，新增還開著；如果那句話其實是問題（問號結尾），就當問題回答，並提醒還有一筆新增在進行中。
- **信心照原本的分級，不新增等級。** 有 repo 出處（工具找到的行，或使用者給、工具讀過也確實寫到目標的行）＝`documented`，虛線；只有使用者的一句話或 repo 外的網址＝`inferred`，點線。provenance 另記 `human`，圖上的箭頭標「human」，報告與圖不會把人的話講成某一層找到的。之後跑執行期那一層若觀測到，照常升成實線。

## 3. 「依要求找到先前沒找到的證據」怎麼做

靜態層要四個問題都答得出來才畫線（名字、位址形狀、歸屬、使用），而且只讀它認得的格式。使用者給了一對服務以後，問題變窄了：不用知道格式，只要在**每個文字檔**裡找「目標的名字出現在位址形狀裡」，再問歸屬慣例「這個檔是誰的」。抽取規則一條都沒放寬。

`EvidenceFinder` 找三種痕跡，強的排前面：

| 痕跡 | 意思 |
|---|---|
| 殘餘列（RESIDUE） | 分析時抽到了這一列，但名字對不上圖上的節點 |
| 位址（ADDRESS）／位址鍵（ADDRESS_KEY） | URL、`host:port`、鍵名說「主機／位址」的值是目標 |
| 名字（NAME） | 設定檔裡單獨出現目標名；程式碼檔只認「字串整個就是那個名字」（`@FeignClient(name = "payment")`），`"Payment status"` 這種不算 |

每一筆都附「為什麼分析時沒畫」：這個檔工具歸給誰（用的是合併器自己的歸屬判斷 `CodeGraphMerger.fileOwners`，不是另寫一套），以及靜態層有沒有從這個檔抽出任何東西（沒有＝格式不在讀取範圍）。

不算命中的：註解行；`localhost`；**第一段剛好是服務名的外部網域**（`storefront.yas.local.com` 是 ingress 主機，照叢集 DNS 的寫法，服務名是單段或帶叢集字尾）；兩段的主機（`postgresql.postgres`，可能是 `服務.namespace`）只算弱命中。

repo 是在第一次找證據時重新 clone（淺 clone），對話結束或 30 分鐘後刪掉。clone 失敗時會講「讀不到 repo」，不會講成「找不到證據」。

## 4. yas 實測（離線，`GraphEditProbeTest`）

拿 11/5 簡報裡「刻意不做」的那 2 條漏邊當題目——老師說可以不修，那就看人在對話裡要求時，工具能不能自己找到出處。完整對話紀錄：[`experiments/2026-11-06-graph-edit/yas.md`](experiments/2026-11-06-graph-edit/yas.md)。

| 要求 | 工具回應 | 結果 |
|---|---|---|
| kafka-connect → postgres | 第 1、2 筆就是 `kafka/connects/debezium-order.json:6`、`debezium-product.json:6` 的 `"database.hostname": "postgres"`；說明「工具把這個檔歸給 `kafka`」 | 選 1 → 建議 db → 寫入，documented |
| storefront-nextjs → storefront | 列出 `storefront/.env.development:1`、`.env.production:1` 的 `API_BASE_PATH=http://storefront/api`；說明「工具把這個檔歸給 `storefront`」——**這就是分析時沒畫的原因**：`storefront/` 目錄被歸給目標自己，讀到了也只會是自己連自己 | 選 3 → sync-http → 寫入，documented |
| order → payment，使用者貼 `storefront-bff/.../application-dev.yaml:11` | 讀了那一行：「`uri: http://localhost:8081`，這行沒有寫到 `payment`。先記成你的說明，信心標最低」 | 取消（也剛好是第二點 yas 那條錯邊的出處） |
| cart → stripe-api | 不在圖上 → 「新增」→ 問種類 → 外部主機 → repo 找不到痕跡 → 「沒有」 | 寫入，inferred（點線）、類別建議 external |
| cart → product | 「圖上已經有了（sync-http，documented，出處 `cart/.../ProductService.java:26`）」 | 不重複加 |
| （沒講來源）→ tax | 反問「哪個服務呼叫或連到 `tax`？」 | — |
| 新增節點 kafka | 「已經在圖上了」 | — |

觀察到的界線：storefront 那題第 1 筆是 `automation-ui/storefront/...` 的 e2e 測試設定（`app.url=http://storefront/`），排在真正的出處前面；要靠使用者選。

## 5. 還沒驗證的、界線

- **偵測那一段沒跑過**：改圖例句的分數、planner 會不會正確輸出 `add-edge`，都要在機器 B 用 Discord 跑。路由的校準測試也要重跑一次，確認改圖例句沒有否決掉 hold-out 裡的正常問句（預期 0 句）。
- 找證據是文字層級的：不追資料流（URL 經過自訂函式參數才到 client 的找不到，同 11/5 界線第四項）、不讀映像裡的程式、不看叢集外的 SDK 端點。
- 沒有「撤銷」：寫錯的話，目前要刪 `dep-state/edits/<repo>.json` 裡那一筆。
- 操作者記的是 Discord user id。
- 刪線、改類別沒做（老師只提到新增）；資料結構已經是「一筆一筆的人工修改」，要加 `remove-edge` 是同一條路。

**不經 Discord 的實測（2026-11-10）**：`GraphEditLiveProbeTest` 把下面 6 句走 thread 真正的回答路徑（`ReportQaService.respond`：embedding → 語意路由 → planner → 對話狀態機 → 重新 clone 找證據），只是不經 Discord，每句照預期自動判 PASS／FAIL。機器 B 上一行跑完（含路由校準）：`bash docs/experiments/2026-11-06-graph-edit/run-on-machine-b.sh`。本機先用替身模型（`-Dprobe.live.stand-in=true`，planner 回它該回的，不呼叫 OpenAI）驗過流程，抓到一個 bug：在證據那一步，中途插問的「postgres 被誰用？」被收成使用者的說明；已修（問號結尾的句子不當說明，交給 thread 當問題回答），`GraphEditDialogueTest` 加一個測試。替身模型 6/6；真模型的結果等機器 B。

機器 B 上的測試句（報告 thread 裡直接打，不用 @bot）：

1. `圖上少了 kafka-connect 連到 postgres 的線，幫我加上` → 應列出 Debezium JSON
2. `storefront-nextjs 其實有呼叫 storefront，把這條線補上去`
3. `add an edge from cart to Stripe API` → 應問「你指的是…或新增」
4. `幫我加一條連到 tax 的線` → 應反問來源
5. `cart 會不會呼叫 product？` → 應照常回答（不能被當成改圖）
6. 進行中途問 `postgres 被誰用？` → 應回答問題、並提醒新增還開著

## 6. 程式

| 檔案 | 內容 |
|---|---|
| `Qa/GraphEditDialogue.java` | 對話狀態機（每步的問題與回答解析、中英文） |
| `Qa/EvidenceFinder.java` | 依要求找證據（三種痕跡、歸屬、「為什麼沒畫」） |
| `Graph/GraphEdit.java` | 一筆人工修改：套用、JSON、依序重放 |
| `Graph/CodeGraphMerger.fileOwners` | 合併器的歸屬判斷，給找證據用 |
| `Qa/SemanticRouter.java` | `EDIT_EXAMPLES` 與否決（不動 `INTENTS`） |
| `Qa/GraphQuery.java`、`graph_query_plan.txt` | `add-edge`、`add-node` 兩個運算子 |
| `Qa/ReportQaService.java` | 接進 thread；clone 快取；寫入與貼圖 |
| `DependencyAnalysisStateStore`、`DependencyReportService` | 每個 repo 的人工修改檔；下次分析重放 |
| `MermaidEmitter`、`DotEmitter` | 人工新增的箭頭標「human」 |

測試：`GraphEditDialogueTest` 15 個（假 repo）；`GraphEditProbeTest`（`-Dprobe.edit.repo=`，真 checkout）。全套 377 個只有既有的 `McpToolkitCallToolTest` 失敗；23 系統回歸 same 23／different 0。
