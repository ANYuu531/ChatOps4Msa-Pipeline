# 別名改成中途問人（2026-10-02 反饋第 1 點）

> **回應**：老師說「節點別名的問題可以像補流量那樣在中間問使用者」。
> **做法**：沿用補流量那條「問人」的機制（按鈕 → 表單 → 存進檢查點 → 下一步套用），對象從「流量需要的值」換成「文件或程式碼用的名字到底是圖上哪個服務」。
> **狀態**：已實作、測試 9 個全過（`AliasAskFlowTest`），全套 263/264（唯一失敗是要連 MCP server 的環境測試，與本次無關）。**尚未在真環境跑過**，要機器 B 重編驗證。
> langchain4j 的評估另見 `docs/langchain4j-spike-2026-10-02.md`；結論是這次不接，理由在那份文件最後一段。

---

## 1. 問題：三個「默默丟掉」的點

工具把文件（DeepWiki）和程式碼殘餘邊合進圖的時候，名字對不上就會被丟掉，而且不留痕跡。查出來有三處：

| 位置 | 情況 | 原本的行為 |
|---|---|---|
| `DocGraphMerger.resolveNode` 第 4 步 | 文件提到一個服務名，所有對齊規則（kebab、去 `-client`、模組目錄後綴）都對不上 | 回傳 null，邊消失 |
| `CodeGraphMerger.matchNodeLoose` | 短名字同時符合多個節點 | 回傳 null，進殘餘清單交給 LLM |
| `DependencyReportService.resolveResidueWithLlm` | LLM 也對不上（或 prompt 要它 DROP） | 列丟掉，沒有紀錄 |
| `GraphNormalizer` 函式庫名單（10/4 補） | 節點名在函式庫名單裡，但程式碼／部署層在它身上畫了邊（Compose 的 `hystrix` dashboard） | 整個節點連邊刪掉，沒有紀錄 |

兩個「自動」的選項都是錯的：**丟掉**等於把文件主張的依賴藏起來；**模糊比對**等於憑相似度發明一條邊。跟補流量的 Tier 3 一樣，這是只有人知道答案的事。

## 2. 流程

```
get-dependency-analysis
  … 收集、合併、完整性檢查 …
  toolkit-depstate-apply-button      （ServiceEntry，原本就有）
  toolkit-depstate-alias-button  ★新  → 建一次合併圖，收集對不上的名字
                                         有 → 存 pending_aliases、貼訊息＋「Resolve names」按鈕
                                         無 → 什麼都不貼（乾淨的 run 不變）
  toolkit-depstate-ask-button        （補流量的值，原本就有）
  toolkit-depstate-checkpoint        （Generate report / Pause）

使用者按「Resolve names」 → Discord modal，每個名字一格（最多 5 格）
  placeholder 顯示：1 customers-service / 2 customer-portal / new / ignore
使用者送出 → ModalListener.onAliasAnswers
  確定性解析（不經 LLM）：候選編號、服務 id 任何拼法、new、ignore；看不懂 → 留著、回報
  存 alias_answers（本次 run）＋ dep-state/aliases/<repo>.json（專案層級）
  沒有重跑：答案在按 Generate report 建圖時套用

Generate report → buildGraph(state)
  程式碼殘餘邊：先套使用者答案（applyAliasAnswers）再給 LLM；LLM 也放不下的 → 記成問題
  文件邊：DocGraphMerger 第 0 步先查答案（對應 / new 建節點 / ignore 丟掉）
  報告多一節「Names resolved by the operator」，由程式從答案產生、LLM 不碰

下一次分析同一個 repo → start() 時從 aliases/<repo>.json 載入答案，只問新名字
```

## 3. 設計決定

