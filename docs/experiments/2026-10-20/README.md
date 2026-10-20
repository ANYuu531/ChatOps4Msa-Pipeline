# 假想敵實驗：純 LLM 抽相依圖 vs DepWeaver 靜態層

> 回應老師 2026-10-19 第 1 點：「實驗要有假想敵。如果只用純 AI、不讓 AI 基於事實，是否真的無法產生正確的圖？」
> 設計寫在 `docs/threshold-design.md` §9.5（2026-09-18 定、當時未實作）。本資料夾是它的實作。
> **狀態（2026-10-20）：機器 B 跑完 63 次呼叫（commit f6f1072）。結論見 `findings.md`，總表 `pure-llm-graph.md`。**

## 問題

COMPSAC 稿目前的對照（§IV-B RQ2、threshold-design §9）比的是**問答層**：同一張圖、三種給 LLM 的方式。老師要的是**抽圖層**的假想敵：不用 DepWeaver，直接讓 LLM 畫圖，畫得出來嗎？

## 設計

同一批專案（RQ1 的 7 個、有作者參考邊集的那 7 個）、同一份參考邊集（`docs/generalization/truth/<name>.tsv`）、同一套計分（`GeneralizationScoreTest` 的規則，本腳本重寫一份並以 `--selfcheck` 驗證兩邊數字相等）。比四組：

| 組別 | 給 LLM 什麼 | 回答老師的哪一半 |
|---|---|---|
| `knowledge` | 只有 repo 網址＋元件清單，**零檔案** | 「不讓 AI 基於事實」：模型靠記憶畫 |
| `files` | 元件清單＋預算內的部署描述檔、設定檔、原始碼 | 「讓 AI 看事實，但沒有工具」：LLM 自己讀檔 |
| `files-nonames` | 同上，但**不給元件清單**，名字它自己取 | 最接近「把 repo 丟給 AI」；名字對不到參考邊集的邊不計分、另外列出 |
| `depweaver-static` | tree-sitter＋設定解析＋14 條規則，不呼叫 LLM | 現行工具（數字直接取 `docs/generalization/scores.md`） |

- 模型：`gpt-4.1-mini`（和問答層對照同一個），temperature 0，JSON mode；每組每專案跑 3 次，報平均與最小–最大。
- 元件清單給 `knowledge`／`files` 是**偏袒 LLM** 的設計：它不用猜名字，只要畫邊。這樣輸了才有說服力；`files-nonames` 則是不偏袒的版本。
- **預算寫死在程式裡**（`BUDGET`）：部署描述檔與設定檔整檔給、上限 300 KB；原始碼上限 500 KB、單檔 20 KB、檔名有 client/service/controller/config 等線索的優先；測試、vendor、產生的程式碼不給。`--dry-run` 會把每個專案實際給了哪些檔寫進 `budget.md`。7 個專案裡 5 個的原始碼**全部**塞得進預算（見 budget.md 的「給／有」欄），只有 TeaStore 與 bank-of-anthos 的描述檔有截斷。
- 方向：呼叫端 → 被呼叫端；佇列消費者也寫成 consumer → broker（和參考邊集一致）。`variant` 類的邊不計。

## 預期要寫進論文的三件事

1. **`knowledge` 組的數字**就是「不基於事實」的答案。預期：知名專案（Online Boutique、Bank of Anthos）recall 不低但會畫出早已不存在的邊；冷門專案（ewolff、ecommerce）幾乎畫不出來。
2. **`files` 組 vs `depweaver-static`**：同樣的檔案，LLM 讀和規則讀差在哪。特別看三個靜態層已知的邊界：TeaStore 的 enum 動態分派（工具 0/7）、robot-shop 的 Node/PHP、ecommerce 的 localhost 預設值。LLM 可能在這些地方贏（它看得懂 `Service.PERSISTENCE` 是誰），但 precision 要一起看。
3. **`files-nonames` 的「對不到的名字」數**：名字對不上就是 ③「請人確認名稱對應」要解的問題，純 LLM 這組會直接把它暴露出來。

文獻對照點：Liljas 等人（ESEM 2026）讓 LLM agent 自己重建微服務架構，GPT-4o 平均 F1 約 0.6；本實驗是同一個問題、用我們的參考邊集再量一次。

