# 問句修飾（question refiner）— 2026-11-12 老師反饋

> 老師：「問句分數低的部分，可以把我們例句做成 RAG 之類的讓 LLM 根據 RAG 先修飾問句，再跑接下來流程（不一定要是 RAG，看你是否有更好的想法）。」

## 做了什麼

路由沒把握的問句，先用它的向量從例句庫撈例句（這是「把例句做成 RAG」的檢索）：最接近的 5 個意圖各 3 句、**其餘每個意圖 1 句**（11/19 前兩輪只給最近的 3 或 5 個，正確意圖缺席時模型只能選錯，見下方結果），交給模型改寫成貼近例句的一句話，改寫句再走**同一套已校準的路由**；只有改寫句**語言和原句一樣**（11/19 加的守門）、路由有把握、而且意圖與模型自己的標籤一致才採用，否則照舊交給 planner。設計與理由：`docs/report-qa-design.md` §2.4b「路由沒把握時先修飾問句」。程式：`SemanticRouter.shortlist`、`QuestionRefiner`、`prompts/question_refine.txt`、`ReportQaService.respond`。

## 要在機器 B 跑的實驗

```bash
cd ~/ChatOps4Msa-Pipeline && git pull
bash docs/experiments/2026-11-12-question-refiner/run-on-machine-b.sh          # 正式設定：5 個最近意圖 × 3 句＋其餘各 1 句 → refine-summary-k5-all.md
bash docs/experiments/2026-11-12-question-refiner/run-on-machine-b.sh 3 nearest # 11/19 第一輪的設定（對照用）
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

## 結果一（2026-11-19 機器 B 第一輪，`refine-summary-k3-nearest.md`；embedding text-embedding-3-small、chat gpt-4.1-mini、T/M/H 0.51/0.10/0.80、模型只看最近 3 意圖 × 3 例句）

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

**結論**：改寫這一步在「正確意圖有進候選」時 44/44 全對；錯全出在候選太窄。

## 結果二（2026-11-19 機器 B 第二輪，`refine-summary-k5-nearest.md`；模型只看最近 5 意圖 × 3 例句）

| 66 句沒把握的 | planner | 管線（最近 3） | 管線（最近 5） |
|---|---|---|---|
| 對 | 60 | 60 | **62** |
| 錯 | 3 | 5 | 4 |
| none | 3 | 1 | 0 |

有把握 36 → 86（84.3%）；採用 50、採用但錯 **4**；被拒 7（none 6、改寫句路到別的意圖 1）。管線第一次贏 planner（對多 2），但錯仍比 planner 多 1。帶寬仍選 0.06／0.02，校準仍 33/33。

逐句對照第一輪錯的 5 句：

| 句子 | 期望 | 最近 3 | 最近 5 |
|---|---|---|---|
| accounts-db 當機的話，前端哪些功能會受牽連？ | impact-of | 錯（沒進候選） | **對**（impact-of 排第 4） |
| which services open connections to accounts-db or ledger-db? | db-users | 錯（沒進候選） | **拒絕→planner 對**（db-users 排第 5，模型選了但改寫句路到 dependents-of 被守門擋下） |
| 有哪些依賴是經過實測確認存在的？ | observed-edges | 錯 | 錯（observed-edges 排第 6 以後） |
| how does queue-master get its work, via a broker? | async | 錯 | 錯（async 排第 6 以後） |
| can you translate the report into Japanese? | none | 錯（硬選 about-report） | 錯（硬選，而且把英文改成中文「這份報告是怎麼產生的？」） |
| which dependencies are still waiting for a request to cross them? | uncovered | 模型說 none → planner 對 | **新錯**：多了 observed-edges 這個反義鄰居，改成 "which dependencies were confirmed by real traffic?"，否定被翻掉 |

**診斷**：開寬有用（2 句進了候選就對）但有極限（3 句排第 6 以後），而且開寬會多給「反義的鄰居」。正確意圖有進候選的兩輪都全對（44/44、46/46）。缺的守門是「改寫句和原句一致」，不是「改寫句和模型標籤一致」。

## 11/19 的修法（第三輪待跑：`run-on-machine-b.sh` 不帶參數 → `refine-summary-k5-all.md`）

1. **模型看得到全部意圖**：最近 5 個各 3 句例句、其餘每個意圖 1 句（`SemanticRouter.shortlist` 多一個 `restPerIntent`），正確意圖不再缺席；對照跑 `3 nearest`／`5 nearest` 還在。
2. **語言守門**：改寫句的文字系統要和原句一樣（`QuestionRefiner.sameLanguage`，有無漢字），不一樣就拒絕、退回 planner。在前兩輪的 csv 上離線算：第一輪會攔 1 句（對的，退回 planner 也對）、第二輪攔 3 句（翻譯那句＋2 句對的，退回 planner 也對）。
3. **prompt 明說 none**：翻譯／改格式／摘要報告、效能數字、程式碼、授權、設定值、常識都是 none；並明說「反義是另一個種類，不要把 with／without、confirmed／unconfirmed 翻掉」。

## 結果三（2026-11-19 機器 B 第三輪，`refine-summary-k5-all.md`；全部意圖＋語言守門，還沒有意思判定）

| 66 句沒把握的 | planner | 最近 3 | 最近 5 | 全部意圖＋語言守門 |
|---|---|---|---|---|
| 對 | 60 | 60 | 62 | **65** |
| 錯 | 3 | 5 | 4 | **1** |
| none | 3 | 1 | 0 | 0 |

有把握 36 → 83（81.4%）；採用 47、採用但錯 **1**；被拒 10（none 7、換語言 1、路由沒把握 1、路到別的意圖 1）。pick 對 56 錯 1：模型看到全部意圖後標籤本身就準了。帶寬仍 0.06／0.02，校準仍 33/33。

前兩輪錯的 6 句全部解決：accounts-db 當機→impact-of 對、實測確認→observed-edges 對、broker→async 對、still waiting→uncovered 對、ledger-db 那句被「路到別的意圖」擋回 planner（對）、翻成日文模型自己說 none。

**剩的 1 句**：`what is missing from the namespace compared to the code?`（期望 undeployed）→ 模型把 namespace 當成文件，改成 "what is documented but not evidenced in code?"、標 mentioned-only，改寫句路到 mentioned-only 1.00 和標籤一致，守門過了。第一輪它是對的（改成 "what is referenced but missing from the namespace?"）。這一句候選沒缺席、語言沒換，是純粹的意思漂移：只有「改寫句和原句意思一樣嗎」這道門攔得到。

## 11/19 第二次修法（第四輪待跑：`run-on-machine-b.sh` 不帶參數 → `refine-summary-k5-all-judge.md`）

**意思判定**（`QuestionRefiner.sameMeaning`、`prompts/question_same.txt`）：改寫句過了語言、路由、標籤一致三道門之後，再問模型一次「B 問的和 A 是不是完全同一件事」（同種資訊、同服務、同方向、同正反、同範圍），回 `{"same": true|false}`；false 或沒答案都拒絕、退回 planner。只對會被採用的改寫句問，第三輪是 47 句、47 次小呼叫。`-Dqa.refiner.judge=off`／腳本第三個參數 `off` 可關掉對照。

## 結果四（2026-11-19 機器 B 第四輪，`refine-summary-k5-all-judge.md`；加意思判定，第一版 prompt「完全同一件事、同範圍」）

| 66 句沒把握的 | planner | 第三輪（無判定） | 第四輪（判定第一版） |
|---|---|---|---|
| 對 | 60 | 65 | 61 |
| 錯 | 3 | 1 | 2 |
| none | 3 | 0 | 3 |

採用但錯 **0**（目標達到），但判定擋掉 18 句會被採用的改寫句，其中 **17 句是對的**：有把握 83 → 65 句（81.4% → 63.7%），管線對的 65 → 61，退回 planner 之後 2 句錯、3 句空（點線那兩句 planner 本來就錯、about-report 三句 planner 本來就空）。判定把「呼叫 payment／用到 payment」「MongoDB 或 MySQL／資料庫」「pub/sub／佇列」「隔了幾個服務／怎麼走」這類只是講法不同或問同一張清單的一部分，都判成不一樣——是 prompt 寫「EXACTLY the same、same scope」的結果。它確實攔到第三輪那句 namespace，但代價太大。

**判定 prompt 第二版**（第五輪待跑）：標準改成「B 的答案能不能回答 A」——同種資訊、同服務、方向與正反不翻就算一樣；講得比較短、少了修飾（直接／經 HTTP／MongoDB 或 MySQL→資料庫）、問同一張清單的一部分或摘要（幾條、幾趴、第一個或最後一個）都算一樣；換種類、翻方向或正反、換服務才算不一樣，並把 namespace 那句當反例寫進去。**效度要老實寫**：判定的標準是看著這批 hold-out 的錯例改的，要在 hold-out F 上再驗一次。

在採用但錯回到 0、而且有把握比例不明顯掉之前，`dependency.qa.question-refiner` 不該預設開；帶寬 0.06／0.02 和它一起決定。

**第二部分（帶寬）**：選定 **0.06／0.02**。目前門檻下帶寬裡 9 句、全部 AGREE、誤傷 0、白問 0（本來就沒有可攔的）。替身：「≤1 錯」那組（0.51/0.08/0.80）錯的 1 句在帶寬裡、第二意見說 DISAGREE；9/14 之前那組（0.58/0.04/0.85）錯的 10 句全在帶寬裡，DISAGREE 8、AGREE 1（「有哪些依賴是經過實測確認存在的」，和第一部分錯的是同一句）、FAILED 1（none 那句）。天花板「H 以下全部」也是攔 8，所以 0.06／0.02 用最少的呼叫（9 句對 31 句）拿到一樣的攔截。33 句校準仍 33/33、有把握的沒被碰。
