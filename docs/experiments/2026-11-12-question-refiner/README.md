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

## 結果

待機器 B 跑；本機沒有額度，不填數字。
