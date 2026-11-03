# 規則探勘：先網羅案例、再找規則（2026-11-03 起）

回應老師 11/3 的第 1 點：「是否可能請 AI 直接去網羅微服務的案例（我們有支援的文法），然後從中找出規則，而非我們測一次修一次」。

前四輪的做法是「挑一組系統 → 寫參考邊集合 → 看錯與漏 → 補規則」，每輪只看 2–3 個系統，規則的來源是那幾個系統的寫法。這裡改成：

1. **網羅**：用固定、寫下來的條件從 GitHub 搜尋一百個左右的微服務系統（`collect_corpus.py`），不是人挑的。
2. **找殘餘**：對每個系統跑靜態層（`MiningProbeTest`），再以程式找出「repo 自己宣告的服務名，出現在位址形狀的位置，但圖上沒有對應的邊」的地方（`mine_residue.py`）。這一步不用 LLM，也不需要參考邊集合。
3. **依形狀分群、依跨 repo 數排序**：同一種「檔案種類 × 語法位置 × 鍵名慣例」的殘餘歸成一群；出現在越多 repo 的群越優先。只出現在一個 repo 的群不做規則（那是單一系統的寫法）。
4. **請 LLM 判讀**（`label_clusters.py`，機器 B）：每群給 6 個不同 repo 的樣本與前後文，判斷是不是相依、能不能用一條只講語法與檔案種類的通用規則分開。另外從工具已經畫出的邊抽樣，請 LLM 判斷出處那一行是否支持那條邊，用來估計工具在這一百個沒看過的系統上的 precision。
5. **作者逐條查證**，才寫規則；寫完以 **hold-out E**（從同一批合格候選中以固定種子抽出、從未被探勘）與 AICS 量測轉移，並對前面 23 個系統做逐邊回歸。

和前四輪的差別：規則的候選來自一百個系統的頻率，而不是一兩個系統的錯誤；「這條規則是不是通用」在寫之前就有數字（出現在幾個 repo）。

## 檔案

| 檔案 | 內容 |
|---|---|
| `collect_corpus.py` | GitHub 搜尋與機械條件、作者篩選、固定種子抽 hold-out E；API 回應快取在 `.cache/`（不進 git） |
| `screening.tsv` | 作者篩選：每個通過機械條件的 repo 一行（收／不收、理由） |
| `candidates.tsv` | 依星數排序評估過的每個候選與判定 |
| `corpus.tsv`、`holdout-e.tsv` | 語料（固定 sha）與 hold-out E |
| `fetch_corpus.sh`、`fetch_regression.sh` | 以固定 sha 取語料／取回歸用的 23 個系統 |
| `mine_residue.py` | 殘餘探勘：`residue.tsv`（每筆殘餘）、`clusters.tsv`（分群排序）、`samples.jsonl`（給 LLM 的群樣本）、`edge_samples.jsonl`（給 LLM 的已畫邊樣本） |
| `label_clusters.py` | LLM 判讀（只在機器 A／B 跑） |

## 篩選條件（`collect_corpus.py` 檔頭有完整版）

機械條件：GitHub topic 為 microservices／microservice／microservices-architecture、主要語言是靜態層有 tree-sitter 文法的 8 種之一（Java、Python、Go、JavaScript、TypeScript、PHP、Kotlin、C#）、公開、非 fork、未封存、≥ 30 星；不是評估中已用過的系統；名稱與描述不像框架或清單；有部署描述檔；≥ 6 個可部署的服務；原始碼 ≥ 80% 是支援的語言；不超過 300 MB。

作者篩選：是由 ≥ 6 個會互相溝通的元件組成的**應用系統**（展示、範例或真實產品都算）；不收框架、中介軟體與平台產品、開發工具、獨立範例的合集、書或課程的章節複本、重複的 fork。機械條件擋不掉這些（例如 temporal、tyk、kgateway 都有 ≥ 6 個 Dockerfile），所以加這一道，理由逐筆寫在 `screening.tsv`。

## 重現

```bash
cd docs/generalization/mining
python3 -I collect_corpus.py --n 100                 # 第一次會列出 to-screen.tsv；判讀後寫進 screening.tsv 再跑一次
bash fetch_corpus.sh corpus.tsv ~/Documents/depweaver-eval/corpus
cd ../../.. && mvn -o test -Dtest=MiningProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.list=$HOME/Documents/depweaver-eval/corpus/list.tsv -Dprobe.outdir=$HOME/Documents/depweaver-eval/out/corpus
python3 -I docs/generalization/mining/mine_residue.py --list ~/Documents/depweaver-eval/corpus/list.tsv \
  --probe-out ~/Documents/depweaver-eval/out/corpus --out docs/generalization/mining/out
```

## 機器 A／B：LLM 判讀（會打 OpenAI，不能在本機跑）

不需要任何 checkout：程式碼的前後文已經包在 `out/samples.jsonl`（108 群，227 KB）與 `out/edge_samples.jsonl`（279 條邊，195 KB）裡。只用 Python 標準函式庫；金鑰讀 `src/main/resources/application.properties` 的 `openai.api.key`（或環境變數 `OPENAI_API_KEY`）。共 136 次呼叫、約 13 萬 prompt token；輸出約 1–2 MB。

```bash
df -h ~                                   # 先確認還有空間（幾 MB 就夠）
cd ~/Desktop/ChatOps4Msa-Pipeline && git pull
python3 -I docs/generalization/mining/label_clusters.py --task clusters \
  --in docs/generalization/mining/out/samples.jsonl --out docs/generalization/mining/llm/clusters
python3 -I docs/generalization/mining/label_clusters.py --task edges \
  --in docs/generalization/mining/out/edge_samples.jsonl --out docs/generalization/mining/llm/edges
git add docs/generalization/mining/llm && git commit -m "mining: LLM labels (machine B)" && git push
```

中途斷掉直接重跑同一行即可：已存在 `raw/` 的呼叫會跳過。

**邊審查第二版（2026-11-03）**：第一版（`llm/edges/`）給沒有行號的描述檔出處（Compose 的 depends_on、environment，pom.xml 的 discovery client）配錯了行——給的是目標自己的定義，不是證據——126 條 depends_on 邊裡 75 條因此被判 wrong。第二版定位到來源服務區塊裡的那一行、告訴模型每條邊的信心等級，並多一個判定 declared-only（只宣告了關係，正是「僅推測」所主張的）。同一組邊（同一個種子），輸出到 `llm/edges-v2/`；第一版保留。

```bash
cd ~/Desktop/ChatOps4Msa-Pipeline && git pull
python3 -I docs/generalization/mining/label_clusters.py --task edges \
  --in docs/generalization/mining/out/edge_samples.jsonl --out docs/generalization/mining/llm/edges-v2
git add docs/generalization/mining/llm && git commit -m "mining: edge audit v2 (machine B)" && git push
```

回歸（23 個系統逐邊比對，30 秒）：`bash fetch_regression.sh ~/Documents/depweaver-eval/repos`，再跑 `regress.sh`。
