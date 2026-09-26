# 可信的依賴視圖：一個 Pattern Language（AsianPLoP 投稿草案・中文討論版）

> **2026-09-25：投稿主軸已移到 `docs/asianplop-paper-zh.md`**（中文完整全文：摘要、緒論、running example、五個 pattern 完整格式、相關研究、討論與限制、參考文獻、附錄）。
> **本檔保留為決策紀錄**：為什麼從舊目錄的 15 個候選收成 5 個（§4）、外部 known uses 的查證過程與被拿掉的項目（§5）、以及待老師決定的四件事（§6）。
> 五個 pattern 的內容以全文為準；本檔的 pattern 段落不再更新。
>
> **狀態**：討論稿（2026-09-15），取代 `docs/pattern-catalog-asianplop.md` 當作投稿主軸；舊目錄保留當素材庫。
> **為什麼重寫**：舊目錄是從 bug 往回推的 15 個「經驗」，橫跨四個問題、三種抽象層級，pattern 之間沒有生成關係，不是一個 pattern language。這一版只回答**一個**問題，收**同一層級**的 5 個 pattern，照建構順序排，每個 pattern 解完都會產生下一個 pattern 的情境。
> **外部 known uses**：2026-09-15 已上網查證（官方文件與原始論文），每項附網址；判定與注意事項見第 5 節。

---

## 0. 先對齊：什麼是 pattern、什麼是 pattern language

PLoP 系列（含 AsianPLoP）沿用 Christopher Alexander 的定義：

| 概念 | 意思 | 這份文件怎麼滿足 |
|---|---|---|
| **Pattern** | 在某個**情境**下，一個**反覆出現的問題**的解法；解法要平衡互相拉扯的**力量（forces）** | 每個 pattern 都寫 Context／Problem／Forces／Solution／Resulting Context |
| **反覆出現** | 不是單一系統的特殊做法；慣例上要有約三個獨立實例（rule of three） | DepWeaver 之外每個 pattern 都有至少兩個查證過的其他領域實例（第 5 節） |
| **有真的 forces** | 只有一個顯而易見的做法，那是常識不是 pattern | Forces 只列真的互相拉扯的，並寫出「不這樣做會怎樣」 |
| **Pattern language** | 一組 pattern 串起來，解一個更大的設計問題；**套用一個之後，產生下一個要解的情境**（生成性） | 5 個 pattern 依建構順序，每個結尾寫「Resulting Context」銜接下一個 |
| **讀者** | 實務工作者；名字要讓人一聽就知道在解決什麼 | 名字用讀者會遇到的問題命名，不用 DepWeaver 的類別名 |

**AsianPLoP 的實際規定（2026 年第 12 屆徵稿頁，https://plopcon.org/asianplop2026/submissions/）**：

- **論文類型**：Regular paper（≤ 20 頁）、Short paper（≤ 10 頁），兩者都走 shepherding ＋ writers' workshop；另有 Presentation paper 與 focus group 提案。PLoP 系列明確接受 pattern、pattern language、proto-pattern、partial pattern language。
- **格式**：ACM 單欄模板（Word／LaTeX）；2026 收英文與日文（2025 年另收中文）。
- **欄位格式與 known uses 數量：徵稿頁沒有規定。** 社群慣例看 Hillside 的 Pattern Writing Checklist：要描述至少一個實際使用實例，並提供跨實例的通用性證據（https://hillside.net/index.php/pattern-writing-checklist）。「約三個 known uses」是慣例，不是規定。
- **審查流程**：委員會初審 → 分配 shepherd 帶著來回修改 → 最終決定 → writers' workshop 討論（每篇約 1 小時，作者多半聆聽）→ 依討論修改交論文集版；每篇至少一位作者要註冊參加（https://plopcon.org/writers-workshop/）。
- **時程參考**：2026 屆在 3/27–30 於橫濱舉辦，投稿截止 2026-01-05、shepherding 開始 01-13、修改稿 02-21、最終決定 02-28。**2027 屆徵稿尚未公告**；比照往年，截止大約落在 2026 年 12 月至 2027 年 1 月（推測）。
- **收錄**：AsianPLoP 2025 論文集由 Hillside 出版（DOI 10.64346/AsianPLoP2025）；近幾屆是否也收進 ACM DL 查不到確認，投稿前要寄信問主辦單位。

---

## 1. 這個 language 解的問題

### 1.1 一句話

> **由多種可靠度不同的證據合成一張依賴圖與報告時，怎麼讓讀者可以信任圖上與報告裡的每一條關係、每一個數字、每一句說法？**

