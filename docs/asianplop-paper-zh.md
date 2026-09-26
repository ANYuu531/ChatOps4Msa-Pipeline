# 可信的依賴視圖：一個 Pattern Language

**Trustworthy Dependency Views: A Pattern Language**

> **這是什麼**：AsianPLoP 投稿用的**中文完整全文**（2026-09-25）。回應反饋第 3 點「這次要開始寫 AsianPLoP pattern 的部分了」。
> 討論稿 `docs/pattern-language-asianplop.md` 保留為**決策紀錄**（為什麼從 15 個候選收成 5 個、查證過程、待老師決定的四件事）；本文件是要拿去翻成英文投稿的那一份。
> **狀態**：五個 pattern 完整、相關研究完整、Known Uses 都有查證過的外部實例（附網址）。仍待處理的寫在最後一節「投稿前待辦」。

---

## 摘要

自動產生的依賴視圖——一張服務關係圖、一兩個覆蓋率數字、一份文字報告——正在被用來做決策：部署順序怎麼排、改一個服務會影響誰、還缺哪些測試。這些視圖的證據來自可靠度差很多的來源：執行期遙測精確但看不全，程式碼與設定檔看得全但包含死碼與只宣告未使用的組態，專案文件與大型語言模型（LLM）的整理最鬆散且可能有幻覺。當這些來源被合成到同一張圖、同一個百分比、同一段敘述裡，**看起來合理但其實是錯的，比工具當掉更危險**：讀者沒有辦法分辨哪一條關係是量到的、哪一個數字的分母算了什麼、哪一句話有依據。

本文提出五個 pattern 組成的 pattern language，回答一個問題：**由多種可靠度不同的證據合成依賴視圖時，怎麼讓讀者可以信任圖上與報告裡的每一條關係、每一個數字、每一句說法？** 五個 pattern 依建構順序排列——證據分級的關係、單一標準模型多個視圖、事實由程式寫、誠實分母、未量測不是零——每一個套用之後都會產生下一個要解的情境。Pattern 來自一個微服務依賴分析與 ChatOps 工具（DepWeaver）兩年的開發與真實叢集驗證，每一個 pattern 的 Known Uses 除了這個工具，都附上至少兩個其他領域已查證的實例：情報分析的來源可靠度分級、SBOM 的元件證據信心值、臨床試驗報告準則的排除揭露、SQL 的三值邏輯、Kubernetes 的 `Unknown` 狀態等。

**關鍵詞**：pattern language、依賴分析、架構回復、證據分級、provenance、LLM 產生的報告、可信度

---

## 1. 緒論

### 1.1 情境

假設你在做一個工具，它自動產出某個系統的**依賴視圖**。輸入是這個系統本身：原始碼倉庫、部署描述、一個跑著的叢集、專案文件。輸出通常有三種：

- **一張圖**：元件與元件之間的關係。
- **一兩個指標**：例如「依賴覆蓋率 7/7」、「資料層 5/5」。
- **一份文字報告**：角色、風險、下一步建議；今天多半由 LLM 撰寫，因為只有它寫得出讀者願意讀的散文。

證據來自三類來源，可靠度差很多：

| 來源 | 例子 | 強項 | 弱點 |
|---|---|---|---|
| **量測** | 服務網格的遙測、追蹤、連線計數 | 精確：看到的就是真的發生過 | 看不全：沒走到的路徑、非 HTTP 協定、沒部署的元件都看不到 |
| **結構分析** | 程式碼的呼叫點、設定檔的位址、部署描述的佈線 | 看得全：不必等流量 | 包含死碼、只宣告沒使用的組態、動態分派看不到 |
| **文字** | README、架構文件、LLM 讀非結構化資料後的整理 | 補得到前兩者都看不到的東西 | 最鬆散，而且可能是幻覺 |

「單一來源就夠」這個前提在實務上已經被推翻：靜態呼叫圖相對於實際執行的 recall 中位數約 0.884（Sui et al., ICSE 2020）；微服務架構回復工具的比較研究裡，單一最佳工具 F1 為 0.86，而**四個工具合起來是 0.91**（Schneider et al., EMSE 2025）。所以合成是必要的——而合成之後就必須回答一個新問題：**同一條關係被兩個可靠度不同的來源提到時，讀者看到的是什麼？**

### 1.2 問題

> **由多種可靠度不同的證據合成一張依賴圖、幾個指標與一份報告時，怎麼讓讀者可以信任其中的每一條關係、每一個數字、每一句說法？**

這個問題不是「怎麼讓抽取更準」。抽取永遠不會完全準，這是被量過的。問題是：**在抽取不完美的前提下，怎麼讓視圖不騙人。**

三個具體的失效形態貫穿本文：

1. **等級被抹平**：文件裡提過一句的關係和遙測量到 5 191 次連線的關係，在圖上長得一模一樣。
2. **產物互相矛盾**：報告寫「執行期觀測：未知」，旁邊附的圖把同一條關係畫成實線。
3. **空白被填滿**：某個步驟根本沒執行，程式的預設值把它變成 `0%`，LLM 把它寫成「目前沒有任何依賴」。

### 1.3 為什麼現在值得寫成 pattern

兩件事讓這個問題從「工程細節」變成「設計問題」：

- **LLM 進入了產出鏈。** 生成式搜尋引擎的評估顯示，平均只有 51.5% 的句子被它的引用完整支撐（Liu et al., EMNLP Findings 2023）；更近期針對研究型 agent 的評估發現，連結有效率超過 94%、相關性超過 80%，但事實正確率只有 39–77%，而且**工具呼叫次數越多、查核正確率掉得越多**（Onweller et al., 2026）。也就是說：「看起來有引用」與「引用真的支撐」是兩件事，而且投入越深不一定越好。
- **這類視圖開始被拿來做自動化決策**（產生部署順序、決定要不要跑某組流量）。錯誤不再只是誤導人，而會被下游程式當成輸入。

### 1.4 讀者

做「多來源合成視圖」的工具的人，不限微服務：

- 架構回復（architecture recovery）與依賴分析工具
- 資料血緣（data lineage）工具
- 服務目錄、基礎設施盤點
- 軟體物料清單（SBOM）與授權分析
- 可觀測性儀表板
- 任何由 LLM 產生、但內容必須正確的報告

### 1.5 貫穿整個 language 的根原則

> **確定性優先，LLM 只補語言。** 能由程式從證據算出來的，就不交給 LLM 重述；LLM 負責詮釋、組織與把事實講成人話。

這條原則本身不是 pattern——它只有一個顯而易見的做法，沒有真正互相拉扯的力量，寫成 pattern 會被評為常識。下面五個 pattern 可以看成這條原則在「依賴視圖」這條建構路徑上的五個落點。

### 1.6 Pattern 的格式與證據

每個 pattern 寫 **Context／Problem／Forces／Solution／Resulting Context／Consequences／Known Uses／Related Patterns**。PLoP 系列沿用 Christopher Alexander 的定義：pattern 是「某個情境下，一個反覆出現的問題的解法，解法平衡互相拉扯的力量」。因此本文特別注意兩件事：

- **Forces 只列真的互相拉扯的**，並且每一個都寫「不這樣做會怎樣」。沒有張力的條目放到 Solution 的實作說明裡。
- **Known Uses 不只列自家工具。** 每個 pattern 都有 DepWeaver 以外**至少兩個查證過的外部實例**（附網址），符合 Hillside 的 Pattern Writing Checklist 對「跨實例通用性證據」的要求。DepWeaver 的部分會附上**具體事件**（哪一次真實叢集的驗證、哪一個 commit），因為 pattern 的力量來自它解掉的真實失敗。

---

## 2. Running Example

全文用同一個線上商店當例子。它刻意選成中性的、與任何特定工具無關的系統：

