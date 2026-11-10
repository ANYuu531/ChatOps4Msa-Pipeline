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

使用者：「把 X 到 Y 的線拿掉」（2026-11-10 加）
   │  planner：remove-edge(X, Y)
   ▼
   兩端都要在圖上（名字對不上 → 列最像的節點；沒有像的 → 結束）
   圖上沒這條線但有反方向 → 問要不要「反過來」
   確認：列出這條線在圖上的紀錄（類別、信心、來源層、出處；執行期有觀測到會特別標出）
   ▼
寫入：圖上拿掉；記成 remove-edge，下次分析若再抽到也會再拿掉

使用者：「撤銷」／「undo」（不經 LLM，程式直接認）
   ▼
   這個 thread 最後一筆修改：圖回到那筆之前的樣子（每筆修改都存了修改前的圖），
   repo 的人工修改檔也拿掉那一筆；可以連續撤銷
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

觀察到的界線：storefront 那題第 1 筆是 `automation-ui/storefront/...` 的 e2e 測試設定（`app.url=http://storefront/`），排在真正的出處前面；要靠使用者選。**11/10 已改**：找證據時沿用靜態層對測試目錄的定義（`SourceScanner.isTestPath`，含 `e2e/`、`automation-ui/`）降分，這類檔案排到服務自己的檔案後面（分數不夠的就不列）。

## 5. 還沒驗證的、界線

- **偵測那一段已在機器 B 用真模型跑過（2026-11-10，見本節最後）**，但還沒經過 Discord 本身的收發，也還沒測 `ask()` 的 prompt injection 檢查。
- 找證據是文字層級的：不追資料流（URL 經過自訂函式參數才到 client 的找不到，同 11/5 界線第四項）、不讀映像裡的程式、不看叢集外的 SDK 端點。
- 「撤銷」只能撤銷**這個 thread** 裡做的修改（修改前的圖存在這份報告的紀錄裡）；別的 thread 加的，要去那個 thread 撤，或刪 `dep-state/edits/<repo>.json` 裡那一筆。
- 操作者記的是 Discord user id。
- 刪線是人的判斷蓋過工具：之後執行期那一層就算觀測到這個呼叫，重放時還是會拿掉（確認時有警告）。改類別沒做。

**不經 Discord 的實測（2026-11-10）**：`GraphEditLiveProbeTest` 把下面 6 句走 thread 真正的回答路徑（`ReportQaService.respond`：embedding → 語意路由 → planner → 對話狀態機 → 重新 clone 找證據），只是不經 Discord，每句照預期自動判 PASS／FAIL。機器 B 上一行跑完（含路由校準）：`bash docs/experiments/2026-11-06-graph-edit/run-on-machine-b.sh`。本機先用替身模型（`-Dprobe.live.stand-in=true`，planner 回它該回的，不呼叫 OpenAI）驗過流程，抓到一個 bug：在證據那一步，中途插問的「postgres 被誰用？」被收成使用者的說明；已修（問號結尾的句子不當說明，交給 thread 當問題回答），`GraphEditDialogueTest` 加一個測試。替身模型 6/6。

**機器 B 真模型結果（2026-11-10，兩次）：第一次 6 情境 6/6；加了刪線、撤銷後第二次 8 情境 8/8；兩次路由校準都是 33/33 正確、改圖例句否決 0/33（第二次已含 6 句刪線例句）。**

