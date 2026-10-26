# 假想敵實驗結果（2026-10-20，機器 B 跑，gpt-4.1-mini，3 runs × 3 arms × 7 projects，63 次呼叫、326 萬 prompt token）

原始輸出 `raw/`、總表 `pure-llm-graph.md`、CSV `pure-llm-graph.csv`、給了哪些檔 `budget.md`。本檔是讀過之後的結論，數字都能從那四樣算回來（下面的敏感度分析用 `analysis` 段的程式重算，沒有再呼叫 API）。

## 一句話

**不給事實，LLM 畫不出正確的圖：七個專案全部有漏邊、六個另有錯邊（macro F1 0.70；ewolff 三次都只漏不錯）。給它事實（repo 檔案），它畫得和 DepWeaver 靜態層一樣好、召回甚至更高（macro F1 0.90 vs 0.88），但代價是沒有出處、同一個 prompt 三次結果不一樣、名字一不給就全盤對不上。** 所以論文的主張不能是「LLM 做不到」，而是「LLM 需要被餵事實，而餵了之後它給的東西沒辦法查證、不穩定、名字對不上」，這三件正好是 DepWeaver 的出處、確定性規則、請人確認名稱在解的問題。

## 總表（21 run 的 macro 平均；depweaver 是 scores.md 的 7 個專案平均）

| 組別 | 給 LLM 什麼 | P | R | F1 | 自迴圈邊佔錯邊 | 截斷 run | 對不到的名字 |
|---|---|---|---|---|---|---|---|
| knowledge | repo 網址＋元件清單，零檔案 | 0.76 | 0.68 | 0.70 | 0/101 | 0 | — |
| files | 元件清單＋預算內檔案 | 0.92 | 0.91 | 0.90 | 32/45 | 0 | — |
| files-nonames | 檔案，名字自己取（嚴格對名） | 0.79 | 0.77 | 0.77 | 8/10 | 2 | 85 |
| **depweaver-static** | 14 條規則，不呼叫 LLM | **0.98** | **0.84** | **0.88** | — | — | — |

每專案 F1（run 平均；files 兩組已去掉自迴圈邊，見敏感度 1）：

| 專案 | knowledge | files | files-nonames（嚴格） | depweaver-static |
|---|---|---|---|---|
| bank-of-anthos | 0.69 | 1.00 | 1.00 | 1.00 |
| ewolff-k8s | 0.83 | 1.00 | 1.00 | 1.00 |
| ecommerce | 0.71 | 0.93 | **0.00** | 0.69 |
| piggymetrics | 0.73 | 0.81 | 0.85 | 0.91 |
| teastore | 0.57 | 0.85 | 0.67 | 0.63 |
| online-boutique | 0.77 | 0.95 | 1.00 | 1.00 |
| robot-shop | 0.61 | 0.96 | 0.98 | 0.92 |

## 回答老師的問題

**1. 「不讓 AI 基於事實，是否真的無法產生正確的圖？」——是，七個都不對。** knowledge 組沒有一個專案 P、R 同時到 1.00。錯的型態三種：
- **把別人的架構套上來**：robot-shop 畫了 11 條錯邊，其中 6 條是 `load → 每個服務`（把負載產生器當成入口）；TeaStore 畫了 `teastore-all → 其他全部`（teastore-all 是「全部裝在一個 pod」的部署變體，不是呼叫者）。
- **資料庫接錯**：Bank of Anthos 把 balancereader、ledgerwriter 接到 accounts-db（實際是 ledger-db）；robot-shop 把 cart、ratings 接到 mongodb（ratings 用 mysql）。
- **方向反**：piggymetrics 4 條、ecommerce 2 條是把 gateway → service 畫成 service → gateway。
而且它漏的是最平常的邊（Bank of Anthos 漏 `frontend → ledgerwriter`、`userservice → accounts-db`）。這七個都是 GitHub 上的公開專案、很可能在訓練資料裡，模型還是只有 0.70；對私有系統，這一組等於 0。