```
web ──→ orders ──→ payment
 │        │
 │        ├──→ shipping ──→ queue
 │        └──→ orders-db
 ├──→ catalog ──→ catalog-db
 └──→ cart
```

證據是這樣分布的——注意每一條的來源不同：

| 關係 | 誰說的 | 可靠度 |
|---|---|---|
| `web → orders` | 遙測：這段期間有 95 次請求 | 量測到 |
| `orders → payment` | 程式碼：`OrderService.java:42` 的 HTTP client | 有使用證據，但沒量到 |
| `orders → shipping` | 只有架構文件寫了一句 | 只被提到 |
| `orders → orders-db` | 設定檔有連線字串，**而且**程式裡有持久化程式碼 | 有使用證據 |
| `catalog → catalog-db` | 設定檔有連線字串，**但**程式裡找不到任何持久化程式碼 | 只被提到 |

`cart` 在圖上，但這次分析沒有查詢叢集，所以**沒有人知道它有沒有部署**。

每個 pattern 都用這個例子說明「沒套用時會怎樣、套用後會怎樣」。

---

## 3. 這個 Language 的全貌

```mermaid
flowchart LR
    E["證據<br/>量測／結構分析／文字"] --> P1
    P1["① 證據分級的關係"] -->|"多來源、多等級的關係<br/>要合在一個地方"| P2
    P2["② 單一標準模型、多個視圖"] -->|"模型一致了，但 LLM 寫的<br/>報告仍會重述事實而漂移"| P3
    P3["③ 事實由程式寫"] -->|"數字由程式算了，<br/>但分母該算哪些？"| P4
    P4["④ 誠實分母"] -->|"有些項目根本沒量測，<br/>該顯示什麼？"| P5
    P5["⑤ 未量測不是零"] --> V["讀者可信任的<br/>圖、數字、報告"]
```

| 順序 | Pattern | 解決的問題 | 套用後產生的新問題（下一個 pattern 的情境） |
|---|---|---|---|
| ① | **證據分級的關係**<br/>Evidence-Graded Relations | 所有關係畫成一樣，讀者分不出哪條是真的 | 同一條關係被多個來源以不同等級提到，要合在哪裡、以誰為準？ |
| ② | **單一標準模型、多個視圖**<br/>One Model, Many Views | 圖、數字、報告各自推導，互相矛盾 | 模型一致了，但報告是 LLM 寫的，它重述事實時會漂移 |
| ③ | **事實由程式寫**<br/>Code-Authored Facts | LLM 重述事實會漂移，改 prompt 只是換一種錯 | 數字由程式算了，但比率的分母要算進哪些項目？ |
| ④ | **誠實分母**<br/>Honest Denominator | 分母混進量不到、證據弱的項目，分數失真 | 排除之後，有些項目或整個指標根本沒量測，要顯示什麼？ |
| ⑤ | **未量測不是零**<br/>Unmeasured Is Not Zero | 沒量測被當成 0、false，或被 LLM 猜掉 | （終點）讀者看到的每個數字與說法都有依據，或明說沒有 |

**這是一個 language 而不是一份 catalog**，因為相鄰的 pattern 之間有生成關係：分級之後才會出現「多個產物怎麼保持一致」；一致之後才會出現「LLM 重述事實」；事實由程式寫之後才會出現「分母算什麼」；分母收斂之後才會出現「整個指標沒量測時顯示什麼」。

**讀法**：可以從頭照順序讀。已經有標準模型的團隊可以從 ③ 開始；只做靜態分析、還沒接遙測的團隊會發現 ⑤ 是最先痛的（因為所有量測欄位都是空的）。

---

## 4. Patterns

### ① 證據分級的關係（Evidence-Graded Relations）

**Context**

你從多個來源收集系統元件之間的關係：執行期遙測、程式碼、設定檔與部署描述、專案文件（可能經由 LLM 整理）。你準備把它們畫成一張圖給人看。

**Problem**

> 來源的可靠度差很多。全部畫成同一種線，讀者會以為每條都一樣真；只畫最可靠的（量測到的），又會丟掉那些量測不到、但確實存在的依賴。

**Forces**

- **可靠度與完整度方向相反。** 遙測 precision 高、recall 低；結構分析 recall 高、precision 中等；文字兩者都低。只取其中一個，一定犧牲另一個。
- **讀者要一眼看懂。** 分級太多看不懂；只有兩級（真／假）又把「有程式碼證據但這次沒跑到」和「文件隨口提過」混為一談。
- **同一條關係會被多個來源重複提到。** 不能畫成多條平行線，也不能讓後到的弱來源蓋掉先到的強來源。
- **下游會偷偷升級。** 摘要、報告、問答都很容易把「只被提到」講成「確定存在」——尤其當下游是 LLM。
- **視覺通道有限。** 線型、顏色、粗細、標籤要分給證據等級、關係類型（同步呼叫／資料庫／佇列／外部）、流量大小。研究指出同時編碼「主屬性」與「不確定性」的視覺變數會互相干擾（Guo et al., IEEE TVCG 2015），所以通道的分配是有代價的選擇，不是想加就加。
- **誠實揭露不確定性可能反過來降低信任。** 主題地圖的研究顯示不確定性視覺化是雙面刃：可能因為誠實而提升可信度，也可能被讀成「這份東西不可靠」（CHI 2026）。所以分級要能被讀懂，而不只是把懷疑丟給讀者。

**Solution**

因此：

1. 定義**小而有序**的證據等級。建議三級：
   - **量測到**（observed）：執行期直接看到這個關係發生。
   - **有使用證據**（used／documented）：有真的會發出呼叫的程式碼、有持久化程式碼搭配連線設定。
   - **只被提到**（mentioned／inferred）：只有設定值、文件敘述，或由名字推測。
2. **等級（有多可信）與來源（誰說的）分開存。** 一條關係可以有多個來源，但只有一個等級。
3. 以（來源元件, 目標元件）為鍵**合併**：等級**取最高**，來源與佐證**取聯集**。
4. 視覺上把**最醒目的通道給證據等級**：實線／虛線／點線。關係類型用顏色或短標籤，流量大小降級成線寬或不畫。
5. 所有下游讀取等級時，**只能照抄或降級，不能升級**。

*Running example*：`web → orders` 實線（量測到，95 次）；`orders → payment` 虛線（程式碼有呼叫，這次沒量到）；`orders → shipping` 與 `catalog → catalog-db` 點線（只被提到，後者標 `db?` 提示「有連線設定、沒有持久化程式碼」）。

**實作要點**

- 三級是離散的而不是 0–1 的連續信心值。離散的好處是**沒有自由參數要校準**、可以直接對應三種線型、而且「取最高」這個合併規則不需要加權公式。代價是無法表達「兩個弱來源互相佐證」——這一點要寫進 Consequences。
- 「什麼算使用證據」是領域知識：每種語言與框架各要一份判準（例如資料庫要同時看到連線設定**與**持久化標記）。這份判準會成為工具最需要維護的部分。

**Resulting Context**

每條關係都有等級與來源了。但同一次分析有好幾個產物（圖、指標、報告、之後的問答），如果各自讀原始證據、各自合併、各自判等級，它們很快就會對同一條關係說出不同的話。→ ②

**Consequences**

- ＋ 量測不到的真依賴仍然在圖上，但不會被誤認為已確認。
- ＋ 多來源合併只需要「取最高」，不需要特殊邏輯，也不需要調參數。
- ＋ 下游可以依等級決定要不要計分（→ ④）。
- ＋ 同一條關係的等級**上升**本身就是有用的訊號：它代表「這次的流量終於走到這裡了」。
- － 「什麼算使用證據」需要領域知識，每種語言或框架各要一份判準。
- － 強來源誤判時會蓋掉弱來源的正確判斷（取最高沒有降級機制）。
- － 讀者要學會看圖例；不確定性的呈現也可能被讀成「這份圖不可靠」。
- － 三級無法表達「多個弱來源互相佐證」。