### 1.2 情境（整個 language 共用）

你在做一個工具，自動產出某個系統的**依賴視圖**：一張圖、一個或幾個指標（例如覆蓋率、完整度），以及一份文字報告。證據來自多個地方，而且可靠度差很多：

- **量測**：執行期遙測。精確，但看不全（沒走到的路徑、非 HTTP 協定、沒部署的元件都看不到）。
- **結構分析**：程式碼、設定檔。看得全，但包含死碼、只宣告沒使用的設定。
- **文字**：專案文件，或 LLM 讀非結構化資料後的整理。最鬆散，還可能有幻覺。

讀者會拿這些視圖做決策：部署順序、改動的影響範圍、還缺哪些測試或流量。所以**看起來合理但其實是錯的，比工具當掉更危險**。

### 1.3 讀者

做「多來源合成視圖」工具的人，不限微服務：

- 架構還原（architecture recovery）與依賴分析工具
- 資料血緣（data lineage）工具
- 服務目錄、基礎設施盤點
- 軟體物料清單（SBOM）與授權分析
- 可觀測性儀表板
- LLM 輔助產生的程式碼或架構報告

### 1.4 貫穿整個 language 的根原則（放引言，不獨立成 pattern）

**確定性優先，LLM 只補語言**：能由程式從證據算出來的，就不交給 LLM 重述；LLM 負責詮釋與把事實講成人話。
下面 5 個 pattern 可以看成這條原則在「依賴視圖」這條建構路徑上的 5 個落點。

### 1.5 Running example（論文用，中性例子）

一個線上商店：`web` 前端呼叫 `orders`、`catalog`、`cart`；`orders` 呼叫 `payment`、`shipping`，並使用 `orders-db`；`shipping` 發訊息到 `queue`。
證據：遙測看到 `web → orders` 有流量；程式碼看到 `orders → payment` 的 HTTP client；文件寫了 `orders → shipping`；設定檔宣告了 `catalog-db` 的連線字串，但程式裡沒有任何持久化程式碼。

後面每個 pattern 都用這個例子說明「沒套用時會怎樣、套用後會怎樣」。DepWeaver 的真實事件放在 Known Uses。

---

## 2. Language 地圖

```mermaid
flowchart LR
    E["證據<br/>量測／程式碼／文字"] --> P1
    P1["① 證據分級的關係<br/>Evidence-Graded Relations"] -->|"多來源、多等級的關係<br/>要合在一個地方"| P2
    P2["② 單一標準模型<br/>One Model, Many Views"] -->|"模型一致了，但 LLM 寫的<br/>報告文字仍會重述事實而漂移"| P3
    P3["③ 事實由程式寫<br/>Code-Authored Facts"] -->|"數字由程式算了，<br/>但分母該算哪些？"| P4
    P4["④ 誠實分母<br/>Honest Denominator"] -->|"有些項目根本沒量測，<br/>該顯示什麼？"| P5
    P5["⑤ 未量測不是零<br/>Unmeasured Is Not Zero"] --> V["讀者可信任的<br/>圖、數字、報告"]
```

| 順序 | Pattern | 解決的問題 | 套用後產生的新問題（下一個 pattern 的情境） |
|---|---|---|---|
| ① | 證據分級的關係 | 所有關係畫成一樣，讀者分不出哪條是真的 | 同一條關係被多個來源、以不同等級提到，要合在哪裡、以誰為準？ |
| ② | 單一標準模型、多個視圖 | 圖、數字、報告各自推導，互相矛盾 | 模型一致了，但報告是 LLM 寫的，它重述事實時會漂移 |
| ③ | 事實由程式寫 | LLM 重述事實會漂移，改 prompt 只是換一種錯 | 數字由程式算了，但分母要算進哪些項目？ |
| ④ | 誠實分母 | 分母混進量不到、證據弱的項目，分數失真 | 排除之後，有些項目或整個指標根本沒量測，要顯示什麼？ |
| ⑤ | 未量測不是零 | 沒量測被當成 0、false，或被 LLM 猜掉 | （結束）讀者看到的每個數字與說法都有依據，或明說沒有 |

**讀法**：可以從頭照順序讀；已經有標準模型的團隊可以從 ③ 開始。

---

## 3. Patterns

### ① 證據分級的關係（Evidence-Graded Relations）

**Context**
你從多個來源收集系統元件之間的關係：遙測、程式碼、設定檔、文件。你準備把它們畫成一張圖給人看。

