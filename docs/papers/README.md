# 投稿論文（Word 版）

| 檔案 | 投哪裡 | 格式 | 內容 |
|---|---|---|---|
| `asianplop-2027-zh.docx` | AsianPLoP 2027 | ACM 單欄（PLoP 官方 Word 範本 `src/acm_submission_template.docx`，取自 plopcon.org） | Pattern language「相依架構抽取」（英文標題 A Pattern Language for Dependency Architecture Extraction in Microservice Systems；10/23 由「相依圖重建 Dependency Map Recovery」改名）：四個論文有、業界在用的做法，**每個 pattern 自成一節（§5–§8），開頭一句 slogan，Solution 附流程圖**（三個證據來源寫成背景；「以流量走遍路徑」是 ① 的前提；「一份目錄」「無資料不等於零」併入 ④）。**10/19 起不提 DepWeaver**（COMPSAC 未發表不能引用），納入標準改成論文＋業界兩個條件 |
| `compsac-2027-zh.docx` | IEEE COMPSAC 2027 | IEEE 會議格式：US Letter、雙欄、Times 10pt（照 IEEE 規格自建；官網範本被反爬蟲擋住，定稿前貼進官方範本） | DepWeaver 自己的方法與實驗。**10/26 起**：46 條規則、RQ1 分「歸納用 7 系統」與「hold-out B／C」三輪轉移量測（表 VIII），純 LLM 對照以更正後參考邊集重算；資料在 `docs/generalization/{scores.md,holdout,holdout-c}`、`docs/experiments/2026-10-20/` |

兩份都是**中文稿**，給老師看；定稿後再翻成英文。

名詞導讀：`glossary.md`（另有 `glossary.docx`，橫向排版）。兩篇合併成一份、每個名詞只解釋一次：第一部分照 AsianPLoP 的出現順序（含兩篇共用的名詞），第二部分只列 COMPSAC 才有的，第三部分是會議期刊縮寫。改了論文內文要記得一起更新。

## 改內容

**不要直接改 .docx。** 改 `src/plop.txt` 或 `src/compsac.txt`，再重新產生：

```
python3 docs/papers/src/build_plop.py    docs/papers/asianplop-2027-zh.docx 19   # 最後一個參數＝頁數，寫進 ACM Reference Format
python3 docs/papers/src/build_compsac.py docs/papers/compsac-2027-zh.docx
```

標記：`#`／`##` 標題、空行分段、`**粗體**`、`- ` 條列、`1. ` 編號、`> ` 引文、`!table 標題` … `!end`（`!widths` 以 dxa 為單位）、`!fig 檔名 | 圖說 | 寬（吋）`、`!algo` ＋ `!code` … `!endcode`。引用寫 `[@key]`，`!bib key | 內容`：COMPSAC 依第一次引用的順序編號（IEEE `[1]`）；AsianPLoP 用 ACM 的作者＋年份（`[Schneider et al. 2025]`），參考文獻不編號、依字母排序，標籤從 `!bib` 內容自動取（作者. 年份. …；組織文件取 accessed 年份）；沒被引用的會印警告。

圖在 `src/fig/`：改 `.dot` 後 `dot -Tpng -Gdpi=300 x.dot -o x.png`。四張 Solution 流程圖（`p_flow1`–`p_flow4`）是 `src/fig/flows.py` 產生的（固定格位、蛇形排列，避免圖太高把版面往下推），改那支程式再跑 `python3 flows.py`，不要直接改它們的 `.dot`。

## 兩篇共用的用詞（2026-10-14 定）

架構重建（architecture recovery）、human-in-the-loop（不翻）、無叢集模式（COMPSAC 正文不再寫 greenfield／純靜態模式；10/21 統一）、缺口補齊（gap-filling）、僅推測／有使用證據／量測到、吻合率（agreement）、參考邊集合、統一模型（canonical model）、產出物、相依關係、連線設定、控制平面、行程內、未對應的邊、部署描述檔、產生流量（流量）／觸發（呼叫、路徑）、名稱、annotation（不翻）。language 名稱（2026-10-23 起）：相依架構抽取（Dependency Architecture Extraction）；研究領域本身仍稱架構重建（architecture recovery）。pattern 名稱（2026-10-23 起）：① 混合重建 Hybrid Reconstruction（前提：先以流量走遍路徑）、② 實測優先於推測 Observation over Inference、③ 請人確認名稱對應 Human-Confirmed Name-Mapping、④ 基於事實的解釋 Fact-Based Explanation（含一份目錄多個視圖、無資料不等於零）。

## 參考文獻格式（2026-10-13 對過規範）

- **AsianPLoP（ACM）**：`作者. 年份. 標題. In *論文集名*. 頁碼. https://doi.org/…`；期刊 `*期刊名* 卷, 期 (年), 頁碼.`；線上文件 `組織. *標題*. 網站. Retrieved October 13, 2026 from 網址`。一筆只對應一個網頁。
- **COMPSAC（IEEE）**：`A. Author, “Title,” in *Proc. XXX*, 年, pp. x–y, doi: …`；期刊 `*Abbrev.*, vol., no., pp., 年`；線上文件 `組織, “Title,” 網站. Accessed: Oct. 13, 2026. [Online]. Available: 網址`。
- `*斜體*` 寫在 `!bib` 裡；網址與 `doi:` 會自動變成可點的超連結，內文引用會連到對應的參考文獻條目。

## 頁數（2026-10-13，AsianPLoP 刪減後）

| | 中文稿 | 上限 | 備註 |
|---|---|---|---|
| AsianPLoP | **19 頁**（10/23 用 Word 匯出 PDF 實數：加了四張流程圖、一個 pattern 一節；10/22 是 16 頁） | regular 20 頁 | **AsianPLoP 2027 收中文稿**（要附英文標題與摘要），WWS 截止 2026-12-07 |
| COMPSAC | **18 頁**（10/26 用 Word 匯出 PDF 實數：正文加了 hold-out 三輪與表 VIII，附錄規則表 14 條→46 條；10/22 是 14 頁）| full paper 10 頁（含參考文獻），可加買 2 頁 | 英文大約 12–14 頁；**附錄 46 條規則表要移到線上 artifact，正文只留節錄** |
