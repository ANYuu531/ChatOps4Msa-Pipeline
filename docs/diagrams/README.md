# DepWeaver 的說明用流程圖（draw.io）

老師要求流程圖改用 draw.io，這裡是正式版本的原始檔。

| 檔案 | 圖 |
|---|---|
| `fig1-pipeline-overview.drawio` | **Fig.1** 端到端流程總覽（Pipeline Overview） |
| `fig2-evidence-merge-chain.drawio` | **Fig.2** 證據合併鏈（Evidence Merge Chain） |

底下另有三張 **Fig.3**，它們**不是** draw.io 畫的，是工具自己輸出的（見下一節）：

| 檔案 | 圖 |
|---|---|
| `fig3a-boa-layered.png` | Bank of Anthos 分層依賴圖（runtime，10 節點 / 12 邊，全實線） |
| `fig3b-train-ticket-layered.png` | train-ticket 分層依賴圖，**只含有邊的 34 個節點**（greenfield，全虛線） |
| `fig3c-train-ticket-full.png` | 同上但含全部 53 個節點——含 19 個無邊節點，寬 7657px |

## 界線：哪些圖是 draw.io、哪些不是

**只有「說明用的流程圖」改 draw.io** —— 就是上面這兩張。它們是給人看的文件圖，本來就該手工排版。

**工具自動產出的依賴圖維持 Graphviz / Mermaid**（Fig.3 那類）。那是程式在跑的時候即時生成的，一次可能 50 個節點、每次結果都不同，不可能也不該用 draw.io 畫。

這條界線建議在會議上先講明，免得被理解成「整套繪圖換掉了」。

### Fig.3 一定要用 `DotEmitter` 產，不要重畫

Discord 上貼出去的圖走的是 `DotEmitter` → Graphviz 這條路。投影片裡的 Fig.3 如果改用
Mermaid 重畫，**資料一樣但畫出來的樣子不一樣**，看的人第一個反應就是「這跟你 demo 的
不是同一張？」。所以簡報用的 Fig.3 一律從 `.dot` 出：

```bash
dot -Tpng -Gdpi=180 docs/diagrams/fig3a-boa-layered.dot -o docs/diagrams/fig3a-boa-layered.png
```

`.dot` 檔跟 PNG 一起放在這裡，要重產隨時可以。

**關於 fig3b 少掉的 19 個節點**：Graphviz 的 `rank=same` 會把同一層排在同一條水平線
上、不換行，train-ticket 光「無相依」那層就有 19 個節點，整張圖被撐到 7657px 寬，中間
有邊的部分完全看不到。所以 fig3b 只畫有邊的 34 個，**完整版保留成 fig3c**。任何地方用
到 fig3b 都要寫明少了幾個、原圖在哪——可以排除，但不准靜默，跟覆蓋率那邊的規則一致。

## 怎麼開、怎麼改

三種都可以，檔案格式一樣：

- **VS Code**：裝 `hediet.vscode-drawio` 擴充，直接點開 `.drawio` 就是編輯器。
- **桌面版**：[draw.io Desktop](https://github.com/jgraph/drawio-desktop/releases)。
- **瀏覽器**：[app.diagrams.net](https://app.diagrams.net) → File → Open From → Device。

## 匯出（投影片 / 論文用）

repo 裡除了 `.drawio` 原始檔，另有兩份 **SVG**（`fig1-pipeline-overview.svg`、
`fig2-evidence-merge-chain.svg`），由 `drawio2svg.py` 從同一份 `.drawio` 直接轉出，
**不需要安裝 draw.io**：

```bash
python3 docs/diagrams/drawio2svg.py \
  docs/diagrams/fig1-pipeline-overview.drawio \
  docs/diagrams/fig1-pipeline-overview.svg
```

它不是通用的 mxGraph 轉換器，只涵蓋這兩張圖用到的圖形與樣式——目的是讓簡報和文件裡
的圖**直接來自那份會被編輯的原始檔**，而不是另外重畫一份會走樣的。改完 `.drawio`
記得重跑一次。

要 PNG（或要 draw.io 官方渲染的精確版本）仍然得用 draw.io 本身。在任一上述環境開啟後：

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