## 偏袒與界線（要和結果一起寫）

- 參考邊集是作者整理的（README 在 `truth/`），計分只保證算得對，不保證答案對；MicroDepGraph 那 7 個系統的第三方邊集還沒接進來（下一步）。
- `files` 組的預算是我們定的；LLM 看不到的檔案，它畫不出來不算它的錯。所以 `budget.md` 要附在結果旁邊，而且 5 個專案原始碼全給了。
- gpt-4.1-mini 不是最強的模型。要補一組 `gpt-4.1` 或 `o4-mini` 只要改 `OPENAI_MODEL`，但成本是 files 組 21 次呼叫 × 約 15 萬 token。
- 3 次重複只能看變異，不是統計檢定。

## 怎麼跑（在機器 A 或 B；不從本機跑）

機器 B（`~/Desktop/ChatOps4Msa-Pipeline`）的步驟：

```bash
cd ~/Desktop/ChatOps4Msa-Pipeline && git pull
python3 --version                              # 3.8 以上即可，只用標準函式庫，不用 docker、不用 mvn
mkdir -p ~/Desktop/baseline-repos && cd ~/Desktop/baseline-repos
for r in GoogleCloudPlatform/bank-of-anthos ewolff/microservice-kubernetes \
  SelimHorri/ecommerce-microservice-backend-app sqshq/piggymetrics DescartesResearch/TeaStore \
  GoogleCloudPlatform/microservices-demo instana/robot-shop; do git clone --depth 1 https://github.com/$r.git; done
cd ~/Desktop/ChatOps4Msa-Pipeline
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --selfcheck
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos ~/Desktop/baseline-repos --dry-run
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos ~/Desktop/baseline-repos --runs 3
git add docs/experiments/2026-10-20 && git commit -m "experiments: pure-LLM graph baseline, 3 runs" && git push
```

- 金鑰：讀該機器 `src/main/resources/application.properties` 的 `openai.api.key`（機器 B 那份不在 git 裡，不會被 pull 蓋掉）。
- 中斷了直接重跑同一行，`raw/` 裡已有的回應會跳過。
- `budget.md` 會被該機器的 checkout 覆寫（commit 可能和 10/20 本機 dry-run 的不同），這是對的，以實際跑的那份為準。

一般步驟：

```bash
# 1. 七個 checkout 放同一個目錄（名字見腳本 PROJECTS）；10/20 用的是當天 HEAD，commit 在 budget.md
cd <某個目錄> && for r in GoogleCloudPlatform/bank-of-anthos ewolff/microservice-kubernetes \
  SelimHorri/ecommerce-microservice-backend-app sqshq/piggymetrics DescartesResearch/TeaStore \
  GoogleCloudPlatform/microservices-demo instana/robot-shop; do git clone --depth 1 https://github.com/$r.git; done

# 2. 在 ChatOps4Msa-Pipeline 根目錄
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --selfcheck            # 計分器對得上 scores.md 才往下
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos <目錄> --dry-run # 產 prompts/ 與 budget.md，不花錢
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos <目錄> --runs 3  # 63 次呼叫
```

- 金鑰讀 `src/main/resources/application.properties` 的 `openai.api.key`，或環境變數 `OPENAI_API_KEY`；模型 `openai.api.model`／`OPENAI_MODEL`。
- 每次回應存在 `raw/<專案>.<組>.run<N>.json`，重跑會跳過已有的（可續跑）。
- 產出：`pure-llm-graph.md`（總表＋每組的錯與漏）、`pure-llm-graph.csv`。
- 費用估計：files 與 files-nonames 兩組 42 次呼叫、每次 4 萬–18 萬 token，合計約 600 萬 input token；gpt-4.1-mini 約 US$2.5–3。knowledge 組可忽略。

## 檔案

- `pure_llm_graph.py` 腳本（只用標準函式庫）
- `budget.md` `--dry-run` 產生：每個專案給了哪些檔、多少 bytes、commit
- `prompts/` 不進 git（可重新產生）
- `raw/`、`pure-llm-graph.md`、`pure-llm-graph.csv` 跑完才會有
