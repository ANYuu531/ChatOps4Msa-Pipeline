# 問句修飾（question refiner）— 2026-11-12 老師反饋

> 老師：「問句分數低的部分，可以把我們例句做成 RAG 之類的讓 LLM 根據 RAG 先修飾問句，再跑接下來流程（不一定要是 RAG，看你是否有更好的想法）。」

## 做了什麼

路由沒把握的問句，先用它的向量從例句庫撈出最接近的 3 個意圖、每個 3 句例句（這是「把例句做成 RAG」的檢索），交給模型改寫成貼近例句的一句話，改寫句再走**同一套已校準的路由**；只有改寫句路由有把握、而且意圖與模型自己的標籤一致才採用，否則照舊交給 planner。設計與理由：`docs/report-qa-design.md` §2.4b「路由沒把握時先修飾問句」。程式：`SemanticRouter.shortlist`、`QuestionRefiner`、`prompts/question_refine.txt`、`ReportQaService.respond`。

## 要在機器 B 跑的實驗

```bash
cd ~/ChatOps4Msa-Pipeline && git pull
bash docs/experiments/2026-11-12-question-refiner/run-on-machine-b.sh
```

`QuestionRefinerHoldoutTest`：102 句 hold-out（`src/test/resources/qa/router-holdout.tsv`，三專案、中英各半、含 10 句圖答不了的）照正式路徑路由；沒把握的每一句做三件事，全部真的 OpenAI、temperature 0、答案快取：

| 手臂 | 是什麼 | 怎麼算對錯 |
|---|---|---|
| planner | 現況：planner prompt＋該專案節點 id → 計畫 | 計畫裡的算子對回意圖，任一個在 expected 內算對；空計畫算「none」（none 列空計畫算對） |
| rewrite | 老師的提議＋守門：改寫→重新路由→採用或拒絕 | 採用且意圖在 expected 內＝accepted-correct；採用但錯＝**accepted-wrong**（要為 0）；其餘 rejected |
| pipeline | 正式行為：採用就用改寫的路由，否則 planner | 兩者合成 |
| pick | 不改寫、直接信模型標籤（同一次呼叫） | 標籤在 expected 內算對；用來量「重新路由那道守門買到什麼」 |

產出：`refine-summary.md`（總表＋被拒原因＋採用但錯的句子＋採用的改寫句＋管線與 planner 不同的句子）、`refine-per-question.csv`（每句的改寫文字與三個手臂）、`router-calibration.txt`（33 句校準，確認有把握的那些沒被碰）。

## 要看的數字（第一部分：沒把握的句子）

1. 有把握比例：路由單獨（11/10 是 35.3%）→ 加上採用的改寫句之後。
2. 採用的改寫句裡錯的句數：門檻守的是 0，這裡也要是 0；不是 0 就看是哪種錯（方向、否定、信心等級混淆），再決定是加守門還是關掉。
3. 同一批沒把握的句子：planner 答對幾句 vs 管線答對幾句。管線沒贏 planner 的話，這一步只省呼叫不加品質，要老實寫。
4. pick 對 rewrite：直接信標籤如果一樣準、或更準，守門就是多餘的；如果 pick 錯得多，守門就有價值。

## 第二部分：有把握但「剛好過」的句子要不要再問一次（帶寬掃描）

0.541 那句當時是有把握的，不會走第一部分。第二意見機制與帶寬的定義、為什麼在這批 hold-out 上量不到好事、用哪兩組較鬆門檻的錯句當替身、選定規則，都寫在 `docs/threshold-design.md` §3.8。同一次跑就產出，不用另外呼叫模型。

看的數字：

1. 鬆門檻下錯的那十來句，分數離 T、領先離 M 各多少：錯的是不是真的集中在門檻附近。
2. 第二意見對它們說 DISAGREE 的有幾句（天花板是「H 以下全部」那一列的 caught）。
3. 選定的帶寬在目前門檻下：帶寬裡幾句（多幾次呼叫）、harm 要是 0、lost 幾句。
4. 選出來的 band.score／band.lead 填進 `application.properties` 的 `dependency.qa.refiner.band.*`，並把數字填回 §3.8；選出 0／0 就維持關閉，也照實寫。

