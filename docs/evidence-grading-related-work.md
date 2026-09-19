# 證據分級：文獻佐證

> 回應 2026-09-14 反饋第 1 點：「搜尋『證據分級』相關的論文，要證明這確實是一件重要的、大家在意的事、有用的事」。
> 用途有兩個：碩論的 related work，以及 AsianPLoP pattern 1（證據分級的關係）的 known uses 與 forces 依據。
> **驗證狀態**欄說明每一條是讀過原文、只讀過摘要，還是只看到二手整理。沒讀過的不寫細節數字。

---

## 1. 要證明的三件事

老師的要求可以拆成三個子命題，文獻各自對應不同領域：

| 子命題 | 要回答的問題 | 主要證據來源 |
|---|---|---|
| **重要** | 「證據有等級」不是我發明的概念，是成熟領域的標準做法 | 醫學的 GRADE 與 evidence hierarchy；軟體工程的實證方法（EBSE） |
| **大家在意** | 業界與標準組織真的在自己的格式裡放「這條資訊是怎麼來的、可信到什麼程度」 | CycloneDX 的 component evidence（confidence 0–1）、SLSA 的 build level、in-toto attestation、W3C PROV |
| **有用** | 不分級會出事：單一來源的依賴關係本來就不完整，而讀者（含 LLM）看不出來 | 靜態呼叫圖 recall 的實測、微服務架構回復工具的比較、不確定性視覺化、LLM 引用可驗證性 |

---

## 2. 「重要」：證據分級是成熟領域的標準做法