**2. 「那如果讓 AI 基於事實呢？」——它變得很強，這一點要誠實寫。** files 組 macro F1 0.90，去掉自迴圈邊後 P 0.97。在 DepWeaver 靜態層的三個已知邊界上它都贏：
- TeaStore 以 enum 常數指定目標：DepWeaver 業務邊 0/7，LLM 讀得懂 `Service.PERSISTENCE` 是誰，業務邊 P/R 3 次平均 1.00/0.67（三次的 R 是 1.00、1.00、0.00）。（10/23 更正：原本寫 1.00/1.00，是只取第 1 次執行；另外這不是動態分派，enum 的字串 `tools.descartes.teastore.persistence` 就是模組名，靜態上可以解析。）
- ecommerce 的 localhost 預設值：DepWeaver 控制面邊 R 0.28（規則丟掉 localhost，不編造），LLM 3 次平均 0.81（0.96、0.88、0.60）。給 LLM 的檔案裡沒有任何真正的主機名：→ cloud-config 只有 `localhost:9296` 對上 cloud-config 的 `port: 9296`，→ service-discovery 只有 eureka-client 依賴與 `@EnableEurekaClient`（Spring Cloud 隱含預設 `localhost:8761`），LLM 是照框架慣例推出來的。（10/23 更正：原本寫 0.96，是只取第 1 次執行；原本舉的 `${EUREKA_HOST:localhost}` 在 repo 和 prompt 裡都不存在。）
- robot-shop 的 Node／PHP：DepWeaver 業務邊 R 0.40，LLM 1.00。
反過來 piggymetrics 它輸：DepWeaver R 1.00（P 0.83，多畫 7 條共用設定造成的邊），LLM R 0.71；3 次都漏的 7 條是 gateway 到 4 個服務、notification 與 statistics 到 auth-service（業務邊），以及 turbine 到 rabbitmq；到 registry 的控制面邊只有 1 次漏。（10/23 更正：原本說漏的是控制面邊，是只看第 1 次執行。）

**3. 但 LLM 給的東西有三個 DepWeaver 沒有的問題，每一個都在這次資料裡出現了：**
- **沒有出處。** 63 次輸出裡沒有一條邊能回答「哪個檔案哪一行」；DepWeaver 每條邊都有，而且 `TruthProvenanceResolvesTest` 檢查過 92 條出處全部打得開。這不是加一句 prompt 就能補的：要它附行號，就回到「LLM 重述事實會錯」的問題。
- **不穩定。** temperature 0、同一個 prompt：TeaStore files 組 R 0.46–1.00；files-nonames 三次裡一次把元件取名 `registry/db/persistence`（沒有 `teastore-` 前綴）整組對不上；Bank of Anthos files-nonames 兩次陷入迴圈，同樣 12 條邊重複輸出 1 417 次直到 32 768 token 的上限。DepWeaver 同一份 checkout 跑幾次都一樣。
- **名字。** 一不給元件清單，ecommerce 三次都是 0.00：它用 Compose 的 `container_name`（`user-service-container`）而不是服務名；Bank of Anthos 多出 `jwt-key`（一個 Secret）、`istio-ingressgateway`；Online Boutique 多出 `alloydb`、`spanner`（README 提到的選配）。這正是 ③「請人確認名稱對應」要解的問題——不是規則對不上才需要人，LLM 對不上也一樣需要。

## 敏感度分析（結果旁邊要一起寫）

1. **自迴圈邊。** files 組 45 條錯邊有 32 條是 `X → X`（Online Boutique 一次 12 條、ecommerce 7 條），顯然是模型照抄元件清單。這種邊一行程式就能濾掉；濾掉後 files 組 P 0.92 → 0.97、F1 0.90 → 0.93。上面每專案表用的是濾掉後的數字，總表兩個都列。
2. **寬鬆對名。** files-nonames 嚴格對名 macro 0.79/0.77；如果允許「去掉 `-container` 後綴」與「唯一的前綴／後綴吻合」（`persistence` ↔ `teastore-persistence`），變成 **0.98/0.95**，ecommerce 從 0.00 變 0.96、TeaStore 從 0.64 變 0.96。也就是說 files-nonames 的分數幾乎全部是名字問題，不是邊畫錯。兩個數字都要報：嚴格的是「交給使用者會看到什麼」，寬鬆的是「邊到底對不對」。
3. **元件清單偏袒 LLM。** knowledge 與 files 組拿到的元件清單來自參考邊集，等於先告訴它有哪些節點、哪個是資料庫。DepWeaver 沒有這個輸入。這是刻意的：讓 LLM 在最有利的條件下比，輸了才有說服力；結果它在 files 組沒有輸。
4. **預算。** 七個裡六個原始碼全給了（budget.md）；TeaStore 原始碼給了 162/174 個檔、Bank of Anthos 是描述檔截在 300 KB。LLM 沒看到的檔它畫不出來不算它的錯，但這兩個專案的 files 組 recall 已經 0.82、1.00，預算不是瓶頸。
5. **模型與重複次數。** 只有 gpt-4.1-mini、每格 3 次。3 次夠看出變異（TeaStore 0.46–1.00），不夠做檢定。

## 對論文的意思

