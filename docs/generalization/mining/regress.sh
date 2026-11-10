#!/bin/bash
# 23 個回歸系統（＋AICS，若有 checkout）逐邊比對已 commit 的摘要：任何一條邊不同就印出來。
# 用法：bash docs/generalization/mining/regress.sh [repos 目錄，預設 ~/Documents/depweaver-eval/repos] [AICS 目錄]
# 在 repo 根目錄執行。需要先 bash fetch_regression.sh <repos 目錄>。
set -u
R=${1:-$HOME/Documents/depweaver-eval/repos}
A=${2:-$HOME/Documents/aics-eval}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/regress.XXXX")
G=docs/generalization
cat > "$OUT/regress.tsv" <<TSV
bank-of-anthos	$R/bank-of-anthos	$G/bank-of-anthos.summary.md
ewolff-k8s	$R/microservice-kubernetes	$G/ewolff-k8s.summary.md
ecommerce	$R/ecommerce-microservice-backend-app	$G/ecommerce.summary.md
piggymetrics	$R/piggymetrics	$G/piggymetrics.summary.md
teastore	$R/TeaStore	$G/teastore.summary.md
online-boutique	$R/microservices-demo	$G/online-boutique.summary.md
robot-shop	$R/robot-shop	$G/robot-shop.summary.md
train-ticket	$R/train-ticket	$G/train-ticket.summary.md
spring-petclinic	$R/external/spring-petclinic-microservices	$G/external/spring-petclinic.summary.md
microservices-book	$R/external/microservice	$G/external/microservices-book.summary.md
tap-and-eat	$R/external/Tap-And-Eat-MicroServices	$G/external/tap-and-eat.summary.md
spring-cloud-netflix	$R/external/spring-cloud-netflix-example	$G/external/spring-cloud-netflix.summary.md
spring-cloud-microservice	$R/external/spring-cloud-microservice-examples	$G/external/spring-cloud-microservice.summary.md
lakeside-mutual	$R/external/LakesideMutual	$G/external/lakeside-mutual.summary.md
robot-shop-2019	$R/external/robot-shop	$G/external/robot-shop.summary.md
opentelemetry-demo	$R/holdout/opentelemetry-demo	$G/holdout/opentelemetry-demo.r8.summary.md
mall-swarm	$R/holdout/mall-swarm	$G/holdout/mall-swarm.r8.summary.md
hotel-reservation	$R/holdout/DeathStarBench/hotelReservation	$G/holdout/hotel-reservation.r8.summary.md
yas	$R/holdout-c/yas	$G/holdout-c/yas.r9.summary.md
run-aspnetcore-microservices	$R/holdout-c/run-aspnetcore-microservices	$G/holdout-c/run-aspnetcore-microservices.r9.summary.md
travels	$R/holdout-d/kiali-demos/travels	$G/holdout-d/travels.r8.summary.md
skywalking-showcase	$R/holdout-d/skywalking-showcase	$G/holdout-d/skywalking-showcase.r8.summary.md
TSV
[ -d "$A/repos" ] && printf 'aics\t%s\t%s\n' "$A/repos" "$A/out/2026-11-04-r8/aics.summary.md" >> "$OUT/regress.tsv"
cut -f1,2 "$OUT/regress.tsv" > "$OUT/list.tsv"
mvn -o -q test -Dtest=MiningProbeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dprobe.list="$OUT/list.tsv" -Dprobe.outdir="$OUT/out" > "$OUT/mvn.log" 2>&1
if [ "$(wc -l < "$OUT/out/failures.tsv")" -gt 1 ]; then echo "PROBE FAILURES:"; tail -n +2 "$OUT/out/failures.tsv" | cut -c1-200; fi
edges() { awk '/^## edges/{f=1;next} /^## /{f=0} f&&/^- /{print $2,$3,$4}' "$1" | sort; }
same=0; diffs=0
while IFS=$'\t' read -r n p base; do
  edges "$OUT/out/$n.summary.md" > "$OUT/a"; edges "$base" > "$OUT/b"
  if cmp -s "$OUT/a" "$OUT/b"; then same=$((same+1)); else diffs=$((diffs+1)); echo "DIFF $n"; diff "$OUT/b" "$OUT/a" | grep '^[<>]'; fi
done < "$OUT/regress.tsv"
echo "same $same, different $diffs   (outputs in $OUT/out)"