**Known Uses**

1. **DepWeaver**（微服務依賴分析與 ChatOps 工具，本研究）
   - `DependencyGraph` 用三級 `observed／documented／inferred`；`addEdge()` 以（source, target）為鍵取最高等級並聯集來源與佐證；`DotEmitter`／`MermaidEmitter` 以實線／虛線／點線繪製，並加 `db?` 標籤與圖例。
   - **事件（等級被抹平）**：指導老師看了只有遙測的圖，說它「比較像流量圖，不像依賴圖」。於是程式碼與文件的關係成為一等公民，證據等級成為主要編碼，請求數降級成線寬（2026-07-21）。之後又被提醒「資料庫是否真的有使用要注意」，於是只有連線設定、沒有持久化程式碼的資料庫邊畫成點線 `db?`（2026-07-23）。
   - **事件（等級上升）**：Bank of Anthos 部署真實資料庫並補上服務網格內的 TCP 查詢後，`userservice → accounts-db` 由虛線升為實線；同一條 `transactionhistory → ledger-db` 在三次實驗中分別是「不存在」→「虛線、誠實說沒量到」→「實線、5 191 次連線」，而三次的回答品質完全跟著等級走。
2. **Kiali**（Istio 的服務圖）：Display 選項 *Idle Edges* 會把「曾經有流量、但查詢期間沒有流量」的邊也畫出來，預設關閉，閒置的邊以灰色呈現，與有流量的邊區分（<https://kiali.io/docs/faq/graph/>）。這是「不同證據狀態用不同視覺呈現、而且可以選擇要不要看」的實例。
3. **情報分析的 Admiralty Code**（NATO AJP-2.1）：**來源可靠度 A–F** 與**資訊可信度 1–6** 分開評級——正是「等級與來源分開存」的先例，而且是這個做法最古老的實例（同儕審查的討論見 *Judgment and Decision Making* 上關於來源可靠度與資訊可信度對情報品質判斷的研究）。
4. **OWASP CycloneDX 的 component evidence**（部分實例）：SBOM 的每個元件可以帶 `evidence.identity`，記錄用了哪些**方法**（例如 manifest-analysis）、每個方法各自的 **confidence 0–1**，以及在原始碼的哪些位置（occurrences）（<https://cyclonedx.org/guides/sbom/evidence/>、現行 1.6 規格 <https://cyclonedx.org/docs/1.6/json/>）。它證明業界標準已經在做「同一個事實依產生方式標可信度」；差別是它用連續信心值，我們用離散三級。
5. **SLSA build track 與 in-toto attestation**：供應鏈用**等級**描述 provenance 的可信度與完整度，等級越高要求越嚴（<https://slsa.dev/spec/v1.1/faq>）。同一個事實依產生方式分級，在工業界是既成做法。
6. **W3C PROV-DM**：provenance 的定義本身就是「用來評估品質、可靠度或可信度的資訊」（<https://www.w3.org/TR/prov-dm/>）。我們把 provenance 從報告文字提升為圖上的一級欄位，有標準可以援引。

**Related Patterns**

往下接 ② 單一標準模型。與 ④ 誠實分母直接相關：等級是分母的過濾條件。與 ⑤ 未量測不是零互補：等級講的是「這條關係有多可信」，⑤ 講的是「這件事根本沒被查過」——兩者不能混用同一個欄位。

---

### ② 單一標準模型、多個視圖（One Model, Many Views）

**Context**

你已經有分級的關係（①）。一次分析要交付好幾種產物：一張圖、一兩個指標、一份文字報告，之後可能還有互動問答或匯出格式。這些產物由不同模組、甚至不同作者（程式或 LLM）產生。

**Problem**

> 每個產物各自從原始證據推導時，推導邏輯會分岔；讀者會看到「報告說 A、圖畫 B」，而且無從判斷誰對。

**Forces**

- **每個產物都想要最適合自己的輸入。** LLM 想讀原始文字，繪圖想讀結構，指標想讀清單。一個共同模型必然對某些消費者不夠貼身。
- **推導邏輯重複就會分岔。** 修了一處忘了另一處——而且分岔只在特定資料下才看得出來。
- **一個模型要承載所有視圖需要的欄位**（等級、來源、部署狀態、分層、流量計數），schema 會變胖。
- **有些視圖發生得很晚。** 報告發出幾天後有人追問，那時原始證據（叢集狀態、暫存的抽取結果）可能已經不存在。
- **耦合。** 所有視圖綁在同一個 schema 上，改 schema 影響面大。

**Solution**

因此：

1. 每次執行**先建一個標準模型，再產所有視圖**；模型只建一次。
2. 每個視圖都是模型的**純函式**：繪圖、指標、報告裡的事實段落（→ ③）、查詢、匯出。
3. **衍生計算只實作一次**（分數、分層、拓樸排序、傳遞閉包），所有視圖呼叫同一個實作。
4. 模型可以**序列化與還原**。晚發生的視圖（幾天後的追問）讀回同一份模型，不從原始證據重推，也不重新查叢集。
5. 需要給 LLM 的事實，**也由模型產生文字給它**，不讓它讀原始證據自己推。

*Running example*：圖、覆蓋率、報告都讀同一個 graph 物件。`orders → payment` 在三個產物裡都是「有使用證據、未量測」；`catalog → catalog-db` 在三個產物裡都是「只被提到」。要新增「部署順序」這個視圖時，它讀的是同一個模型的分層結果，不是重新解析部署描述。

**實作要點**

- 「純函式」是可測試性的來源：視圖的測試不需要叢集、不需要 API、不需要網路，只要一個手寫的模型。這讓「報告與圖是否一致」變成單元測試可以釘住的性質。
- 序列化的那份模型同時是**問答的基礎**：它讓「報告之後還能問細節」不必重跑分析。

**Resulting Context**

圖、指標、查詢都一致了。但文字報告仍由 LLM 撰寫；即使給了它模型產生的事實，它重述這些事實時仍會漏、會加、會改措辭。→ ③

**Consequences**

- ＋「報告與圖矛盾」在結構上消失，不需要靠人校對。
- ＋ 之後加新視圖（問答、匯出、部署計畫）不必重建推導邏輯。
- ＋ 測試集中在模型與衍生計算。
- ＋ 模型可以存檔，晚到的問題仍然有據可答。
- － schema 成為所有視圖的共同介面，改動要照顧所有消費者。
- － 模型建構失敗時所有視圖一起失敗，需要降級策略（接 ⑤：失敗要留痕跡）。
- － 原始證據裡有、模型沒收的資訊，所有視圖都拿不到。這可以是刻意的界線，但要寫下來。

**Known Uses**

1. **DepWeaver**
   - `DependencyReportService` 先 `buildGraph()`，同一個物件餵給 Mermaid／DOT 繪圖、`CoverageAnalyzer`、報告的事實段落、以及問答用的 archive；問答的「哪些依賴還沒有流量佐證」直接呼叫同一個 `CoverageAnalyzer`，部署順序用同一套分層。
   - **事件**：Bank of Anthos 的報告寫資料庫邊「執行期觀測：未知」，旁邊附的圖卻把同一條邊畫成實線（commit `8424538`）。根因是報告與圖各自推導；修法是兩者共用同一個模型（commit `9c13354`）。
