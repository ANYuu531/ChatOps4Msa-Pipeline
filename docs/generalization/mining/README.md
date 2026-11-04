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

## 結果（2026-11-03）

| 步驟 | 數字 |
|---|---|
| 網羅 | 搜尋 1,492 個 repo，依星數評估 849 個：286 無部署描述檔、206 像框架、153 服務 < 6、27 太大、8 已用過、3 語言比例不符、**65 被作者篩掉**、101 合格 → 語料 100＋hold-out E 1 |
| 殘餘 | 2,845 筆、803 種形狀，108 種出現在 ≥ 2 個 repo（`out/clusters.tsv`） |
| LLM 判讀 | 108 次；312 個樣本：相依 108、來源不對 54、不是 130、不確定 20；46 群「有通用規則」（`llm/summary.md`） |
| 作者查證後寫的規則 | 57 多文件 YAML、58 .NET Options 鍵、59 Service selector、60 掛載的設定檔、61 Ocelot（另修 r2dbc: 前綴、範本設定檔）；語料中各在 18／16／5／15／4 個系統觸發 |
| 不寫的 | 只綁 mesh 的 Istio VirtualService（規則 56 刻意不算邊）、.NET Aspire AppHost、Ingress 對外主機名、裸字串字面值（LLM 也判斷不能以語法分開） |
| 效果 | 28 個系統的圖改變（邊 +207／−8）；殘餘 2,845 → 2,721、跨 repo 形狀 108 → 96；新邊抽 40 條人工查證 34 條對 |
| 轉移 | hold-out E 0/8 → 0/8；B／C／D、AICS 不變；23 個回歸系統只多遙測 variant 邊與 petclinic 一條正確的邊 |
| 邊審查第二版 | 279 條（抽自規則 57 前的圖）：correct 101、declared-only 156、wrong 10、unsure 12。作者查 wrong＋unsure 22 條、declared-only 中不以 depends_on 為出處的 55 條理由：**確定錯 11 條（3.9%，下限）**，規則 57–61 後仍在。兩種形狀：共用環境變數來源（env_file／envFrom ConfigMap，4 系統 7 條）、程式碼中不是呼叫目標的 URL 字串（RFC 連結、ProblemDetails type、claim、Swagger 授權連結，4 系統 4 條）；另 2 條 wrong 是 LLM 誤判（片段沒截到路由表、第一個出處不是決定的檔案） |

**邊審查和殘餘探勘是兩半**：探勘只找「宣告了卻沒畫」（漏畫），邊審查找到的兩種形狀都是「多畫」，探勘看不到。逐條查證的清單：

| 形狀 | repo | 邊 |
|---|---|---|
| 共用 env_file | uid4oe/microservices-go-grpc | advice-service → user-service、postgres → advice-service、postgres → user-service |
| 共用 env_file | uid4oe/grpc-web-demo | postgres → mongodb、alva-offer → alva-order（實際是 order 呼叫 offer） |
| 共用 env_file | ebosas/microservices | database → redis（用 redis 的是 cache 與 server） |
| 共用 envFrom ConfigMap | hoangtien2k3/ecommerce-microservices | tax-service → kafka（ConfigMap 給 14 個服務） |
| URL 字串 | ebubekirdinc/SuuCat | assessment.api → tools.ietf.org |
| URL 字串 | meysamhadeli/booking-microservices-java-spring-boot | buildingblocks → problems |
| URL 字串 | thangchung/clean-architecture-dotnet | samples → bob.com |
| URL 字串 | ivanpaulovich/clean-architecture-manga | accounts-api → raw.githubusercontent.com |
| LLM 誤判（邊是對的） | tanerdiler/…-kubernetes | k8n-service-gateway → containerized-accounts（路由 `lb://CONTAINERIZED-ACCOUNTS`） |
| LLM 誤判（邊是對的） | rcherara/microservice-architecture | gateway-service → discovery-service（pom 的 Eureka client，僅推測） |

**hold-out E 沒有轉移的原因**是探勘方法的盲點：殘餘只找 repo 宣告過的名稱，E 的應用服務以 `config.development.json` 的 `localhost`＋port 連到 Compose 發佈的 port，這種間接指向在探勘中看不到。這是看到 E 之後才發現的；為了讓 E 保持乾淨，探勘程式沒有改。

範圍普查（`scope_census.py` → `scope-census.md`）：語料中範圍外最常見的是 Ingress（18 個系統）、Secret 參照（16）、NATS／Redis pub/sub 等其他 broker（11）、Dapr 服務呼叫（7）、Compose profiles（7）。