**Problem**
> 來源的可靠度差很多。全部畫成同一種線，讀者會以為每條都一樣真；只畫最可靠的（量測到的），又會丟掉那些量測不到、但確實存在的依賴。

**Forces**
- **可靠度與完整度方向相反**：遙測 precision 高、recall 低；結構分析 recall 高、precision 中等；文字兩者都低。只取其中一個，一定犧牲另一個。
- **讀者要一眼看懂**：分級太多看不懂，太少又分不出差異。
- **同一條關係會被多個來源重複提到**：不能畫成多條平行線，也不能讓後到的弱來源蓋掉先到的強來源。
- **下游會偷偷升級**：摘要、報告、問答容易把「只被提到」講成「確定存在」。
- **視覺通道有限**：線型、顏色、粗細、標籤要分給證據等級、關係類型、流量大小。

**Solution**
因此：
1. 定義**小而有序**的證據等級，建議三級：**量測到**（observed）＞ **有使用證據**（used，例如真的呼叫的程式碼、持久化程式碼）＞ **只被提到**（mentioned，例如只有設定或文件）。
2. 等級（**有多可信**）與來源（**誰說的**）分開存。一條關係可以有多個來源，但只有一個等級。
3. 以（來源元件, 目標元件）為鍵合併：等級**取最高**、來源與佐證**取聯集**。
4. 視覺上把**最醒目的通道（線型）給證據等級**：實線、虛線、點線。類型用顏色或標籤；流量大小降級成粗細或不畫。
5. 所有下游讀取等級時，**只能照抄或降級，不能升級**。

*Running example*：`web → orders` 實線（量測到）；`orders → payment` 虛線（程式碼有呼叫）；`orders → shipping` 與 `catalog → catalog-db` 點線（只被提到，後者標 `db?`）。

**Consequences**
- ＋ 量測不到的真依賴仍在圖上，但不會被誤認為已確認。
- ＋ 多來源合併只需要「取最高」，不需要特殊邏輯。
- ＋ 下游可以依等級決定要不要計分（→ ④）。
- － 「什麼算使用證據」需要領域知識，每種語言或框架各要一份判準。
- － 強來源誤判時會蓋掉弱來源的正確判斷（取最高沒有降級機制）。
- － 讀者要學會看圖例。

**Resulting Context**
每條關係都有等級與來源了。但同一次分析有好幾個產物（圖、指標、報告），如果各自讀原始證據再各自合併、各自判等級，它們很快就會對同一條關係說出不同的話。→ ②

**Known Uses**
1. **DepWeaver**（微服務依賴分析，本研究）
   - `DependencyGraph` 三級 `observed／documented／inferred`，`addEdge()` 取最高並聯集來源與佐證；`DotEmitter`／`MermaidEmitter` 用實線、虛線、點線，並加 `db?` 與圖例。
   - 事件：指導老師看了只有遙測的圖，說它「比較像流量圖，不像依賴圖」→ 讓程式碼與文件的關係成為一等公民，以證據等級為主要編碼、請求數降級成線寬（2026-07-21）。之後老師提醒「資料庫是否真的有使用要注意」→ 只有連線設定的資料庫邊畫成點線 `db?`（2026-07-23）。
   - 升級實例：Bank of Anthos 部署真實資料庫、補上 in-mesh TCP 查詢後，`userservice → accounts-db` 由虛線升為實線（commit `852de69`）。
2. **Kiali**（Istio 服務圖）：Display 選項「Idle Edges」會把「曾經有流量、但查詢期間沒有流量」的邊也畫出來，預設關閉，閒置的邊以灰色呈現，和有流量的邊區分開（https://kiali.io/docs/faq/graph/）。
3. **情報分析的 Admiralty Code**（NATO AJP-2.1）：來源可靠度 A–F 與資訊可信度 1–6 **分開評級**，正是「等級與來源分開存」的先例（同儕審查論文：https://www.cambridge.org/core/journals/judgment-and-decision-making/article/effect-of-source-reliability-and-information-credibility-on-judgments-of-information-quality-in-intelligence-analysis/E67548E8010A47345C3439D45D9EC6B3 ；論文定稿要改引 AJP-2.1 原文）。
4. **OpenLineage**（資料血緣標準，部分實例）：沒有信心等級，但每個 facet 都強制記錄 `_producer`（誰產生的），column lineage 另分 DIRECT／INDIRECT 等類型，屬於「來源與關係類型分開記錄」（https://github.com/OpenLineage/OpenLineage/blob/main/spec/OpenLineage.md 、https://openlineage.io/docs/spec/facets/dataset-facets/column_lineage_facet/）。