- **問的時機**：和補流量的值一樣放在收集結束、bot 閒置時，而且**排在值之前**（名字影響圖，值影響流量）。greenfield 也問，因為純靜態的 run 只有名字可依靠；這一步**不在** `CLUSTER_TOOLKITS` 名單裡。
- **候選怎麼排**：`AliasResolution.rankCandidates` 三個訊號相加（去掉 service／api／client 這類填充字之後的包含關係、共用字詞含字首比對、編輯距離），只用來**排序給人看**，不做任何決定。
- **答案的解析是確定性的**：`parseAnswer` 只認四種形式；看不懂就留著不動，並明說「這個我沒讀懂」。寧可多問一次，不可把讀錯的答案合進圖。
- **答案的效力**：使用者的話高於所有規則（DocGraphMerger 第 0 步），但**不能無中生有**——答一個這次圖上沒有的節點 id，就當沒答（避免舊答案在詞彙變了之後憑空造節點）。
- **不重跑**：和補流量不同，名字不需要再打一輪流量，所以答完只存起來，等 Generate report 建圖時套用。resume 流程也會再貼一次按鈕，給上一輪留白的名字。
- **專案層級記憶**：答案是「關於這個專案的事實」，不是「關於這次 run 的事實」，所以存在 TTL 之外的 `dep-state/aliases/<repo>.json`，`start()` 時載入。
- **報告揭露**：哪幾條邊是靠人的話畫上去的，讀者要看得到。那一節由程式產生（pattern ③）。
- **殘餘 prompt 多一個回傳欄位** `target_raw`：讓 LLM 對上的列和丟掉的列分得開，丟掉的才變成問題。舊模型不回這個欄位時退回用 target 比對。

## 4. 碰到的檔案

| 檔案 | 改動 |
|---|---|
| `Graph/AliasResolution.java` ★新 | Question／Questions／Answers、候選排序、答案解析、JSON |
| `Graph/DocGraphMerger.java` | `merge(graph, notes, answers, questions)` 多載；第 0 步查答案；第 4 步記問題而不是默默丟 |
| `DependencyReportService.java` | `aliasQuestions(state)`；`buildGraph` 先套答案、LLM 剩下的記問題；報告加「Names resolved by the operator」 |
| `DependencyAnalysisStateStore.java` | `pending_aliases`／`alias_answers` 兩個 stage；`loadProjectAliases`／`saveProjectAliases`；`start()` 載入 |
| `DepstateToolkit.java` | `toolkitDepstateAliasButton()`；按鈕與 modal id 常數 |
| `ButtonListener.java` | 開「Which service is each name?」表單 |
| `ModalListener.java` | `onAliasAnswers`：解析、存檔、回報 |
| `dependency.yml`、`toolkit_verify.yml` | 兩條流程各加一步 `toolkit-depstate-alias-button` |
| `prompts/dependency_graph_residue.txt` | 輸出多 `target_raw` |
| `AliasAskFlowTest.java` ★新 | 9 個測試：排序、解析、文件層問與答、程式殘餘邊問與答、JSON 來回、專案記憶、報告段落 |

## 5. 離線驗證：用上週簡報的那個專案（2026-10-04，已做）

上週簡報第 7 頁說 spring-cloud-microservice 漏 2 條（`hystrix → discovery`、`hystrix → gateway`）是「工具的剩餘邊界、不修」。老師因此建議「別名在中間問使用者」。所以驗證就用**同一個專案、同一個 commit**（`6938297`，2017-03-23）。

**根因比上週講的更單純**：Compose 檔裡 `hystrix` 是 Hystrix **dashboard** 這個服務（`image: zpng/cloud-hystrix-dashboard`，links gateway／discovery）。合併時工具有畫出它（16 節點／31 邊），但 `GraphNormalizer` 的函式庫名單裡有 `hystrix`，整個節點被當函式庫刪掉（15／29）。「`hystrix` 是函式庫標籤還是真服務」——規則分不出來，人一眼就知道。所以第 1 節的三個丟掉點之外，**加了第四個問人點**：函式庫名單命中、但程式碼／部署層在它身上畫了邊的節點，照舊刪掉但記成問題；答 `new` 保留、答服務 id 併過去、答 `ignore` 刪掉。文件層提到的函式庫名、沒有邊的函式庫名（Bank of Anthos 的 `lettuce`）照舊靜默刪除。

探針（`GreenfieldProbeTest`）現在會印「alias questions」一節，並接受 `-Dprobe.aliases=<答案檔>`，就是「Resolve names」按鈕的離線形式：

