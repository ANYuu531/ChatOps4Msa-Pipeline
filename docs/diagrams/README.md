# DepWeaver 的說明用流程圖（draw.io）

老師要求流程圖改用 draw.io，這裡是正式版本的原始檔。

| 檔案 | 圖 |
|---|---|
| `fig1-pipeline-overview.drawio` | **Fig.1** 端到端流程總覽（Pipeline Overview） |
| `fig2-evidence-merge-chain.drawio` | **Fig.2** 證據合併鏈（Evidence Merge Chain） |

## 界線：哪些圖是 draw.io、哪些不是

**只有「說明用的流程圖」改 draw.io** —— 就是上面這兩張。它們是給人看的文件圖，本來就該手工排版。

**工具自動產出的依賴圖維持 Graphviz / Mermaid**（Fig.3 那類）。那是程式在跑的時候即時生成的，一次可能 50 個節點、每次結果都不同，不可能也不該用 draw.io 畫。

這條界線建議在會議上先講明，免得被理解成「整套繪圖換掉了」。

## 怎麼開、怎麼改

三種都可以，檔案格式一樣：

- **VS Code**：裝 `hediet.vscode-drawio` 擴充，直接點開 `.drawio` 就是編輯器。
- **桌面版**：[draw.io Desktop](https://github.com/jgraph/drawio-desktop/releases)。
- **瀏覽器**：[app.diagrams.net](https://app.diagrams.net) → File → Open From → Device。

## 匯出（投影片 / 論文用）

repo 裡只放 `.drawio` 原始檔，**沒有預先匯出的 PNG/SVG** —— 產生它們需要 draw.io 本身，而這台開發機沒有裝。你在任一上述環境開啟後：

- **投影片**：File → Export as → PNG，勾 **Transparent Background**、Zoom 200%（投影機上才不糊）。
- **論文**：File → Export as → SVG（向量，放大不失真）或 PDF。

匯出的圖檔建議放在 `docs/diagrams/` 下、檔名對齊圖號（`fig1-pipeline-overview.png`），或直接放進投影片不進 repo —— 看你習慣。

## 排版規範（兩張圖共用，沿用 8/14 講稿老師已熟悉的標記）

| 元素 | 樣式 | 意思 |
|---|---|---|
| 藍底 `#dae8fc` | 〔程式碼〕 | 我們自己寫的確定性邏輯 |
| 紫底 `#e1d5e7` | 〔AI〕 | LLM：需要讀/寫自然語言、語意理解的地方 |
| 藍紫漸層 | 混合 | 情境由 AI 產生、執行是程式碼（驅動流量那步） |
| 橘色菱形 `#ffe6cc` | 決策點 | 等使用者按按鈕 |
| 黃色便條 `#fff2cc` | 註記 | greenfield 行為、誠實邊界這類補充 |
| 灰色圓柱 | checkpoint / 原始證據 | 存在 dep-state 的東西 |

**為什麼要標〔程式碼〕/〔AI〕**：老師最常問的一題是「這整套是不是就靠 LLM？」。圖上直接標出來，答案自己就在圖裡——骨幹是確定性程式碼，AI 只用在三處（讀文件、產流量情境、補殘餘+寫報告）。

## 和 Mermaid 版的關係

`docs/dependency-analysis-flow.md` 裡的 Mermaid 版**保留**，兩者職責不同：

- **Mermaid**（在 md 裡）：GitHub / HackMD 直接渲染，純文字可 grep、可 diff，改起來快。它是**跟著程式碼一起維護的活文件**。
- **draw.io**（這裡）：排版可控、能匯出高解析圖。它是**對外交付的正式圖**。

兩邊內容要一致。改流程時先改 Mermaid（因為它貼著程式碼），要交出去之前再同步 draw.io。