2. **Pandoc**：各種輸入格式先轉成同一個 Pandoc AST，writers 再從這個 AST 輸出各種格式；所有輸出都來自同一份中間表示（<https://pandoc.org/using-the-pandoc-api.html>）。
3. **Model–View–Controller**：Trygve Reenskaug 1978–79 年在 Xerox PARC 提出，同一個 model 由多個 view 呈現（<https://folk.universitetetioslo.no/trygver/themes/mvc/mvc-index.html>）。本 pattern 可以看成 MVC 的 model 概念在「一次性分析產物」上的應用：這裡的 model 不是長駐的應用程式狀態，而是**一次執行的結果快照**。
4. **Backstage Software Catalog**：官方描述是「a centralized system that keeps track of ownership and metadata」，各外掛讀同一份 catalog（<https://backstage.io/docs/features/software-catalog/>）。

**Related Patterns**

前接 ① 證據分級的關係（模型承載等級與來源）。往下接 ③ 事實由程式寫（事實段落是模型的一個視圖）。④ 的排除規則寫在模型層，所有產生那個數字的視圖共用。

---

### ③ 事實由程式寫（Code-Authored Facts）

**Context**

你有標準模型（②）。報告由 LLM 撰寫，因為只有它寫得出通順、有解釋、讀者願意讀的散文。報告裡有一部分是**事實**：哪個元件依賴哪個資料庫、有沒有量測到、證據等級、連線次數——這些模型裡都有，而且有正確答案。另一部分是**詮釋**：這個服務在架構裡的角色、風險在哪、建議先做什麼。

**Problem**

> LLM 重述事實時會漂移，而且收緊 prompt 通常只是把錯誤換個樣子。怎麼保有 LLM 報告的可讀性，又讓事實不出錯？

**Forces**

- **讀者要一份連貫的文件**，不想看到拼接得很生硬的兩種文字。
- **範本文字很僵硬**：程式產生的段落讀起來像表格，缺少「所以呢」。
- **改 prompt 很便宜，所以很誘人**：改一次看起來好了，下次換個方式錯。
- **LLM 輸出的段落邊界不可靠**：它可能自己也寫了那一段、可能改了標題、可能把它放到別的位置。
- **事實與詮釋的界線要人判斷**：劃錯的話，LLM 仍會在詮釋段落裡重述事實。
- **詮釋需要事實**：不能只把事實藏起來不給它看，否則詮釋會和事實段落矛盾。

**Solution**

因此：

1. 把報告段落分成**事實型**（有正確答案、模型裡都有）與**詮釋型**。
2. 事實型段落**由程式從模型產生**，措辭固定且精確（例如寫「連線數」而不是「請求數」，因為量到的是前者）。
3. **告訴 LLM 跳過事實段落**；同時把同樣的事實以「已確認清單」的形式給它，讓詮釋段落不會和事實段落矛盾。
4. 用**結構錨點**把程式段落拼進 LLM 的報告：以下一節的標題為錨點插入；LLM 若自己也寫了該段，**丟掉它的版本**；找不到錨點就附在報告最後——事實永遠不會因為格式意外而遺失。
5. 段落末尾註明「本段由程式從模型產生」，讓讀者知道這一段的可信度不同。

*Running example*：「基礎設施依賴」一節由程式列出：`orders → orders-db`（量測到，12 次連線）、`catalog → catalog-db`（只被提到：有連線設定、無持久化程式碼）。LLM 在「風險」一節引用這兩條，但不重寫它們；它負責說出「catalog 的資料庫依賴沒有程式碼佐證，可能是殘留的設定，值得確認」。

**實作要點**

- 「丟掉 LLM 的版本」比「請它不要寫」可靠：後者是請求，前者是保證。
- 錨點失敗時**附在最後**而不是丟掉，因為事實遺失比排版難看嚴重。
- 事實段落可以單元測試：給定一個手寫模型，段落的文字是確定的。

**Resulting Context**

報告裡的清單與數字都由程式產生了。但「覆蓋率 70%」這種比率，程式要決定**分母**算進哪些項目；分母定錯，程式也會很精確地算出一個誤導的數字。→ ④

**Consequences**

- ＋ 讀者最可能拿去對照圖的部分，保證與圖一致。
- ＋ 不再反覆修 prompt；事實段落可以測試。
- ＋ 出錯時的除錯方向明確：事實錯就是模型錯，不必猜是不是模型「這次心情不好」。
- － 報告風格不一致（有一段是範本文字）。
- － 拼接依賴標題慣例；LLM 格式大改時要靠「附在最後」的退路。
- － 事實與詮釋的界線要人維護；界線劃錯時，錯誤會回到詮釋段落裡。

**Known Uses**

1. **DepWeaver**
   - `DependencyReportService.infrastructureSection()` 從圖產生報告的基礎設施一節，`spliceInfrastructureSection()` 以下一節標題為錨點拼接並丟掉 LLM 的版本；`dataLayerLedger()` 把量測到的資料庫邊當「已確認清單」給 LLM。
   - **事件**：報告與圖矛盾之後，**改 prompt 兩次都只是換一種錯法**——第二次甚至把建置期函式庫與雲端替代方案列成依賴（commit `8424538` → `9c13354`）。於是那一節改由程式產生，LLM 不碰。這是本 pattern 最直接的來源：**不是 prompt 寫得不夠好，是這件事不該交給 prompt。**
2. **Satyrn**（2024，analytics augmented generation）：先由分析程式從資料算出事實，再交給 LLM 寫報告。報告中正確主張的比例超過 **86%**，而直接讓 GPT-4 Code Interpreter 寫只有 **57%**（<https://arxiv.org/abs/2406.12069>）。這是本 pattern 最直接的學術實例，而且有量化證據。
3. **SymGen**（2023，變體）：LLM 不直接寫數值，而是輸出指向資料欄位的符號參照，再由程式代入（<https://arxiv.org/abs/2311.09188>）。同一個原則的另一種實作：不是「切出一段不給它寫」，而是「讓它寫佔位符」。
4. **R Markdown／knitr 的行內程式碼**：散文中用 `` `r ...` `` 嵌入程式運算結果，數字不手打（<https://rmarkdown.rstudio.com/lesson-4.html>）。這是本 pattern 在科學寫作裡的日常形態。
5. **由 OpenAPI 規格產生的參考文件**（Swagger UI、Redoc）：API 參考段落由規格自動產生，與手寫的指南並存於同一份文件（<https://swagger.io/tools/swagger-ui/>、<https://github.com/Redocly/redoc>）。

**Related Patterns**

前接 ② 單一標準模型（事實段落是模型的一個視圖）。往下接 ④ 誠實分母（程式一旦負責算數字，就得對分母負責）。與 ⑤ 未量測不是零直接相關：程式產生的事實段落絕不能用 `0` 代表「沒查過」。

---

### ④ 誠實分母（Honest Denominator）

**Context**

你用程式從模型算比率型指標（③）：覆蓋率、完整度、健康度。讀者拿它判斷「做得夠不夠、還要不要繼續補」。模型裡混著三種東西：量測機制**不可能**量到的項目、證據很弱的項目（①的「只被提到」），以及性質根本不同的項目（同步呼叫與資料庫連線）。

**Problem**

> 把量不到的項目算進分母，分數會被永久壓低，讀者被推去修一個修不好的缺口；把弱證據的項目算進去，文件多說幾句就會稀釋分數；排除太多又會虛高。而讀者只看到一個百分比，分不出是哪一種。

**Forces**

- **全部算進去看起來最保守**，卻讓讀者去追一個永遠到不了的 100%。
- **排除會虛高**：證據很薄時分母縮到只剩幾項，100% 沒有意義。
- **排除規則需要領域知識**：什麼是控制面、什麼是程序內元件、什麼是平台基礎設施。這是一份會長大的清單。
- **跨次比較**：分母定義改了，歷史數字就不可比。
- **不同量測混在同一個比率會改變數字的意義**：「請求被驅動」與「連線被觀測」是兩件事，平均起來兩邊都不成立。
- **讀者想要一個數字**：揭露越詳細，越沒有人讀。

**Solution**