- **COMPSAC 的假想敵寫成 RQ0**：純 LLM 零檔案 F1 0.70、有檔案 0.90–0.93（視自迴圈處理）、DepWeaver 靜態層 0.88，加上 Liljas ESEM 2026 的 agent 0.6 當文獻對照。結論寫三件事：零事實不行；有事實的 LLM 和規則各有輸贏（enum／localhost／Node 它贏，共用設定它輸）；它輸在出處、穩定、名字，而不是輸在 P/R。
- **DepWeaver 的定位要跟著調**：不是「取代 LLM」，是「LLM 當成管線裡的一個來源」——它在沒有文法的語言上本來就是這樣用（III-B 的 LLM 層），這次結果說明 enum 動態分派也可以交給它，但輸出要進同一個等級／出處框架、名字要走同一個確認流程。這是一個具體的未來工作，而且有數字支持。
- **AsianPLoP 那篇不受影響**：④「先算事實，再讓 AI 解釋」講的是「事實算好了之後不讓 LLM 重述」，這次實驗是「事實還沒算之前讓 LLM 自己讀」，兩回事；倒是 ③ 多了一個 Known Use 以外的佐證——LLM 自己取名時有 85 個名字對不上。

## 模型與參數（論文要附的）

| 項目 | 值 | 出處 |
|---|---|---|
| 模型 | `gpt-4.1-mini`（OpenAI Chat Completions API；63 次回應的 `model` 欄位都是這個別名；實際快照名當時沒存，10/20 之後的版本會存 `response_model`） | `raw/*.json` |
| temperature | 0 | `pure_llm_graph.py` `chat()` |
| 輸出格式 | `response_format={"type":"json_object"}`（JSON mode） | 同上 |
| max_tokens | 前 9 次呼叫（bank-of-anthos 三組）沒設，模型預設上限 32,768，其中 2 次撞到；之後的 54 次設 12,000，沒有再撞到 | `raw/*.json` 的 `completion_tokens`；commit `fa6c353` |
| 其他 | n=1、無 seed、無 system prompt、無 tool；4 個並行 worker | `chat()`、`--workers 4` |
| 重複 | 每組每專案 3 次（`--runs 3`），同一個 prompt | `pure-llm-graph.csv` 的 run 欄 |
| 輸入預算 | 部署描述檔＋設定檔 300,000 bytes、原始碼 500,000 bytes、單檔 20,000 bytes、最多 400 檔；原始碼依檔名線索（client/service/controller/config…）優先、小檔先；略過 test、vendor、node_modules、產生的 proto 等目錄 | `BUDGET`、`SKIP_DIR`、`SOURCE_HINT`；實際給了什麼在 `budget.md` |
| prompt | 三組的完整模板在 `build_prompt()`；邊的四類定義在 `CLASS_TEXT`；`prompts/` 可由 `--dry-run` 重新產生 | `pure_llm_graph.py` |
| 受測 checkout | 7 個 repo 2026-10-20 的 HEAD（commit 在 `budget.md`） | `budget.md` |
| 參考邊集 | `docs/generalization/truth/<name>.tsv`，`variant` 類不計 | `truth/README.md` |
| 計分 | 與 `GeneralizationScoreTest` 相同（`--selfcheck` 逐格相等）；files-nonames 的名稱比對＝小寫去非英數後完全相等 | `score()`、`canon()` |
| 用量 | 63 次呼叫，3,260,592 prompt token（其中 1,955,456 是同一 prompt 第 2、3 次呼叫命中的 prompt cache）＋ 105,074 completion token | `raw/*.json` 的 `usage` |
| 機器 | 機器 B（Ubuntu、Python 3.12），2026-10-20 | commit `f6f1072` |

## 限制

- 參考邊集是作者整理的（truth/README.md 說明了為什麼不叫 ground truth）；MicroDepGraph 的第三方邊集還沒接進來。
- 七個受測系統全部公開在 GitHub，knowledge 組對私有系統沒有意義（只會更差）。
- 「files 組贏」是在 150 KB–600 KB 的 prompt 上，每個專案一次呼叫 4 萬–18 萬 token；DepWeaver 靜態層不花 token。
- 沒有量 LLM 的邊能不能標信心等級；它輸出的 `class` 欄位沒有計分。

## analysis（重算敏感度用，不呼叫 API）

```python
# 在 repo 根目錄：python3 -I - <<'EOF' ... 讀 raw/*.json，用 pure_llm_graph 的 parse_edges/score 重算；
# 自迴圈：先濾掉 canon(s)==canon(t) 的邊再 score；寬鬆對名：canon 相等 → 去 container 後綴 → 唯一前綴/後綴吻合。
```
（完整程式碼在 2026-10-20 的對話紀錄；要固定下來的話加成 `--sensitivity` 參數。）
