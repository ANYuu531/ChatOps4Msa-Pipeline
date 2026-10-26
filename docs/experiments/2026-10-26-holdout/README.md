# 假想敵實驗第二批：hold-out 系統與 AICS

> 接 `docs/experiments/2026-10-20/`（7 個歸納用系統）。COMPSAC 效度段寫明「RQ0 的假想敵只在 7 個歸納用系統上執行，尚未在 hold-out 系統上重複」，這個資料夾補那一塊。
> 腳本同一支：`docs/experiments/2026-10-20/pure_llm_graph.py`，加了 `--set holdout` 與 `--set aics`。

## 跑什麼

| `--set` | 系統 | 參考邊集 | DepWeaver 靜態層輸出（第三輪規則後） | 輸出到 |
|---|---|---|---|---|
| `holdout` | opentelemetry-demo、mall-swarm、hotel-reservation（hold-out B）；yas、run-aspnetcore-microservices（hold-out C） | `docs/generalization/holdout{,-c}/truth/` | `docs/generalization/holdout{,-c}/<name>.r3.summary.md` | 這個資料夾（`raw/`、`pure-llm-graph.{md,csv}`、`budget.md`） |
| `aics` | AICS（私有，13 個 repo 放同一個父目錄） | `~/Documents/aics-eval/truth/aics.tsv` | `~/Documents/aics-eval/out/2026-10-26-r3/aics.summary.md` | `~/Documents/aics-eval/out/llm/`（**不進 git**） |

三組（`knowledge`／`files`／`files-nonames`）、每格 3 次、預算與模型都和 10/20 一樣，數字才能並排。

## 在機器 B 上的步驟

```bash
cd ~/Desktop/ChatOps4Msa-Pipeline && git pull

# 1. 取 5 個 hold-out 系統到固定的 commit（和寫參考邊集時同一版）
bash docs/experiments/2026-10-26-holdout/fetch_repos.sh ~/Desktop/holdout-repos

# 2. 計分器自檢：印出的 DepWeaver 列要等於 docs/generalization/holdout/scores-r3.md 與 holdout-c/scores-r3.md
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set holdout --selfcheck

# 3. 不花錢的 dry-run：產 prompts/ 與 budget.md，看每個系統給了多少檔
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set holdout --repos ~/Desktop/holdout-repos --dry-run

# 4. 正式跑：5 系統 × 3 組 × 3 次 = 45 次呼叫（估 300–400 萬 prompt token，gpt-4.1-mini 約 US$2）
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set holdout --repos ~/Desktop/holdout-repos --runs 3

git add docs/experiments/2026-10-26-holdout && git commit -m "experiments: pure-LLM baseline on the five hold-out systems" && git push
```

AICS（私有程式碼會送到 OpenAI；擁有者已同意）：

```bash
# 1. 13 個 repo 放同一個父目錄（機器 B 的 gh 要能讀那個私有 org；或從 Mac 把 ~/Documents/aics-eval 與 clone 一起 scp 過去）
mkdir -p ~/Desktop/aics && cd ~/Desktop/aics
# 13 個 repo 的名單與 commit 在 ~/Documents/aics-eval/heads.tsv（私有，不寫在這裡）：逐一 gh repo clone <org>/<repo> -- --depth 1
# commit 以 ~/Documents/aics-eval/heads.tsv 為準（參考邊集是對那一版寫的）

# 2. 參考邊集與靜態層輸出：把 Mac 的 ~/Documents/aics-eval 整個資料夾放到機器 B 的 ~/Documents/aics-eval
cd ~/Desktop/ChatOps4Msa-Pipeline
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set aics --selfcheck
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set aics --repos ~/Desktop --dry-run    # --repos 指到放 aics-repos 的那一層
python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --set aics --repos ~/Desktop --runs 3     # 9 次呼叫
# 結果在 ~/Documents/aics-eval/out/llm/，整個資料夾 scp 回 Mac，不 commit
```

`--repos` 的規則：目錄底下要有腳本 `HOLDOUT`／`AICS` 表裡的 checkout 名（`opentelemetry-demo`、`mall-swarm`、`DeathStarBench`、`yas`、`run-aspnetcore-microservices`；AICS 是名叫 `aics` 的父目錄，上面第二段已直接 clone 到 `~/Desktop/aics`）。

- 金鑰：讀該機器 `src/main/resources/application.properties` 的 `openai.api.key`，或環境變數 `OPENAI_API_KEY`。**不要從 Mac 跑。**
- 中斷了直接重跑同一行，`raw/` 裡已有的回應會跳過。
- 跑完要補進論文的地方：COMPSAC 表 V 加「hold-out」列、效度段刪掉「尚未在 hold-out 系統上重複」那句；AICS 的數字只寫總分，不寫服務名。

## 要回答的問題

1. 零檔案組在 AICS 上是不是接近 0？（私有系統不在訓練資料裡，這是 10/20 推測「對私有系統只會更差」的直接證據。）
2. 給檔案組在 hold-out 上贏過第三輪規則後的靜態層多少？贏的地方是不是又指出新的規則缺口？
3. 不給名稱組在 .NET（`catalog.api` 這種含點的名字）與 polyrepo（AICS）上對名的困難。