因此：

1. 分母只算**同時**滿足兩個條件的項目：**量測機制做得到**（兩端都存在、不是刻意排除在量測外、不是程序內部元件），且**證據高於「只被提到」**（①）。已經量測到的一律算。
2. 性質不同的項目用**獨立的比率**，不混在一起（業務呼叫覆蓋率、資料層覆蓋率分開報）。
3. 分數旁邊**一定揭露**「未計分：N 項，理由」。
4. 被排除的比計分的多時，加上「此分數建立在很小的基礎上」的警告。
5. 同時列出**未覆蓋清單**，讓分數可以直接變成下一步行動。
6. 排除規則寫在一處，所有產生這個數字的視圖共用（②）。

*Running example*：業務呼叫覆蓋率的分母是 `web → orders`、`web → catalog`、`web → cart`、`orders → payment`，共 4 條，其中量到 3 條 → 3/4。`orders → shipping` 只被提到，不計分但揭露。`orders → orders-db` 另算「資料層 1/1」，不併進業務覆蓋率。

**實作要點**

- 「排除但揭露」和「排除就消失」是天差地別的兩件事，見下面 Known Uses 的反例。
- 警告的門檻要沒有自由參數才不必校準：「未計分的項目比計分的多」就是「多數」，不需要一個 30% 這樣的數字。
- 分母定義改動時，要在報告裡說明與舊數字的可比性——否則趨勢圖會說謊。

**Resulting Context**

分母只剩量測得到的項目了。但有時**整個指標**根本沒量測（這次沒有連上遙測），或某個元件的狀態從來沒查過；程式的預設值會把它們變成 0 或 false，LLM 會把空白猜掉。→ ⑤

**Consequences**

- ＋ 分數回答它宣稱回答的問題；文件幻覺不再拉低分數。
- ＋「永遠到不了 100%」的假缺口消失，讀者看得到分母的組成。
- ＋ 分數可以直接轉成行動（未覆蓋清單就是下一輪要驅動的流量）。
- － 排除規則是一份清單，要維護，也會有漏網之魚。
- － 多一行揭露文字；規則改變後要說明與舊數字的可比性。
- － 讀者可能只看百分比而不看揭露——這是本 pattern 無法單靠設計解決的部分。

**Known Uses**

1. **DepWeaver**
   - `CoverageAnalyzer`：只算可被流量驅動的業務同步邊；排除未部署的節點與平台控制面；`inferred`（只被提到）不進分母；資料層獨立計算並排除程序內快取；揭露未計分的數量，並在被排除的比計分的多時加警告。
   - **事件一**：spring-petclinic 修好服務發現之後重跑，覆蓋率被控制面、框架產生的偽節點與別名稀釋成**假的 45%**；改成只算可驅動的業務邊之後是 **4/4**（2026-07-29）。
   - **事件二**：Bank of Anthos 同一個叢集、同一段流量，只因為文件層多講了幾條不存在的邊，覆蓋率從 **7/7 變成 7/10**；改成「只被提到」的邊不進分母之後恢復（commit `03ba052`）。
   - **事件三**：程序內快取永遠量測不到，把資料層卡在 **5/6**；改成程序內元件不進分母（commit `99bb7d0`）。
   - 三個事件的共同形態值得注意：**每一次都是分母出錯，而不是量測出錯。**
2. **CONSORT 臨床試驗報告準則**：流程圖的每個階段都要寫排除人數與理由（「Excluded (n= )… Excluded from analysis (give reasons)」），檢核表也要求交代排除與理由（CONSORT 2010 Statement，<https://pmc.ncbi.nlm.nih.gov/articles/PMC2860339/>；已更新的 CONSORT 2025，<https://pubmed.ncbi.nlm.nih.gov/40228833/>）。這是「排除是可以的，但必須逐項揭露」最成熟的制度化實例。
3. **coverage.py**：覆蓋率的分母是可執行敘述數**減去**被排除的敘述數；被排除的行不進分母，但 HTML 報告以灰色標出並可切換顯示，JSON 報告也列出 `excluded_lines`（<https://coverage.readthedocs.io/en/latest/faq.html>）。
4. **反例，同樣重要**：
   - **JaCoCo** 的過濾器會讓被過濾的程式碼**直接從報告中消失**，文件沒有提到另外列出（<https://www.jacoco.org/jacoco/trunk/doc/changes.html>）——「排除但不揭露」。
   - **Istanbul** 會把被略過的程式碼**當成已覆蓋來計數**，雖然 HTML 以灰色標示並列出略過數量，但分子被灌水（<https://github.com/gotwarlost/istanbul/blob/master/ignoring-code-for-coverage.md>）——「排除但灌進分子」。
   兩個反例正好界定了本 pattern 的兩條紅線。

**Related Patterns**

前接 ① 證據分級的關係（等級是分母的過濾條件）與 ③ 事實由程式寫（比率是程式產生的事實）。往下接 ⑤ 未量測不是零（分母收斂之後，剩下「整個指標沒量測」這個情況）。

---

### ⑤ 未量測不是零（Unmeasured Is Not Zero）

**Context**

你的指標只算量測得到的項目（④）。但工具有多種執行模式：有沒有連上叢集、有沒有遙測、有沒有跑流量、有沒有網路可以讀文件。而且工具是 fail-soft 設計的——某個步驟失敗不中斷整條流程，因為使用者寧願要一份不完整的報告，也不要什麼都沒有。下游有程式的預設值，也有 LLM，兩者都會「填空」。

**Problem**

> 沒收集的資料在程式裡變成 0 或 false，在 LLM 手上變成猜測；失敗的步驟看起來和「什麼都沒找到」一模一樣。讀者把一個從未發生的量測當成結果。

**Forces**

- **二值欄位簡單**：改成三值（是／否／未知）會擴散到每一個讀取點。
- **空白會邀請填補**：空的段落在 LLM 看起來像漏寫了，它會很樂意補上。
- **fail-soft 讓系統不中斷**，卻會把失效藏起來。
- **讀者想要一個數字**：「未量測」看起來像沒做完，而 `0%` 看起來像做完了而且很糟——後者更容易被接受，這正是危險所在。
- **原因要傳到很遠的下游**：執行模式從入口一路要帶到報告、指標與問答，漏一個地方就破功。

**Solution**

因此：

1. 狀態欄位用**三值**，預設是「未知」而不是 false；只有在證據明確時才設為 false。
2. **執行模式**（這次沒收集哪些證據）當成中繼資料，隨模型傳到每一個視圖。
3. 視圖遇到未知時**寫一句話**：為什麼未知，以及補什麼證據才會知道。
4. **絕不用 0、0/N、0% 這種數值佔位代表未量測**，尤其不要把這種佔位交給 LLM。直接給它「未量測」的敘述。
5. 步驟失敗時記錄**失敗狀態與原因**，不要回傳空結果——空結果會被讀成「找過了，沒有」。
6. 空段落寫成「無，原因是……」，不留白。

*Running example*：只做靜態分析時，覆蓋率顯示「未量測：本次沒有連上遙測」，而不是 `0%`；`cart` 的部署狀態顯示「未知：沒有查詢叢集」，而不是「未部署」。

**實作要點**

- 三值最省的實作是用可為 null 的布林（`Boolean` 而非 `boolean`），但關鍵不在型別而在**預設值的方向**：預設必須是未知。
- 「補什麼證據才會知道」這句話讓未知變成可行動的，而不只是誠實。

**Resulting Context**

這個 language 的終點：讀者看到的每一條關係都有等級（①）、每一個產物說同一件事（②）、事實不經 LLM 重述（③）、每一個數字的分母是誠實的（④）、沒量到的明說沒量到（⑤）。