---

### ② 單一標準模型、多個視圖（One Model, Many Views）

**Context**
你已經有分級的關係（①）。一次分析要交付好幾種產物：一張圖、一個或幾個指標、一份文字報告，之後可能還有問答或匯出。這些產物由不同模組、甚至不同作者（程式或 LLM）產生。

**Problem**
> 每個產物各自從原始證據推導時，推導邏輯會分岔，讀者會看到「報告說 A、圖畫 B」，而且無從判斷誰對。

**Forces**
- **每個產物都想要最適合自己的輸入**：LLM 想讀原始文字，繪圖想讀結構，指標想讀清單。
- **推導邏輯重複就會分岔**：修了一處忘了另一處。
- **一個模型要承載所有視圖需要的欄位**（等級、來源、部署狀態、分層），schema 會變胖。
- **有些視圖發生得很晚**：例如報告發出後才有人追問，原始證據可能已經刪除。
- **耦合**：所有視圖綁在同一個 schema 上，改 schema 影響面大。

**Solution**
因此：
1. 每次執行**先建一個標準模型，再產所有視圖**。模型只建一次。
2. 每個視圖都是模型的**純函式**：繪圖、指標、報告的事實段落（→ ③）、查詢。
3. **衍生計算只實作一次**（例如分數、分層、排序），所有視圖呼叫同一個實作。
4. 模型可以**序列化與還原**；晚發生的視圖讀回同一份模型，不從原始證據重推。
5. 需要給 LLM 的事實，也由模型產生文字給它，不讓它讀原始證據自己推。

*Running example*：圖、覆蓋率、報告都讀同一個 graph 物件；`orders → payment` 在三個地方都是「有使用證據、未量測」。

**Consequences**
- ＋ 「報告與圖矛盾」在結構上消失。
- ＋ 之後加新視圖（例如問答）不必重建推導邏輯。
- ＋ 測試集中在模型與衍生計算。
- － schema 成為所有視圖的共同介面，改動要照顧所有消費者。
- － 模型建構失敗時所有視圖一起失敗，需要降級策略。
- － 原始證據裡有、模型沒收的資訊，所有視圖都拿不到（可當成刻意的界線）。

**Resulting Context**
圖、指標、查詢都一致了。但文字報告仍由 LLM 撰寫；即使給了模型的事實，LLM 重述它們時仍會漏、會加、會改措辭。→ ③

**Known Uses**
1. **DepWeaver**
   - `DependencyReportService` 先 `buildGraph()`，同一個物件餵給 Mermaid／DOT 繪圖、`CoverageAnalyzer`、報告第 5 節、問答 archive；問答的 `uncovered` 直接呼叫同一個 `CoverageAnalyzer`，部署順序用同一套分層。
   - 事件：Bank of Anthos 的報告說資料庫邊「runtime observed: unknown」，旁邊貼的圖卻把同一條邊畫成實線（commit `8424538`）→ 報告與圖改為共用同一個模型（commit `9c13354`）。
2. **Pandoc**：readers 把各種輸入格式轉成同一個 Pandoc AST，writers 再從這個 AST 輸出各種格式；所有輸出都來自同一份中間表示（https://pandoc.org/using-the-pandoc-api.html）。
3. **Model–View–Controller**：Trygve Reenskaug 1978–79 年在 Xerox PARC 提出，同一個 model 由多個 view 呈現（https://folk.universitetetioslo.no/trygver/themes/mvc/mvc-index.html）。
4. **Backstage Software Catalog**（可選）：官方描述是「a centralized system that keeps track of ownership and metadata」，各外掛讀同一份 catalog；論文用官方措辭，不寫「single source of truth」（https://backstage.io/docs/features/software-catalog/）。

---

### ③ 事實由程式寫（Code-Authored Facts）

**Context**
你有標準模型（②），報告由 LLM 撰寫，才讀得通順、有解釋。報告裡有一部分是可以從模型直接列出的**事實**（哪個元件依賴哪個資料庫、有沒有量測到、證據等級），另一部分是**詮釋**（角色、風險、建議）。

**Problem**
> LLM 重述事實時會漂移，而且收緊 prompt 通常只是把錯誤換個樣子。怎麼保有 LLM 報告的可讀性，又讓事實不出錯？

