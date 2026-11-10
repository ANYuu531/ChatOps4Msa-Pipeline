#!/bin/bash
# 機器 B：在報告 thread 裡加線的 6 句測試句（真的 OpenAI，走 thread 的回答路徑，只是不經 Discord）
# ＋語意路由校準（改圖例句有沒有誤擋正常問句）。
# 用法（在 repo 根目錄）：bash docs/experiments/2026-11-06-graph-edit/run-on-machine-b.sh
# 需要：mvn、git、src/main/resources/application.properties（有額度的 openai.api.key）。
set -u
D=docs/experiments/2026-11-06-graph-edit
LOG=$(mktemp -d "${TMPDIR:-/tmp}/graph-edit.XXXX")
command -v mvn >/dev/null || { echo "找不到 mvn"; exit 1; }
[ -f src/main/resources/application.properties ] || { echo "缺 src/main/resources/application.properties"; exit 1; }

echo "== 1/2 六句測試句（clone yas、跑靜態分析、逐句對話，約 3–5 分鐘）"
mvn -q clean test -Dtest=GraphEditLiveProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.live.repo=nashtech-garage/yas -Dprobe.live.out="$D/live-yas.md" > "$LOG/live.log" 2>&1
[ -f "$D/live-yas.md" ] || { echo "沒有產出，log 在 $LOG/live.log"; tail -30 "$LOG/live.log"; exit 1; }

echo "== 2/2 路由校準"
mvn -q test -Dqa.calibrate=true -Dtest=SemanticRouterCalibrationTest -Dsurefire.failIfNoSpecifiedTests=false > "$LOG/calibration.log" 2>&1
grep -E "^question|ok$|WRONG|unsure|veto|^accuracy" "$LOG/calibration.log" > "$D/router-calibration.txt"

echo
sed -n 3,12p "$D/live-yas.md"
echo
grep -E "^edit veto|^accuracy" "$D/router-calibration.txt"
echo
echo "完整對話：$D/live-yas.md；校準表：$D/router-calibration.txt；log：$LOG"
echo "傳回來：git add $D && git commit -m 'graph edit: machine B live run' && git push"
echo "（push 不行的話，把上面兩段輸出貼給我就好）"