接下來會出現的是**另外兩個問題**，各自是另一個 language：證據不夠時怎麼有系統地補（分段檢查點、覆蓋率導向的補證迴圈、最後才問人），以及報告交付之後怎麼讓人對它提問（見 §7）。

**Consequences**

- ＋ 讀者不會把沒發生的量測當成結果。
- ＋ 靜態模式與執行期模式可以共用同一套產物而不誤導。
- ＋ 失效變得可見——fail-soft 不再等於靜默退化。
- － 每一個讀取點都要處理三值；產物裡多了「未知，因為……」的文字。
- － 只要漏一個地方沒傳執行模式（例如某個 prompt），幻覺就從那裡回來。這是本 pattern 最脆弱的地方：它要求全面實施。

**Known Uses**

1. **DepWeaver**
   - `DependencyGraph.Node.deployed` 用可為 null 的 `Boolean`（null 表示未判定），只對服務類型在有叢集資料時設 false；報告寫「Not determined」；問答在純靜態模式下給 LLM 的字串是「NOT MEASURED」而不是 `0%`；抽取失敗記在 `EdgeLedger.fail()` 而不是回傳空清單。
   - **事件一**：容器映像檔裡沒有 git，程式碼抽取在容器內從未成功過，而 fail-soft 讓它**靜默退化**成只看文件——報告看起來正常，只是所有程式碼證據都不見了（2026-07-13）。
   - **事件二**：純靜態模式的報告幻覺出「25 Services observed / 50 Pods Running」（commit `40f7448`）。
   - **事件三**：純靜態模式的問答把「未量測」講成「覆蓋率 0%」（commit `89402dc`）。
   - 三個事件都不是計算錯誤，而是**空白被填滿**。
2. **SQL 的 NULL 與三值邏輯**：NULL 表示「未知」，一般比較運算遇到 NULL 的結果是 NULL（例如 `7 = NULL` 的結果是 NULL）而不是 false（<https://www.postgresql.org/docs/current/functions-comparison.html>）。這是資料領域對本 pattern 最古老、也最徹底的實作——連代價（三值邏輯會擴散到每一個查詢）也一樣。
3. **Kubernetes 的 condition**：Pod／Node condition 的 status 取值是 `True`、`False`、`Unknown`；Node 的 Ready 為 `Unknown` 代表 node controller 在寬限期內沒有收到回報，而**不是**「沒有就緒」（<https://kubernetes.io/docs/concepts/workloads/pods/pod-condition/>、<https://kubernetes.io/docs/reference/node/node-status/>）。
4. **Grafana 與 Prometheus**：Grafana 的告警有獨立的 **No Data** 狀態，定義是「查詢成功但沒有任何資料點」，與數值 0 分開處理（<https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/>）；Prometheus 的 `absent()`／`absent_over_time()` 專門用來偵測「序列不存在」（<https://prometheus.io/docs/prometheus/latest/querying/functions/>）。監控領域把「沒有資料」當成一級狀態，因為把它當 0 會讓告警在系統掛掉時反而安靜。

**Related Patterns**

前接 ④ 誠實分母。與 ① 證據分級的關係要分清楚：等級講「多可信」，本 pattern 講「有沒有被查過」，兩者不能共用同一個欄位——一條「只被提到」的關係是有證據的（弱證據），而一個「未知部署狀態」是完全沒有證據。

---

## 5. 相關研究

本文的定位是**設計知識**（pattern），不是新技術或新演算法。相關研究分四條線，每一條都說明「它建立了什麼前提」與「它留下什麼沒回答」。

### 5.1 證據分級在別的領域是成熟做法

醫學的 GRADE 是最完整的先例。它 2004 年提出時的動機正是「當時有六套以上互相衝突的證據分級系統」——也就是說，**分級本身沒有爭議，爭議的是怎麼分**（GRADE Working Group, BMC Health Services Research 2004）；2011 年起的方法學系列把「證據品質」正式定義為「對效果估計的信心」（Guyatt et al., J Clin Epidemiol 2011）。GRADE 把**證據品質**與**建議強度**分成兩個軸，這與本文 ① 把「等級」與「來源」分開存、④ 把「等級」與「是否計分」分開處理，是同一個設計直覺。

軟體工程這一側，實證軟體工程（EBSE）把這套方法搬進來，並指出軟體工程的證據普遍較弱、隨機對照試驗稀少，因此**通常接受所有等級的證據，但必須標明等級**（Kitchenham, Dybå & Jørgensen, ICSE 2004；同一批作者的專書 CRC Press 2015）。這正是依賴分析工具的處境：遙測很少、程式碼分析有限、文件常常是唯一來源。

**可反駁之處，先寫在前面**：醫學分級的對象是「研究設計」，我們分級的對象是「一條關係的來源」，抽象層級不同。共通的是**同一個結論可以由不同可靠度的證據支撐，而使用者必須看得見是哪一種**。

### 5.2 業界標準已經在記錄證據與信心

- **OWASP CycloneDX** 的 `evidence.identity`：每個元件可記錄用了哪些方法、每個方法的 **confidence 0–1**、以及在原始碼的哪些位置（現行 1.6 規格）。
- **SLSA build track 與 in-toto attestation**：以**等級**描述 provenance 的可信度與完整度。
- **W3C PROV-DM**：provenance 的定義就是「用來評估品質、可靠度或可信度的資訊」。

三者共同證明本文 ① 不是學術玩具。**差異也要講**：CycloneDX 用連續信心值，我們用離散三級；微服務依賴領域目前**沒有**現成的分級命名標準，三級的名字是我們自己定的，論文要說明為什麼選離散（可解釋、可直接對應線型、沒有自由參數）。

### 5.3 單一來源不完整，所以必須合成

- **靜態呼叫圖的 recall**：相對於實際執行，中位數 0.884；用上最好的動態特性支援可到 0.935；主要漏源不是反射，而是 native 方法與 JVM 自行發起的呼叫（Sui et al., ICSE 2020）。同一個問題四年後的重做見 *Total Recall? How Good Are Static Call Graphs Really?*（ISSTA 2024）。
- **微服務架構回復工具的比較**：13 個工具收集、9 個成功執行；單一最佳工具 F1 0.86，**四個工具合起來 0.91**（Schneider et al., Empirical Software Engineering 30(5), 2025）。
- **依賴品質決定回復品質**：依賴關係的正確度會顯著影響架構回復的結果（Lutellier et al., ICSE 2015）。

這條線建立了本文的前提：**合成是必要的**。它沒有回答的是：合成之後，讀者怎麼知道哪一條是哪一種——那正是本 language 的問題。

### 5.4 讀者端與 LLM 端

- **不確定性怎麼畫**：「邊的主屬性 ＋ 邊的不確定性」要用哪兩個視覺變數同時呈現，有專門的受控實驗（Guo, Huang & Laidlaw, IEEE TVCG 2015），並指出視覺變數之間會互相干擾——這是 ① 的 forces 之一。
- **表示法與規模**：超過大約二十個頂點時，矩陣式呈現在多數任務上勝過節點連結圖，**只有路徑尋找始終站在節點連結圖這邊**（Ghoniem, Fekete & Castagliola, Information Visualization 2005）；這個比較的大規模重做支持節點連結圖在連通性與記憶類任務較好，但**沒有重述那個單一門檻**（Okoe, Jianu & Kobourov, IEEE TVCG 2019）。
- **誠實的代價**：不確定性視覺化對信任是雙面刃，可能提升可信度，也可能被讀成「不可靠」（CHI 2026）。
- **LLM 的引用不等於支撐**：生成式搜尋引擎平均只有 51.5% 的句子被引用完整支撐（Liu, Zhang & Liang, Findings of EMNLP 2023）；研究型 agent 的連結有效率超過 94%、相關性超過 80%，但事實正確率只有 39–77%，且工具呼叫次數越多越糟（Onweller et al., 2026）。
- **先算後寫的效果**：由分析程式算出事實再交給 LLM 撰寫，報告中正確主張的比例超過 86%，而直接讓 GPT-4 Code Interpreter 寫是 57%（Satyrn, 2024）——這是 ③ 最直接的量化支撐。