**Forces**
- **讀者要一份連貫的文件**，不想看到拼接得很生硬的兩種文字。
- **範本文字很僵硬**：程式產生的段落讀起來像表格。
- **改 prompt 很便宜**：改一次看起來好了，下次換個方式錯。
- **LLM 輸出的段落邊界不可靠**：它可能照樣寫了那一段，或改了標題。
- **事實與詮釋的界線要人判斷**：劃錯的話，LLM 仍會在詮釋段落裡重述事實。

**Solution**
因此：
1. 把報告段落分成**事實型**（有正確答案、模型裡都有）與**詮釋型**。
2. 事實型段落由程式從模型產生，**措辭固定且精確**（例如「連線數，不是請求數」）。
3. 告訴 LLM 跳過事實段落；同時把同樣的事實以「已確認清單」給它，讓詮釋段落不會和事實段落矛盾。
4. 用**結構錨點**把程式段落拼進 LLM 報告：LLM 若自己也寫了該段，**丟掉它的版本**；找不到錨點就附在最後，事實永遠不會因格式意外而遺失。
5. 段落末尾註明「本段由程式從模型產生」。

*Running example*：「基礎設施依賴」一節由程式列出：`orders → orders-db`（量測到，12 次連線）、`catalog → catalog-db`（只被提到：有連線設定、無持久化程式碼）。LLM 在「風險」一節引用它，但不重寫它。

**Consequences**
- ＋ 讀者最可能拿去對照圖的部分，保證與圖一致。
- ＋ 不再反覆修 prompt；事實段落可以單元測試。
- － 報告風格不一致（一段是範本文字）。
- － 拼接依賴標題慣例，LLM 格式大改時要靠「附在最後」的退路。

**Resulting Context**
報告裡的清單與數字都由程式產生了。但「覆蓋率 70%」這種比率，程式要決定**分母**算進哪些項目；分母定錯，程式也會很精確地算出一個誤導的數字。→ ④

**Known Uses**
1. **DepWeaver**
   - `DependencyReportService.infrastructureSection()` 從圖產生報告第 5 節，`spliceInfrastructureSection()` 以下一節標題為錨點拼接並丟掉 LLM 的版本；`dataLayerLedger()` 把量測到的資料庫邊當確認清單給 LLM。
   - 事件：報告與圖矛盾後，**改 prompt 兩次都只是換一種錯法**，第二次甚至列出建置期函式庫與雲端替代方案當依賴（commit `8424538` → `9c13354`）→ 第 5 節改由程式產生，LLM 不碰。
2. **Satyrn（2024，analytics augmented generation）**：先由分析程式從資料算出事實，再交給 LLM 寫報告；報告中正確主張的比例超過 86%，直接讓 GPT-4 Code Interpreter 寫只有 57%。這是本 pattern 最直接的學術實例，也有量化證據（https://arxiv.org/abs/2406.12069）。
3. **SymGen（2023）**（變體）：LLM 不直接寫數值，而是輸出指向資料欄位的符號參照，再由程式代入（https://arxiv.org/abs/2311.09188）。
4. **R Markdown／knitr 的行內程式碼**：散文中用 `` `r ...` `` 嵌入程式運算結果，數字不手打（https://rmarkdown.rstudio.com/lesson-4.html）。
5. **由 OpenAPI 規格產生的參考文件**（Swagger UI、Redoc）：API 參考段落由規格自動產生，與手寫指南並存（https://swagger.io/tools/swagger-ui/ 、https://github.com/Redocly/redoc）。

---

### ④ 誠實分母（Honest Denominator）

**Context**
你用程式從模型算比率型指標（③），例如覆蓋率、完整度，讀者拿它判斷「做得夠不夠」。模型裡混著量測機制不可能量到的項目、證據很弱的項目（①的「只被提到」），以及性質不同的項目。

**Problem**
> 把量不到的項目算進分母，分數會被永久壓低；把弱證據項目算進去，文件多說幾句就會稀釋分數；排除太多又會虛高。讀者只看到一個百分比，分不出是哪一種。

**Forces**
- **全算看起來保守**，卻讓讀者去修一個修不好的缺口。
- **排除會虛高**：證據很薄時分母縮到剩幾項，100% 沒有意義。
- **排除規則需要領域知識**：什麼是控制面、什麼是程序內元件。
- **跨次比較**：分母定義改了，歷史數字就不可比。
- **不同量測混在同一個比率會改變數字的意義**：請求被驅動與連線被觀測是兩件事。

