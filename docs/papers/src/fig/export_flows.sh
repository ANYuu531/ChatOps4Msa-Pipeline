#!/bin/sh
# 把 AsianPLoP.drawio 的四個分頁（① ～ ④）匯出成論文用的 p_flow1.png ～ p_flow4.png
# （需要 draw.io Desktop：brew install --cask drawio）。
# 用法：sh docs/papers/src/fig/export_flows.sh        # 全部
#       sh docs/papers/src/fig/export_flows.sh 2      # 只匯出第 2 頁（②）
set -e
cd "$(dirname "$0")"
pages=${*:-1 2 3 4}
for p in $pages; do
  drawio -x -f png -p "$p" -s 3 -b 10 --theme light -o "p_flow$p.png" AsianPLoP.drawio >/dev/null 2>&1
  echo "wrote p_flow$p.png"
done