```
S=<暫存目錄>; git clone https://github.com/zpng/spring-cloud-microservice-examples.git $S/spring-cloud-microservice
(cd $S/spring-cloud-microservice && git checkout 6938297335e924f8066f5558b79ee82fa204c4ee)

# 第一輪：沒有答案 → 看問題
mvn -o test -Dtest=GreenfieldProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.repo=$S/spring-cloud-microservice -Dprobe.out=$S/r1/spring-cloud-microservice.mmd
# 第二輪：帶答案
mvn -o test -Dtest=GreenfieldProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.repo=$S/spring-cloud-microservice -Dprobe.out=$S/r2/spring-cloud-microservice.mmd \
  -Dprobe.aliases=docs/generalization/external/aliases/spring-cloud-microservice.answers.json
# 對照資料集（單一專案版的計分）
python3 docs/generalization/external/aliases/agreement_one.py $S/r2/spring-cloud-microservice.summary.md \
  src/test/resources/graphs/microdepgraph/spring-cloud-microservice.graphml
```

**誰在回答，要先講清楚**：這一輪的「操作者」是我（作者）。問題是程式產生的、答案是我手寫進 `answers.json` 的，而且我寫答案時**看過資料集的期望邊**。所以這個驗證證明的是**機制走得通**（問題會被提出 → 答案會被套用 → 邊會出現、不會多出別的東西），**不證明**「問人會得到正確答案」——那要由沒看過資料集的人在 Discord 上答（第 6 節），而且最好不是我。這和上次「標準答案是我給的」是同一類問題，不能混過去。

| 輪 | 工具問了什麼 | 我（作者）答 | 圖 | 對照資料集 |
|---|---|---|---|---|
| 1 | ① `hystrix`：函式庫名，但部署層在它身上畫了 2 條邊（hystrix → gateway、hystrix → discovery）<br>② `cloud-hystrix-dashboard`：模組目錄，圖上沒有這個名字的服務<br>③ `cloud-eureka-server`：同上（候選給了 cloud-config-server 等，不準） | （無） | 15 節點／29 邊 | **24／26＝0.92**，缺的正是那兩條 |
| 2 | 沒有問題了 | ① `new` ② `hystrix` ③ `discovery` | **16 節點／31 邊** | **26／26＝1.00** |

- 答案檔：`docs/generalization/external/aliases/spring-cloud-microservice.answers.json`；第二輪的完整輸出：同目錄 `*.after-answers.summary.md`。
- 既有七個專案的對照（`ExternalTruthAgreementTest`，讀的是 `docs/generalization/external/*.summary.md`，**沒有換成帶答案的版本**）逐項不變；整體 91／95 的數字維持原樣，因為那是「不問人」的結果，要跟「問人之後」分開講。
- 誠實的界線：第三題的候選排序不準（`cloud-eureka-server` 應該是 `discovery`，排序靠詞彙相似度猜不到），操作者要自己打名字；候選只是提示，從來不自動決定。第二、三題答了之後那兩條 config 邊**仍然沒畫**，因為目標是 eureka URL，離線沒有 LLM 對目標——只有來源被改成真名後交給 LLM 的殘餘清單（線上才會接著解）。

## 6. 真環境要驗的事（機器 B）

1. 重編（`--build`），先 grep `Started ChatOps4MsaApplication`。
2. 跑 Bank of Anthos 或 petclinic 的 `get-dependency-analysis`；看收集結束後有沒有多一則「N name(s) … do not match」訊息。
   - 沒有也正常：代表這個專案的名字全對上了。要逼出問題，可以在 DeepWiki 的 notes 裡故意用一個不存在的名字，或拿 TeaStore（compose 用 `persistence`、`db` 這種短名）。
3. 按 Resolve names → 填編號 → 看回覆是否正確回顯；再按 Generate report，確認報告最後有「Names resolved by the operator」且圖上那條邊出現。
4. 再跑一次同一個 repo，確認**不再問**同一個名字（`dep-state/aliases/<repo>.json` 存在）。
5. 故意填一個看不懂的答案（例如 `maybe`），確認它被回報為「not understood」而且圖上沒有多出東西。

## 7. 和 pattern language 的關係

這是論文 §7 說的第二個 language（補證）的「最後才問人」那一條的第二個實例：第一個是補流量時問值，這個是合圖時問名字。共同形態：**自動能做的都做完，剩下的用確定性的表單問人，答案存起來、揭露在報告裡、下次不再問**。