**Solution**
因此：
1. 分母只算同時滿足兩個條件的項目：**量測機制做得到**（兩端都存在、不是刻意排除在量測外、不是程序內部元件），且**證據高於「只被提到」**（①）；已量測到的一律算。
2. 性質不同的項目用**獨立比率**，不混在一起。
3. 分數旁邊**一定揭露**「未計分：N 項，理由」。
4. 被排除的多於計分的，加「此分數建立在很小的基礎上」警告。
5. 同時列出**未覆蓋清單**，讓分數能直接變成下一步行動。
6. 排除規則寫在一處，所有產生這個數字的視圖共用（②）。

*Running example*：業務呼叫覆蓋率的分母是 `web → orders`、`web → catalog`、`web → cart`、`orders → payment`，共 4 條；`orders → shipping` 只被提到，不計分但揭露；`orders → orders-db` 另算「資料層 1/1」。

**Consequences**
- ＋ 分數回答它宣稱回答的問題；文件幻覺不再拉低分數。
- ＋ 「永遠到不了 100%」的假缺口消失；讀者看得到分母組成。
- － 排除規則是清單，要維護，也會有漏網之魚。
- － 多一行揭露文字；規則改變後要說明與舊數字的可比性。

**Resulting Context**
分母只剩量測得到的項目了。但有時**整個指標**根本沒量測（例如沒連上遙測），或某個元件的狀態從來沒查過；程式預設值會把它們變成 0 或 false，LLM 會把空白猜掉。→ ⑤

**Known Uses**
1. **DepWeaver**
   - `CoverageAnalyzer`：只算可驅動的業務同步邊、排除未部署與平台元件、`inferred` 不進分母、資料層獨立計算並排除程序內快取、揭露未計分數量並在排除多於計分時警告。
   - 事件一：spring-petclinic 修好服務發現後，覆蓋率被控制面、偽節點、別名稀釋成**假 45%** → 只算可驅動業務邊後為 **4/4**（2026-07-29）。
   - 事件二：Bank of Anthos 同一叢集、同一段流量，因文件多講了不存在的邊，覆蓋率從 **7/7 變 7/10** → 只被提到的邊不進分母（commit `03ba052`）。
   - 事件三：程序內快取永遠量測不到，把資料層卡在 **5/6** → 程序內元件不進分母（commit `99bb7d0`）。
2. **CONSORT 臨床試驗報告準則**：流程圖每個階段都要寫排除人數與理由（「Excluded (n= )… Excluded from analysis (give reasons)」），清單第 13b 項也要求交代排除與理由。出處 CONSORT 2010 Statement（Schulz, Altman, Moher；https://pmc.ncbi.nlm.nih.gov/articles/PMC2860339/）；論文要一併提到已更新的 CONSORT 2025（BMJ 2025;389:e081123，https://pubmed.ncbi.nlm.nih.gov/40228833/）。
3. **coverage.py**：覆蓋率分母＝可執行敘述數減去被排除的敘述數；被排除的行不進分母，但 HTML 報告以灰色標出並可切換顯示，JSON 報告也列出 `excluded_lines`（https://coverage.readthedocs.io/en/latest/faq.html）。
- **反例（寫在 Consequences 或 Forces 對照用）**：JaCoCo 的過濾器會讓被過濾的程式碼**直接從報告中消失**，文件沒有提到另外列出（https://www.jacoco.org/jacoco/trunk/doc/changes.html）；Istanbul 會把略過的程式碼**當成已覆蓋來計數**，雖然 HTML 以灰色標示並列出略過數量，但分母並不誠實（https://github.com/gotwarlost/istanbul/blob/master/ignoring-code-for-coverage.md）。兩者正好說明「排除但不揭露」與「排除但灌進分子」會怎樣。

---

### ⑤ 未量測不是零（Unmeasured Is Not Zero）

**Context**
你的指標只算量測得到的項目（④）。但工具有多種執行模式（例如有沒有連上叢集或遙測），也有 fail-soft 設計（某個步驟失敗不中斷整條流程）。下游有程式預設值，也有 LLM，兩者都會「填空」。

**Problem**
> 沒收集的資料在程式裡變成 0 或 false，在 LLM 手上變成猜測；失敗的步驟看起來和「什麼都沒找到」一模一樣。讀者把一個從未發生的量測當成結果。

**Forces**
- **二值欄位簡單**：三值（是／否／未知）會擴散到每個讀取點。
- **空白會邀請填補**：空的段落在 LLM 看來像漏寫了。
- **fail-soft 讓系統不中斷**，卻會隱藏失效。
- **讀者想要一個數字**：「未量測」看起來像沒做完。
- **原因要傳到很遠的下游**：執行模式從入口一路要帶到報告與問答。

