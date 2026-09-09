# 簡報工具

- `build_pptx.py`：用 python-pptx 產出 SOSEL 樣式的可編輯 `.pptx`（可直接匯入 Google Slides）。
  內容目前寫死為「報告問答」那份 15 頁；換主題時複製這支改內容區塊即可，版型函式（`frame`、`table`、`box`、`codebox`、`tag`）不用動。
  ```
  python3 docs/deck-tools/build_pptx.py ~/Downloads/xxx.pptx
  ```
- `assets.json`：實驗室 logo、封面貓咪、波浪底圖（base64），從 2026-06-30 的 pptx PDF 抽出。
- HTML 版簡報 `docs/report-qa-deck.html` 用 Chrome 直接列印成向量 PDF：
  ```
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu \
    --no-pdf-header-footer --virtual-time-budget=8000 --print-to-pdf=out.pdf file:///path/to/deck-print.html
  ```
