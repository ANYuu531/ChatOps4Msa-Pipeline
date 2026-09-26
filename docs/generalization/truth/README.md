# 參考邊集（作者整理，出處逐條可複驗）

每個專案一個 TSV：`source<TAB>target<TAB>class<TAB>evidence`。

- `class`：`business`（服務→服務）、`data`（服務→資料庫/佇列）、`external`（→外部主機）、`control`（→config server / registry / 反向代理入口等控制面）、`variant`（只在某個部署變體才存在，例如 TeaStore 的 Kieker RabbitMQ；工具畫了不算錯，沒畫也不算漏）。
- `evidence`：**完整的 repo 相對路徑 `檔案:行`**，或 README 段落／架構圖的指名。
- 節點名用工具的 id（manifest 的 workload 名；沒有 manifest 時是 Compose 的服務名）。方向：呼叫端→被呼叫端；訊息佇列的消費者也寫成 `consumer -> broker`（工具目前不分方向）。

計分：`GeneralizationScoreTest` 讀這裡與 `../<name>.mmd`，輸出 `../scores.md`。`variant` 類不進分子分母。

## 這份東西的效力，以及為什麼不叫「標準答案」

它是**一個人依專案自己的文件與程式碼整理的讀法**，不是真值。同一個人也寫了被評的工具，所以「程式計分」只保證算得沒錯，不保證答案是對的（建構效度）。因此：

- 名稱一律用**參考邊集**，指標一律說**一致度**，不寫 ground truth／precision／recall。
- 強度更高的對照是 `docs/generalization-external.md`：**MicroDepGraph 資料集自己發表的邊集**，7 個專案，作者沒有參與標註。
- 每一條出處都要**第三方打得開**。`TruthEvidenceResolvesTest` 會檢查，打不開就失敗：

```bash
mvn -o test -Dtest=TruthEvidenceResolvesTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtruth.repos=<放各專案 checkout 的目錄>
```

  目前 92 條有檔案出處的條目全部打得開。2026-09-25 第一次跑這道檢查時抓到 **2 條寫錯的出處**（`dispatch/src/main.go` 實際是 `dispatch/main.go`；robot-shop 根本沒有 `ratings/html/API.php`，真正的呼叫點是 `ratings/html/src/Service/CatalogueService.php:27`）——邊是對的，出處不可用，而在舊的寫法下沒有人會發現。

- 同名檔案用**邊的來源服務**消歧（`<來源服務>/src/main/resources/bootstrap.yml`），不要寫成 `bootstrap.yml`。
- **出處指「哪裡發出呼叫」，不是「host 從哪來」。** 這個慣例是 2026-09-26 的第二標註者實驗逼出來的：同一條 `cart → catalogue`，獨立標註者引 `cart/server.js:30`（`catalogueHost = process.env.CATALOGUE_HOST`），本檔的慣例是引 `:362`（真正發出請求的那一行）。兩邊都查得住，但不寫下來就會有分歧。host 的來源寫在同一格的說明文字裡。
- 出處**不是檔案**時照實寫（TeaStore 的 `loadBalanceRESTOperation(Service.PERSISTENCE, …)` 是 enum 常數，目標在執行期由自家 registry 解析），並在報告裡說明它為什麼不能是路徑。

## 新增一個專案時

1. `GreenfieldProbeTest` 產圖（`docs/generalization/<name>.mmd` 與 `.summary.md`）。
2. 讀專案自己的 README／架構圖／程式碼，寫 `truth/<name>.tsv`，**出處一律寫完整路徑**。
3. `GeneralizationScoreTest` 計分、`TruthEvidenceResolvesTest` 檢查出處。
4. 每一條差異追到「一條通用規則」或「一個明確的語言／呼叫型態邊界」，寫進報告。
5. 如果這個專案也在 MicroDepGraph 資料集裡，順便加進 `ExternalTruthAgreementTest.CASES`——那一份對照不需要本檔案。