**Solution**
因此：
1. 狀態欄位用**三值**，預設是「未知」而不是 false；只有證據明確時才設為 false。
2. **執行模式**（哪些證據沒收集）當成中繼資料，隨模型傳到每個視圖。
3. 視圖遇到未知時**寫一句話**：為什麼未知，以及補什麼證據才會知道。
4. **絕不把 0、0/N、0% 這種數值佔位交給 LLM** 代表未量測；直接給它「未量測」的敘述。
5. 步驟失敗時記錄**失敗狀態與原因**，不回傳空結果。
6. 空段落寫成「無，原因是……」，不留白。

*Running example*：只做靜態分析時，覆蓋率顯示「未量測：本次沒有連上遙測」，而不是 0%；`payment` 的部署狀態顯示「未知：沒有查詢叢集」，而不是「未部署」。

**Consequences**
- ＋ 讀者不會把沒發生的量測當成結果。
- ＋ 靜態模式與執行期模式共用同一套產物而不誤導。
- ＋ 失效可以被看見。
- － 每個讀取點都要處理三值；產物多了「未知，因為……」的文字。
- － 只要漏一個地方沒傳模式資訊（例如某個 prompt），幻覺就從那裡回來。

**Resulting Context**
這個 language 的終點：讀者看到的每條關係有等級（①），每個產物說同一件事（②），事實不經 LLM 重述（③），每個數字的分母是誠實的（④），沒量到的明說沒量到（⑤）。

**Known Uses**
1. **DepWeaver**
   - `DependencyGraph.Node.deployed` 用 `Boolean`（null＝未判定），只對服務類型設 false；報告寫「Not determined」；問答在 greenfield 模式下給 LLM「NOT MEASURED」而不是 0%；抽取失敗記在 `EdgeLedger.fail()`。
   - 事件一：Docker image 沒有 git，程式碼抽取在容器裡從未成功，fail-soft 讓它**靜默退化**成只看文件（2026-07-13）。
   - 事件二：靜態模式的報告幻覺出「25 Services observed / 50 Pods Running」（commit `40f7448`）。
   - 事件三：靜態模式的問答把「未量測」講成「覆蓋率 0%」（commit `89402dc`）。
2. **SQL 的 NULL 與三值邏輯**：NULL 表示「未知」，一般比較運算遇到 NULL 結果是 NULL（例如 `7 = NULL` 為 NULL），而不是 false（https://www.postgresql.org/docs/current/functions-comparison.html）。
3. **Kubernetes condition**：Pod／Node condition 的 status 取值為 `True`、`False`、`Unknown`；Node 的 Ready 為 Unknown，代表 node controller 在寬限期內沒收到回報，而不是「沒有就緒」（https://kubernetes.io/docs/concepts/workloads/pods/pod-condition/ 、https://kubernetes.io/docs/reference/node/node-status/）。
4. **Grafana 與 Prometheus**：Grafana alerting 有獨立的 **No Data** 狀態，定義為「查詢成功但沒有任何資料點」，與數值 0 分開處理（https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/）；Prometheus 的 `absent()`／`absent_over_time()` 專門用來偵測「序列不存在」（https://prometheus.io/docs/prometheus/latest/querying/functions/）。

---

## 4. 舊目錄 15 個候選的去處

| 舊候選 | 去處 | 理由 |
|---|---|---|
| P1 確定性骨幹 | **引言的根原則**（§1.4） | 抽象層級是原則，單獨成篇易被評為常識 |
| P2 詞彙表錨定 | ① 或 ② 的 Solution 實作說明（弱來源只能掛到已知元件上） | 是建模時的技巧，層級低於這 5 個 |
| P3 失敗時關閉的擴充點 | ⑤ 的 Related（被丟棄的比對要留痕跡） | 太細，是抽取器實作技巧 |
| P4 證據分級的邊 | **①** | — |
| P5 正規化層 | ② 的 Solution 補充（建模前清掉偽元件與別名） | 技巧層級 |
| P6 單一標準模型 | **②** | — |
| P7 權威段落由程式碼產生 | **③** | — |
| P8 誠實分母 | **④** | — |
| P9 未量測不是零 | **⑤** | — |
| P10–P12 補證迴圈 | **第二篇 language**：「證據不夠時怎麼補」 | 不同問題（時間軸上的補證），與本篇的「怎麼呈現」分開 |
| P13–P15 對產物提問 | **第三篇或碩論章節**：「報告之後的問答」 | 不同問題；P15 known uses 不足 |