## 結果（2026-11-19 機器 B，`refine-summary.md`；embedding text-embedding-3-small、chat gpt-4.1-mini、T/M/H 0.51/0.10/0.80、候選 3 意圖 × 3 例句）

102 句：路由有把握 36、沒把握 66（修飾跑了 57，9 句因為缺節點或像改圖沒跑）。

**第一部分**

| | 路由單獨 | 加上採用的改寫句 |
|---|---|---|
| 有把握 | 36（35.3%） | 85（83.3%） |
| 有把握但錯 | 0 | **5** |

同一批 66 句沒把握的：

| 手臂 | 對 | 錯 | none |
|---|---|---|---|
| planner（現況） | 60 | 3 | 3 |
| pipeline（採用就用改寫，否則 planner） | 60 | 5 | 1 |
| pick（直接信模型標籤） | 51 | 5 | 1 |

採用 49、被拒 8（模型說 none 7、改寫句路由沒把握 1）。被拒的 8 句裡 7 句期望就是 none，1 句（uncovered）期望不在候選裡、模型照規矩說 none，都是對的拒絕。

**採用但錯的 5 句，錯在哪**（`refine-per-question.csv`）：

| 句子 | 期望 | 候選（3 個） | 採用成 |
|---|---|---|---|
| accounts-db 當機的話，前端哪些功能會受牽連？ | impact-of | path、dependents-of、startup-needs | dependents-of |
| 有哪些依賴是經過實測確認存在的？ | observed-edges | mentioned-only、externals、db-users | mentioned-only |
| which services open connections to accounts-db or ledger-db? | db-users | path、dependents-of、dependencies-of | dependents-of |
| how does queue-master get its work, via a broker? | async | dependencies-of、dependents-of、path | dependencies-of |
| can you translate the report into Japanese? | none | about-report、async、db-users | about-report |

前 4 句**正確的意圖根本不在那 3 個候選裡**：模型只能在錯的裡面挑最像的，改寫句照著那個意圖的例句寫，當然路得很有把握（4 句都是 1.00），守門看不出來——它查的是「改寫句和模型標籤一致」，不是「改寫句和原句一致」。第 5 句是該說 none 卻硬選了候選。pick 也錯同樣 5 句：守門這次一句都沒多攔。

**四個數字的答案**：①有把握比例 35.3% → 83.3%；②採用但錯 5，不是 0；③管線對 planner：對的一樣 60，錯多 2（planner 錯的 3 句裡管線救回 1 句、none 的 2 句救回 2 句，但另外弄錯 4 句 planner 本來對的）；④pick 對 rewrite：一樣錯 5，守門沒買到東西。

**結論**：改寫這一步在「正確意圖有進候選」時 44/44 全對；錯全出在候選太窄。下一步是把候選從 3 開到 5 再跑一次（`run-on-machine-b.sh 5`，結果寫 `refine-summary-k5.md`），看那 4 句有沒有進候選、以及開寬會不會多出別的錯；none 那句要在 prompt 裡把「翻譯、效能數字、程式碼」這類明說成 none。在數字回到 0 之前，`dependency.qa.question-refiner` 不該預設開。

**第二部分（帶寬）**：選定 **0.06／0.02**。目前門檻下帶寬裡 9 句、全部 AGREE、誤傷 0、白問 0（本來就沒有可攔的）。替身：「≤1 錯」那組（0.51/0.08/0.80）錯的 1 句在帶寬裡、第二意見說 DISAGREE；9/14 之前那組（0.58/0.04/0.85）錯的 10 句全在帶寬裡，DISAGREE 8、AGREE 1（「有哪些依賴是經過實測確認存在的」，和第一部分錯的是同一句）、FAILED 1（none 那句）。天花板「H 以下全部」也是攔 8，所以 0.06／0.02 用最少的呼叫（9 句對 31 句）拿到一樣的攔截。33 句校準仍 33/33、有把握的沒被碰。