### 5.5 PLoP 系列裡最接近的 pattern 論文

在 AsianPLoP／PLoP／EuroPLoP 的論文集裡，**沒有找到**以「架構回復」或「依賴分析」為主題的 pattern 論文，這是本文的定位空缺。主題上最接近的是：

| 論文 | 出處 | 關係 |
|---|---|---|
| Patterns for AI-Assisted Document Maintenance and Traceability | AsianPLoP 2026 | AI 產生文件的可追溯性，最接近 ③ |
| Patterns for Log Dissemination in Cloud-Native Environments | AsianPLoP 2025 | 雲原生可觀測性 |
| A tale of two worlds: improving microservices-based systems | AsianPLoP 2026 | 微服務 |
| Service Mesh Patterns | EuroPLoP 2022 | 遙測來源（Istio） |
| A Prompt Pattern Catalog to Enhance Prompt Engineering with ChatGPT | PLoP 2023 | LLM 相關 pattern；本文 ③ 的立場正好與它互補——**有些事不該靠 prompt 解** |

---

## 6. 討論與限制

### 6.1 這個 language 適用到哪裡

適用的條件有三個，缺一個就不成立：

1. **證據來自多個來源，而且可靠度確實不同。** 只有一種來源時，分級沒有意義。
2. **產物有多個**（圖、數字、報告至少兩個），而且它們會被同一個讀者對照。
3. **讀者會據以決策。** 純瀏覽用的視覺化可以接受「大概對」，決策不行。

不適用或效益很低的情況：單一來源的工具；產物只有一張圖；以及**證據只有一種等級**的場合（例如純粹從執行期追蹤產生的服務圖——那時整張圖都是 observed，分級退化成沒有作用）。

### 6.2 這五個 pattern 的證據強度

| Pattern | DepWeaver 的證據 | 外部實例 | 強度評估 |
|---|---|---|---|
| ① 證據分級的關係 | 三級實作 ＋ 真實叢集上等級上升的觀測 ＋ 兩次反饋驅動的修正 | Kiali、Admiralty Code、CycloneDX、SLSA、PROV | **強**。跨五個領域，而且有標準組織的實例 |
| ② 單一標準模型 | 一次「報告與圖矛盾」的事故與其修法 | Pandoc AST、MVC、Backstage | **強**，但也最接近既有知識（MVC 的變體）——論文要說清楚新的部分是「一次性分析產物的快照模型」與「晚到的視圖讀回同一份模型」 |
| ③ 事實由程式寫 | 改 prompt 兩次都只換一種錯法，最後改由程式產生 | Satyrn（有量化結果）、SymGen、knitr、OpenAPI 產生文件 | **強**，而且有唯一一個帶量化效果的外部實例 |
| ④ 誠實分母 | 三次分母事故（45% 假分數、7/7→7/10、5/6 卡住） | CONSORT、coverage.py ＋ 兩個反例（JaCoCo、Istanbul） | **強**。反例讓紅線清楚 |
| ⑤ 未量測不是零 | 三次「空白被填滿」事故 | SQL NULL、Kubernetes condition、Grafana No Data、Prometheus `absent()` | **強**，而且外部實例橫跨資料庫、編排、監控三個領域 |

### 6.3 威脅效度

- **所有 pattern 都來自同一個工具。** 外部實例證明「別人也這樣做」，但不能證明「在別的工具裡照這個順序建構也會順利」。這個 language 的**生成性**（套用一個之後產生下一個的情境）目前只在一個系統上走過一遍。
- **事件是回溯敘述的。** 每個 pattern 的事件都有 commit 或日期，但「當初為什麼這樣改」是作者事後整理的，不是當時的設計文件。
- **沒有使用者研究。** 我們沒有量過「讀者看到分級之後決策是否變好」；文獻裡也沒有找到這樣的研究（這是可以主張的貢獻缺口）。特別是 ① 的 forces 裡「誠實揭露可能降低信任」這一條，我們只有文獻，沒有自己的量測。
- **三級的粒度沒有被驗證過是最佳的。** 我們有理由選三級（可解釋、對應線型、無自由參數），但沒有比較過四級或連續值。
- **LLM 的行為會變。** ③ 與 ⑤ 的必要性建立在「LLM 會漂移、會填空」之上。若未來的模型不再如此，這兩個 pattern 的 forces 會弱化——但**它們的解法（把事實從模型手上拿走）在那種情況下仍然是無損的**，因為程式產生的事實不會因為模型變好而變差。

---

## 7. 結論與後續

本文把「多來源合成的依賴視圖怎麼讓人信任」拆成五個依序解決的設計問題，並給出每一步的解法、代價與外部先例。核心主張很短：**能由程式從證據算出來的，就不要讓語言模型重述**；而要做到這件事，需要先有分級的關係與單一的模型，之後還要對分母與空白負責。

工具開發過程中另外浮現兩組 pattern，各自是不同的問題，留作後續：

- **證據不夠時怎麼有系統地補**：分段證據檢查點（在每個階段檢查證據是否足以繼續）、覆蓋率導向的補證迴圈（用未覆蓋清單驅動下一輪流量）、最後才問人（能自動取得的證據都取完之後，才向人索取密碼或帳號這類只有人知道的資訊）。這一組的重點是**時間軸上的補證**，與本文的「怎麼呈現」不同。
- **報告之後的提問**：交付一份報告之後，讀者會追問細節。把自然語言問題轉成對模型的確定性查詢、按權威順序組織 context、讓 LLM 只負責把查詢結果講成人話——這一組的重點是**互動**。

---

## 參考文獻

> 書目格式待依 ACM 單欄模板調整；此處先列完整資訊與可查證的連結。標「摘要」者尚未讀全文（見「投稿前待辦」）。

