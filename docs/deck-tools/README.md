# 簡報工具

- `build_deck.py`：把一份「內容原始檔」組成單一檔案的 HTML 簡報。原始檔只寫 slide，資產與圖表用佔位符
  （`{{LOGO}}` `{{CAT}}` `{{WAVE}}` `{{CHART:<name>}}`），所以要編輯的檔案是幾十 KB 而不是近 1 MB。
  曲線圖以 `<svg>` 原文內嵌（不是 base64），列印成 PDF 仍是向量、會跟著版面縮放。
  ```
  python3 docs/deck-tools/build_deck.py docs/deck-tools/<name>.body.html docs/<name>.html
  ```
  現成範例：`deck-2026-09-25.body.html`（12 頁，上次反饋五點）。
  **改簡報請改 `.body.html` 再重跑**，不要直接改產出的 HTML。

- `pdf_to_pptx.py`：把列印好的 PDF 轉成 `.pptx`，**一頁一張全出血圖片**，給 Google Slides 用。
  版面 100% 等於 PDF，不會跑掉；代價是**文字不可編輯**（連結也會失效，連結留在 HTML 與 PDF）。
  逐頁講稿會自動放進每張投影片的**備忘稿**。
  ```
  python3 docs/deck-tools/pdf_to_pptx.py ~/Downloads/deck.pdf ~/Downloads/deck.pptx docs/meeting-script-<date>.md
  ```
  需要 poppler 的 `pdftoppm`（`brew install poppler`）。講稿的章節標題要是 `## P<n> · 標題` 才對得上頁碼。

- `build_pptx.py`：用 python-pptx 產出 SOSEL 樣式的**可編輯** `.pptx`（內容寫死在程式裡，換主題要改程式）。
  內容目前寫死為「報告問答」那份 15 頁；換主題時複製這支改內容區塊即可，版型函式（`frame`、`table`、`box`、`codebox`、`tag`）不用動。
  ```
  python3 docs/deck-tools/build_pptx.py ~/Downloads/xxx.pptx
  ```
- `assets.json`：實驗室 logo、封面貓咪、波浪底圖（base64），從 2026-06-30 的 pptx PDF 抽出。
- HTML 版簡報用 Chrome 直接列印成向量 PDF：
  ```
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu \
    --no-pdf-header-footer --virtual-time-budget=10000 --print-to-pdf=out.pdf file:///path/to/deck.html
  ```
  樣式裡要有 `@page { size: 1280px 720px; margin: 0 }`，否則 Chrome 會印成信紙直式、投影片只佔上半頁
  （`deck-template.html` 原本沒有這條，2026-09-22 補上）。

- **檢查排版**：中文字寬與版面高度無法用肉眼從原始碼判斷，一定要印出來逐頁看。
  ```
  pdftoppm -png -r 72 out.pdf pg    # 產生 pg-01.png … 再逐張檢視
  ```
  最常見的兩種問題：內容超出 `.bd` 被下緣切掉、以及反過來下方留白三四成。