---

## 5. 查證結果（2026-09-15）

### 5.1 每個 pattern 的外部實例

| Pattern | 查證過、可寫進論文的外部實例 | 已拿掉或改寫的 |
|---|---|---|
| ① 證據分級的關係 | Kiali Idle Edges、Admiralty Code、OpenLineage（部分實例） | Backstage relation 標來源：**不成立**（來源記在實體的 annotation 上，不在 relation 上），已拿掉 |
| ② 單一標準模型 | Pandoc AST、MVC（Reenskaug）、Backstage catalog（用官方措辭） | Backstage「single source of truth」只見於第三方文章，改用官方措辭 |
| ③ 事實由程式寫 | **Satyrn（有量化結果）**、SymGen、knitr inline code、OpenAPI 產生文件 | — |
| ④ 誠實分母 | CONSORT 2010／2025、coverage.py | JaCoCo、Istanbul 改當**反例** |
| ⑤ 未量測不是零 | SQL NULL、Kubernetes condition Unknown、Grafana No Data、Prometheus `absent()` | — |

每個 pattern 都有 **DepWeaver 以外至少兩個查證過的實例**，符合 Hillside checklist「跨實例的通用性證據」。

**寫論文時要注意：**
- Admiralty Code 目前只有二手與同儕審查論文來源，定稿改引 AJP-2.1 原文。
- Kiali Idle Nodes 的官方原文沒有逐字抓到，只寫 Idle Edges。
- OpenLineage 的 static（design）與 runtime lineage 區分只見於第三方文章，論文不寫。

### 5.2 相關論文與定位

AsianPLoP／PLoP 過往沒有找到「架構還原」或「依賴分析」主題的 pattern 論文，這是這篇的定位空缺。Related work 應該引用主題最接近的幾篇：

| 論文 | 出處 | 關係 |
|---|---|---|
| Patterns for AI-Assisted Document Maintenance and Traceability（Su 等） | AsianPLoP 2026 | AI 產生文件的可追溯性，最接近 ③ |
| Patterns for Log Dissemination in Cloud-Native Environments | AsianPLoP 2025 | 雲原生可觀測性 |
| A tale of two worlds: improving microservices-based systems（Lopes、Yoder、Goldman） | AsianPLoP 2026 | 微服務 |
| Service Mesh Patterns | EuroPLoP 2022 | 遙測來源（Istio） |
| A Prompt Pattern Catalog to Enhance Prompt Engineering with ChatGPT | PLoP 2023 | LLM 相關 pattern |

來源：https://plopcon.org/asianplop2026/program/ 、https://plopcon.org/proceedings/asianplop/2025/ 、https://dl.acm.org/doi/fullHtml/10.1145/3551902.3551962 、https://dl.acm.org/doi/10.5555/3721041.3721046

### 5.3 時程與形式建議

- **目標**：AsianPLoP 2027。徵稿尚未公告，比照往年，截止大約在 2026 年 12 月至 2027 年 1 月（推測，要等公告確認）。
- **形式**：5 個 pattern ＋ running example 預估會超過 10 頁，投 **Regular paper（≤ 20 頁）**。
- **倒推**：老師確認方向後，約 10 月寫英文初稿、11 月研究室內部讀一次、12 月定稿投稿；投稿後進 shepherding 再來回修改。
- 投稿前寄信問主辦單位：論文集是否收進 ACM DL。

---

## 6. 請老師決定

1. **問題定義**（§1.1）與**讀者**（§1.3）是否就這樣定。
2. **5 個 pattern 的順序與生成關係**（§2）是否成立；④ 與 ⑤ 要分開，還是合成一個「誠實的數字」。
3. Running example 用**中性的商店例子**（本文做法），DepWeaver 只放 Known Uses；還是直接用 DepWeaver 當 running example。
4. 補證迴圈（P10–P12）與問答（P13–P15）是否留作後續論文。

---

## 7. 論文結構草案（英文稿用）

1. Introduction：問題、讀者、根原則（確定性優先，LLM 只補語言）
2. Running Example：商店系統與三種證據
3. The Language at a Glance：地圖與建構順序
4. Patterns ①–⑤（每個：Context／Problem／Forces／Solution／Consequences／Resulting Context／Known Uses）
5. Related Work：architecture recovery、data lineage（OpenLineage）、LLM-generated reports（Satyrn、SymGen）、AsianPLoP／EuroPLoP 相近 pattern 論文（§5.2）
6. Conclusion 與後續（補證迴圈、報告問答兩個 language）