| 測試句 | 工具回應 | 結果 |
|---|---|---|
| 1 · 漏邊：kafka-connect → postgres | 開啟新增、證據列出 Debezium JSON；選 1 → 建議 db → 寫入 | PASS |
| 2 · 漏邊：storefront-nextjs → storefront（換一種說法、貼自己的出處） | 開啟新增；貼 `storefront/.env.production:1` → 讀到那一行、documented → 寫入 | PASS |
| 3 · 英文、名字對不上：cart → Stripe API | 問「你指的是…或新增」；新增 → 外部主機 → 沒有出處 → inferred 寫入 | PASS |
| 4 · 沒講來源：→ tax | 反問哪個服務呼叫 tax；取消後圖不變 | PASS |
| 5 · 是問題、不是改圖 | 照常回答，不開新增、圖不變 | PASS |
| 6 · 新增進行中途插問題 | 第二句當問題回答，並提醒新增還開著；取消後圖不變 | PASS |
| 7 · 刪線再撤銷：cart → product | 確認時列出這條線的出處（ProductService.java）；確定後少一條；撤銷後回到原樣 | PASS |
| 8 · 加線再撤銷：kafka-connect → postgres | 寫入後多一條；undo 後回到原樣 | PASS |

路由校準（hold-out 33 句）：accuracy 33/33；confident 且正確 24、confident 但錯 0；正確的最低分 0.541、錯的最高分 0.000；門檻 T=0.51 M=0.10 H=0.80 沒動。改圖例句一句正常問句都沒擋掉。完整對話與校準表在機器 B 的 `experiments/2026-11-06-graph-edit/live-yas.md`、`router-calibration.txt`（尚未傳回）。

機器 B 上的測試句（報告 thread 裡直接打，不用 @bot）：

1. `圖上少了 kafka-connect 連到 postgres 的線，幫我加上` → 應列出 Debezium JSON
2. `storefront-nextjs 其實有呼叫 storefront，把這條線補上去`
3. `add an edge from cart to Stripe API` → 應問「你指的是…或新增」
4. `幫我加一條連到 tax 的線` → 應反問來源
5. `cart 會不會呼叫 product？` → 應照常回答（不能被當成改圖）
6. 進行中途問 `postgres 被誰用？` → 應回答問題、並提醒新增還開著
7. `把 cart 到 product 那條線拿掉` → 確認時列出 `ProductService.java` 的出處；`確定` 後少一條；`撤銷` 後回到原樣（11/10 加）
8. 第 1 句加完 kafka-connect → postgres 之後 `undo` → 回到原樣（11/10 加）

11/10 在路由的改圖例句裡加了 6 句刪線的說法，**路由校準要在機器 B 重跑一次**（腳本已包含），確認 edit veto 仍是 0。

## 6. 程式

| 檔案 | 內容 |
|---|---|
| `Qa/GraphEditDialogue.java` | 對話狀態機（每步的問題與回答解析、中英文） |
| `Qa/EvidenceFinder.java` | 依要求找證據（三種痕跡、歸屬、「為什麼沒畫」） |
| `Graph/GraphEdit.java` | 一筆人工修改（add-edge／add-node／remove-edge）：套用、JSON、依序重放 |
| `Graph/CodeGraphMerger.fileOwners` | 合併器的歸屬判斷，給找證據用 |
| `Qa/SemanticRouter.java` | `EDIT_EXAMPLES` 與否決（不動 `INTENTS`） |
| `Qa/GraphQuery.java`、`graph_query_plan.txt` | `add-edge`、`add-node`、`remove-edge` 三個運算子 |
| `Qa/ReportQaService.java` | 接進 thread；clone 快取；寫入與貼圖；「撤銷」（每筆修改存修改前的圖在 `ReportArchive.editHistory`） |
| `DependencyAnalysisStateStore`、`DependencyReportService` | 每個 repo 的人工修改檔；下次分析重放 |
| `MermaidEmitter`、`DotEmitter` | 人工新增的箭頭標「human」 |

測試：`GraphEditDialogueTest` 20 個（假 repo；含刪線、反方向、e2e 目錄降分）；`GraphEditUndoTest` 3 個（走 `respond()`：刪線記進 repo、撤銷拿掉、進行中撤銷＝取消）；`GraphEditProbeTest`（`-Dprobe.edit.repo=`，真 checkout）；`GraphEditLiveProbeTest` 8 個情境（機器 B）。23 系統回歸 same 23／different 0。