| 出處 | 說的是什麼 | 對應到 DepWeaver | 驗證狀態 |
|---|---|---|---|
| GRADE Working Group, *Systems for grading the quality of evidence and the strength of recommendations I: critical appraisal of existing approaches*, BMC Health Serv Res 2004（[PMC545647](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC545647/)；II 為 pilot study，[PMC1084246](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC1084246/)） | 醫學界在 2000 年代初期有六套以上互相衝突的證據分級系統，GRADE 的出發點就是「分級本身沒有爭議，爭議的是怎麼分」，並把**證據品質**與**建議強度**分成兩個軸 | 我們也分兩個軸：一條邊的**證據等級**（量測到／有使用證據／只被提到）與它在**覆蓋率分母**裡算不算數，是兩件事 | 摘要 |
| *The hierarchy of evidence: levels and grades of recommendation*（[PMC2981887](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC2981887/)） | 證據階層的通論：等級不是為了排斥低等級證據，而是為了讓讀者知道自己站在哪一階 | 「只被提到」的邊不刪掉，畫成點線並排除在分母外，理由相同 | 摘要 |
| Kitchenham, Dybå, Jørgensen, *Evidence-based Software Engineering*, ICSE 2004（[PDF](https://web-backend.simula.no/sites/default/files/publications/SE.5.Kitchenham.2004.pdf)） | 把實證醫學的方法搬進軟體工程，並指出軟體工程的證據普遍較弱、RCT 稀少，因此**通常接受所有等級的證據，但必須標明等級** | 這正是工具面對的情況：遙測很少、程式碼分析有限、文件常常是唯一來源 | 摘要＋二手整理 |

> 可反駁點（要先準備好）：醫學的分級是對「研究設計」分級，我們是對「一條依賴關係的來源」分級，抽象層級不同。回答方式：兩者共通的是**同一個結論可以由不同可靠度的證據支撐，而使用者必須看得見是哪一種**；GRADE 自己也強調它分的是「對效果估計的信心」，不是資料本身。

---

## 3. 「大家在意」：業界格式已經在存證據與信心值

| 出處 | 說的是什麼 | 對應 | 驗證狀態 |
|---|---|---|---|
| OWASP CycloneDX, *Authoritative Guide to SBOM* — component evidence（[指南](https://cyclonedx.org/guides/sbom/evidence/)、[1.5 JSON 規格](https://cyclonedx.org/docs/1.5/json/)） | 1.5 起，SBOM 的每個元件可以帶 `evidence.identity`：用了哪些**方法**（例如 manifest-analysis）、每個方法各自的 **confidence 0–1**，以及 occurrences（在原始碼的哪些位置） | 幾乎是我們的邊模型：`provenance`（runtime／code／doc）＋ `confidence`（observed／documented／inferred）＋ `evidence`（檔名:行號、Prometheus 指標） | 官方文件摘錄 |
| SLSA v1.x build track（[FAQ](https://slsa.dev/spec/v1.1/faq)、[build provenance](https://slsa.dev/spec/draft/build-provenance)）與 in-toto attestation | 供應鏈用「等級」描述 provenance 的**可信度與完整度**，等級越高要求越嚴；attestation 是有簽章的中繼資料 | 「同一個事實，依產生方式分級」在工業界是既成做法，不是學術玩具 | 官方文件摘錄 |
| W3C PROV-DM（[TR](https://www.w3.org/TR/prov-dm/)） | provenance 的定義就是「用來評估品質、可靠度或可信度的資訊」；FAIR 原則 R1 也要求 rich provenance | 我們把 provenance 從報告文字提升為圖上的一級欄位，有標準可援引 | 官方規格摘錄 |

---

## 4. 「有用」：不分級會出什麼事

### 4.1 單一來源本來就不完整（所以必須合成，合成就必須分級）

| 出處 | 數字 | 用途 | 驗證狀態 |
|---|---|---|---|
| Sui et al., *On the Recall of Static Call Graph Construction in Practice*, ICSE 2020（[ACM](https://dl.acm.org/doi/10.1145/3377811.3380441)） | 靜態呼叫圖相對於實際執行的 **recall 中位數 0.884**；用上最好的動態特性支援可到 **0.935**；主要漏源不是反射，而是 native 方法與 JVM 自行發起的呼叫 | 直接支撐「只靠程式碼分析會漏邊」，也支撐「runtime 是更高一級的證據」 | ACM 摘要 |
| *Total Recall? How Good Are Static Call Graphs Really?*, ISSTA 2024（[PDF](https://www.opal-project.de/articles/TotalRecall@ISSTA24.pdf)） | 同一主題的後續評估（尚未細讀） | 備用引用 | 僅見標題／摘要 |
| *Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications*（[arXiv 2412.08352](https://arxiv.org/abs/2412.08352)，registered report 於 MSR'24 審查） | 13 個工具收集、9 個成功執行；**單一最佳工具 F1 0.86，四個工具合起來 0.91** | 兩件事：①微服務依賴抽取到今天仍非完全正確 ②**合成多個來源會更好**——這就是我們的做法，而合成之後就得回答「以誰為準」 | 摘要（WebFetch） |
| Lutellier et al., *Comparing Software Architecture Recovery Techniques Using Accurate Dependencies*, ICSE 2015（[IEEE](https://ieeexplore.ieee.org/document/7202951/)）與其期刊延伸 | 依賴關係的**正確度**會顯著影響架構回復品質；動態繫結相關的依賴對靜態語言有幫助 | 支撐「邊的品質決定圖的價值」，也支撐把 runtime 觀測放在最高等級 | 摘要 |

### 4.2 讀者端：不確定性要編碼在圖上，而且編碼方式有研究

| 出處 | 說的是什麼 | 對應 | 驗證狀態 |
|---|---|---|---|
| Guo, Huang, Laidlaw, *Representing Uncertainty in Graph Edges: An Evaluation of Paired Visual Variables*, IEEE TVCG 2015（[IEEE](https://ieeexplore.ieee.org/abstract/document/7089294)） | 專門研究「邊的主屬性 ＋ 邊的不確定性」要用哪兩個視覺變數同時呈現：比較 lightness、grain、fuzziness、transparency，並指出彼此的干擾 | 我們用線型（實線／虛線／點線）表示等級、用粗細／標籤表示流量次數，屬於同一個設計問題，可引為依據也可作為未來改良方向 | 摘要 |
| Ghoniem, Fekete, Castagliola, *On the Readability of Graphs Using Node-Link and Matrix-Based Representations*, Information Visualization 4(2), 2005（[SAGE](https://journals.sagepub.com/doi/10.1057/palgrave.ivs.9500092)） | 原文：「when graphs are bigger than twenty vertices, the matrix-based visualization outperforms node-link diagrams on most tasks. **Only path finding is consistently in favor of node-link diagrams**」 | 部分圖 20 節點上限的依據（見 `threshold-design.md` 第 8 節）；而且我們的主要任務正是 path finding，所以節點連結圖仍然是對的形式 | **已讀原文（摘要頁）** |
| *The Impact of Uncertainty Visualization on Trust in Thematic Maps*, CHI 2026（[DOI](https://doi.org/10.1145/3772318.3790743)） | 不確定性視覺化對信任是雙面刃：可能因誠實而提升可信度，也可能被讀成「不可靠」 | 誠實揭露的代價要寫進 pattern 的 forces，不能只講好處 | 僅見摘要 |

### 4.3 LLM 端：沒有依據的敘述會被寫得很像真的

| 出處 | 數字 | 用途 | 驗證狀態 |
|---|---|---|---|
| Liu, Zhang, Liang, *Evaluating Verifiability in Generative Search Engines*, Findings of EMNLP 2023（[ACL](https://aclanthology.org/2023.findings-emnlp.467/)、[arXiv 2304.09848](https://arxiv.org/abs/2304.09848)） | 四個商用生成式搜尋引擎：**平均只有 51.5% 的句子被引用完整支撐，只有 74.5% 的引用真的支撐它所附的句子**；回答「流暢且看起來有資訊量」 | 最直接支撐「事實由程式寫、LLM 只補語言」與證據等級必須出現在 context 裡 | 摘要 |
| *RAG vs. GraphRAG: A Systematic Evaluation*（[arXiv 2502.11371](https://arxiv.org/html/2502.11371v3)）、*In-depth Analysis of Graph-based RAG in a Unified Framework*（[arXiv 2503.04338](https://arxiv.org/pdf/2503.04338)） | 圖結構化檢索在多跳推理題型上勝過純向量 RAG，但在單純段落題型不一定；並指出「保留原文段落很關鍵」 | 支撐我們的混合設計（圖查詢結果 ＋ 報告段落一起進 context），也是純 AI 對照實驗（`threshold-design.md` 第 9 節）的比較基礎 | 摘要 |

---

## 5. 這些文獻在論文裡各放哪裡

| 位置 | 引用 |
|---|---|
| 碩論 related work「為什麼依賴視圖需要證據等級」 | GRADE、EBSE、ICSE 2020 recall、MSR'24 工具比較、Lutellier |
| 碩論 related work「為什麼 LLM 不能負責事實」 | Liu et al. 2023、RAG vs GraphRAG |
| 碩論設計章「為什麼線型這樣畫、為什麼子圖 20 節點」 | Guo et al. 2015、Ghoniem et al. 2005 |
| AsianPLoP pattern 1（證據分級的關係）known uses | CycloneDX component evidence、SLSA build levels、GRADE（跨領域實例） |
| AsianPLoP pattern 1 forces | CHI 2026 不確定性與信任（誠實揭露可能降低信任）、Guo et al.（兩個視覺變數會互相干擾） |

---

## 6. 目前**找不到**的東西（要先講在前面）

1. **沒有找到**直接量測「把依賴關係分級之後，工程師的決策變好」的使用者研究。現有證據是間接的：分級在別的領域是標準做法（第 2、3 節）、不分級的前提（單一來源完整）被實測推翻（第 4.1 節）、不確定性編碼有視覺化研究（第 4.2 節）。
   → 這正好是可以主張的貢獻缺口；要補的話，最小做法是一個小型使用者研究：同一張圖，一組有分級、一組全部同樣畫法，問「哪些關係你敢據以做部署決策」。
2. 微服務領域**沒有找到**現成的「依賴證據等級」標準命名（三級的名字是我們自己定的）。CycloneDX 用的是連續 confidence，不是離散等級；要在論文裡說明為什麼選離散三級（可解釋、可對應線型、沒有自由參數）。
3. 上表標「摘要」的項目，投稿前要讀全文確認數字與上下文，尤其 ICSE 2020 的 0.884／0.935 與 MSR'24 的 0.86／0.91。

---

## 7. 搜尋紀錄（可重跑）

以下查詢在 2026-09-18 以英文執行：`evidence-based software engineering hierarchy of evidence Kitchenham`、`software architecture recovery ground truth static vs dynamic dependencies`、`static call graph recall soundness empirical study`、`uncertainty visualization graph edges confidence encoding`、`CycloneDX component evidence identity confidence`、`SLSA in-toto attestation levels`、`W3C PROV provenance trust`、`evaluating verifiability generative search engines`、`GraphRAG versus vanilla RAG evaluation`、`Ghoniem Fekete Castagliola readability node-link matrix`。中文關鍵詞（證據分級、證據等級）幾乎只會命中醫學文獻，軟體領域要用 provenance／confidence／evidence 才查得到。
