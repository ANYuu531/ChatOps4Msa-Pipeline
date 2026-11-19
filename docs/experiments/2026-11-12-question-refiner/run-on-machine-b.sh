#!/bin/bash
# 機器 B：問句修飾（2026-11-12 老師反饋）在 102 句 hold-out 上的實驗，真的 OpenAI。
#   第一部分：路由沒把握的句子 → ①planner（現況）②改寫後重新路由（老師的提議＋守門）③模型直接選意圖（不改寫）
#   第二部分：有把握但剛好過門檻的句子要不要再問一次——帶寬掃描（threshold-design.md §3.8），同一次跑、離線重播
# 用法（在 repo 根目錄）：bash docs/experiments/2026-11-12-question-refiner/run-on-machine-b.sh
# 需要：mvn、git、src/main/resources/application.properties（有額度的 openai.api.key、openai.api.model）。
# 費用：102 句 × 2 次小呼叫（修飾 + planner）＋ 約 100 句改寫的 embedding；embedding 與 chat 都有快取，重跑免費。
set -u
D=docs/experiments/2026-11-12-question-refiner
KEEP="$HOME/.depweaver-qa-cache"
LOG=$(mktemp -d "${TMPDIR:-/tmp}/refiner.XXXX")
command -v mvn >/dev/null || { echo "找不到 mvn"; exit 1; }
[ -f src/main/resources/application.properties ] || { echo "缺 src/main/resources/application.properties"; exit 1; }

# 快取在 target/ 下，mvn clean 會刪掉：先收起來，編譯完再放回去。
mkdir -p "$KEEP"
[ -f target/qa-calibration/embeddings-cache.json ] && cp target/qa-calibration/embeddings-cache.json "$KEEP/"
[ -f target/qa-calibration/chat-cache.json ] && cp target/qa-calibration/chat-cache.json "$KEEP/"
if [ -d target ] && [ ! -w target ] || find target -not -user "$(id -un)" 2>/dev/null | grep -q .; then
  echo "target/ 裡有不是你的檔案（通常是之前用 sudo 或 docker 跑過），mvn clean 會刪不掉。先執行："
  echo "  sudo chown -R \"\$USER\":\"\$USER\" target"
  exit 1
fi
mvn -q clean compile test-compile > "$LOG/compile.log" 2>&1 || { echo "編譯失敗，log 在 $LOG/compile.log"; tail -30 "$LOG/compile.log"; exit 1; }
mkdir -p target/qa-calibration
cp "$KEEP"/*.json target/qa-calibration/ 2>/dev/null

echo "== 1/2 問句修飾 hold-out（約 2–4 分鐘）"
mvn -q test -Dqa.calibrate=true -Dtest=QuestionRefinerHoldoutTest -Dsurefire.failIfNoSpecifiedTests=false > "$LOG/refine.log" 2>&1
[ -f target/qa-calibration/refine-summary.md ] || { echo "沒有產出，log 在 $LOG/refine.log"; tail -30 "$LOG/refine.log"; exit 1; }
cp target/qa-calibration/refine-summary.md "$D/refine-summary.md"
cp target/qa-calibration/refine-per-question.csv "$D/refine-per-question.csv"
cp target/qa-calibration/refine-band.csv "$D/refine-band.csv"

echo "== 2/2 路由校準 33 句（確認修飾沒有碰到有把握的那些）"
mvn -q test -Dqa.calibrate=true -Dtest=SemanticRouterCalibrationTest -Dsurefire.failIfNoSpecifiedTests=false > "$LOG/calibration.log" 2>&1
grep -E "^question|ok$|WRONG|unsure|veto|^accuracy" "$LOG/calibration.log" > "$D/router-calibration.txt"

cp target/qa-calibration/*.json "$KEEP/" 2>/dev/null
echo
sed -n '1,/^## Part 1 — the deferred rows/p' "$D/refine-summary.md"
sed -n '/^## Part 1 — the deferred rows/,/^### Why/p' "$D/refine-summary.md" | head -14
grep -E "^\*\*Selected" "$D/refine-summary.md"
echo
grep -E "^accuracy" "$D/router-calibration.txt"
echo
echo "完整結果：$D/refine-summary.md、$D/refine-per-question.csv、$D/refine-band.csv；log：$LOG"
echo "傳回來：git add $D && git commit -m 'question refiner: machine B hold-out run' && git push"
echo "（push 不行的話，把 refine-summary.md 貼給我就好）"