1. GRADE Working Group. *Systems for grading the quality of evidence and the strength of recommendations I: critical appraisal of existing approaches*. BMC Health Services Research, 2004. <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC545647/>（摘要）
2. Guyatt, G. et al. *GRADE guidelines: 1. Introduction — GRADE evidence profiles and summary of findings tables*. Journal of Clinical Epidemiology 64(4), 2011. <https://doi.org/10.1016/j.jclinepi.2010.04.026>（摘要）
3. Kitchenham, B., Dybå, T., Jørgensen, M. *Evidence-based Software Engineering*. ICSE 2004.
4. Kitchenham, B., Budgen, D., Brereton, P. *Evidence-Based Software Engineering and Systematic Reviews*. CRC Press, 2015. <https://doi.org/10.1201/b19467>（僅見書目）
5. Sui, L. et al. *On the Recall of Static Call Graph Construction in Practice*. ICSE 2020. <https://dl.acm.org/doi/10.1145/3377811.3380441>（摘要）
6. *Total Recall? How Good Are Static Call Graphs Really?* ISSTA 2024. <https://www.opal-project.de/articles/TotalRecall@ISSTA24.pdf>（僅見標題與摘要）
7. Schneider, S. et al. *Comparison of static analysis architecture recovery tools for microservice applications*. Empirical Software Engineering 30(5), 2025. <https://doi.org/10.1007/s10664-025-10686-2>（摘要）
8. Lutellier, T. et al. *Comparing Software Architecture Recovery Techniques Using Accurate Dependencies*. ICSE 2015. <https://ieeexplore.ieee.org/document/7202951/>（摘要）
9. Guo, H., Huang, J., Laidlaw, D. H. *Representing Uncertainty in Graph Edges: An Evaluation of Paired Visual Variables*. IEEE TVCG, 2015. <https://ieeexplore.ieee.org/abstract/document/7089294>（摘要）
10. Ghoniem, M., Fekete, J.-D., Castagliola, P. *On the readability of graphs using node-link and matrix-based representations: a controlled experiment and statistical analysis*. Information Visualization 4(2):114–135, 2005. <https://doi.org/10.1057/palgrave.ivs.9500092>（已讀摘要頁原文）
11. Okoe, M., Jianu, R., Kobourov, S. *Node-Link or Adjacency Matrices: Old Question, New Insights*. IEEE TVCG 25(10):2940–2952, 2019. <https://doi.org/10.1109/TVCG.2018.2865940>（摘要）
12. *The Impact of Uncertainty Visualization on Trust in Thematic Maps*. CHI 2026. <https://doi.org/10.1145/3772318.3790743>（摘要）
13. Liu, N. F., Zhang, T., Liang, P. *Evaluating Verifiability in Generative Search Engines*. Findings of EMNLP 2023. <https://aclanthology.org/2023.findings-emnlp.467/>（摘要）
14. Onweller, C. et al. *Cited but Not Verified: Parsing and Evaluating Source Attribution in LLM Deep Research Agents*. arXiv:2605.06635, 2026. <https://arxiv.org/abs/2605.06635>（摘要）
15. *Satyrn: A Platform for Analytics Augmented Generation*. arXiv:2406.12069, 2024. <https://arxiv.org/abs/2406.12069>（摘要）
16. *SymGen: Fine-Grained Attribution*. arXiv:2311.09188, 2023. <https://arxiv.org/abs/2311.09188>（摘要）
17. NATO. *AJP-2.1 Allied Joint Doctrine for Intelligence Procedures*（Admiralty Code；**投稿前要引原文，目前只有二手與同儕審查論文來源**）
18. OWASP CycloneDX. *Authoritative Guide to SBOM* — component evidence. <https://cyclonedx.org/guides/sbom/evidence/>；1.6 JSON 規格 <https://cyclonedx.org/docs/1.6/json/>
19. SLSA. *Build provenance / FAQ*. <https://slsa.dev/spec/v1.1/faq>
20. W3C. *PROV-DM: The PROV Data Model*. <https://www.w3.org/TR/prov-dm/>
21. Kiali. *Graph FAQ（Idle Edges）*. <https://kiali.io/docs/faq/graph/>
22. CONSORT 2010 Statement. <https://pmc.ncbi.nlm.nih.gov/articles/PMC2860339/>；CONSORT 2025, BMJ 2025;389:e081123. <https://pubmed.ncbi.nlm.nih.gov/40228833/>
23. coverage.py FAQ. <https://coverage.readthedocs.io/en/latest/faq.html>
24. JaCoCo change log（過濾器不另外列出被排除的程式碼）. <https://www.jacoco.org/jacoco/trunk/doc/changes.html>
25. Istanbul. *Ignoring code for coverage*. <https://github.com/gotwarlost/istanbul/blob/master/ignoring-code-for-coverage.md>
26. PostgreSQL. *Comparison Functions and Operators（NULL 的三值邏輯）*. <https://www.postgresql.org/docs/current/functions-comparison.html>
27. Kubernetes. *Pod conditions*、*Node status*. <https://kubernetes.io/docs/concepts/workloads/pods/pod-condition/>、<https://kubernetes.io/docs/reference/node/node-status/>
28. Grafana. *No Data and Error states*. <https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/>
29. Prometheus. *Query functions（`absent()`、`absent_over_time()`）*. <https://prometheus.io/docs/prometheus/latest/querying/functions/>
30. Pandoc. *Using the pandoc API（AST）*. <https://pandoc.org/using-the-pandoc-api.html>
31. Reenskaug, T. *MVC — Xerox PARC 1978–79*. <https://folk.universitetetioslo.no/trygver/themes/mvc/mvc-index.html>
32. Backstage. *Software Catalog*. <https://backstage.io/docs/features/software-catalog/>
33. R Markdown. *Inline code*. <https://rmarkdown.rstudio.com/lesson-4.html>
34. Hillside. *Pattern Writing Checklist*. <https://hillside.net/index.php/pattern-writing-checklist>

---

## 附錄 A：Pattern 與實作的對應（審稿人查證用）

| Pattern | 主要實作位置 | 釘住它的測試 |
|---|---|---|
| ① 證據分級的關係 | `Graph/DependencyGraph.addEdge()`、`Graph/DotEmitter`、`Graph/MermaidEmitter` | `DependencyGraphTest`（等級取最高、來源聯集、資料庫邊的升級條件） |
| ② 單一標準模型、多個視圖 | `DependencyReportService.buildGraph()` 之後的所有視圖；`Qa/ReportArchive`（序列化） | `ReportQaContextTest`、`PartialGraphQaTest`（視圖是模型的純函式） |
| ③ 事實由程式寫 | `DependencyReportService.infrastructureSection()`、`spliceInfrastructureSection()`、`dataLayerLedger()` | 報告段落的離線測試（給定模型，段落文字確定） |
| ④ 誠實分母 | `Graph/CoverageAnalyzer`；thin-evidence 警告在 `CoverageAnalyzer.Report.isThinlyEvidenced()`，訊息在 `DepstateToolkit` | `DependencyGraphTest.coverageCountsDrivableBusinessEdgesOnly` 等（排除規則、揭露） |
| ⑤ 未量測不是零 | `DependencyGraph.Node.deployed`（可為 null 的 `Boolean`）、`CodeExtraction/EdgeLedger.fail()`、問答的 greenfield 模式字串 | `ReportQaContextTest`（靜態模式給 LLM 的是「NOT MEASURED」而不是 0%） |

## 附錄 B：投稿資訊（AsianPLoP）

- **論文類型**：Regular paper（≤ 20 頁）或 Short paper（≤ 10 頁），兩者都走 shepherding ＋ writers' workshop。PLoP 系列明確接受 pattern、pattern language、proto-pattern 與 partial pattern language。
- **格式**：ACM 單欄模板。2026 屆收英文與日文（2025 屆另收中文）。
- **審查流程**：委員會初審 → 分配 shepherd 帶著來回修改 → 最終決定 → writers' workshop 討論（每篇約一小時，作者多半聆聽）→ 依討論修改交論文集版；每篇至少一位作者要註冊參加。
- **時程**：2026 屆於 3/27–30 在橫濱舉辦，投稿截止 2026-01-05。**2027 屆徵稿尚未公告**，比照往年推測截止約在 2026 年 12 月至 2027 年 1 月。
- 出處：<https://plopcon.org/asianplop2026/submissions/>、<https://plopcon.org/writers-workshop/>

---

## 投稿前待辦

1. **翻成英文**並套 ACM 單欄模板；五個 pattern ＋ running example 預估超過 10 頁，投 Regular paper。
2. **讀全文確認數字**：ICSE 2020 的 0.884／0.935、EMSE 2025 的 0.86／0.91、Okoe et al. 2019 的結果節、ISSTA 2024。目前標「摘要」的都要處理。
3. **Admiralty Code 改引 AJP-2.1 原文**（現在只有二手與同儕審查論文來源）。
4. **§6.2 表中 ② 的定位要再寫一次**：它與 MVC 的差別必須在論文本文講清楚，否則會被評為既有知識。
5. **決定 running example 的處理**：目前是中性的商店例子，DepWeaver 只出現在 Known Uses。若 shepherd 認為證據不夠具體，替代方案是直接用 DepWeaver 當 running example。
6. 寄信問主辦單位論文集是否收進 ACM DL。
7. 討論稿 `docs/pattern-language-asianplop.md` §6 的四個問題（問題定義、pattern 順序、running example 的選擇、後續兩組 pattern 是否留作續篇）若有新的決定，回頭同步本文件。
