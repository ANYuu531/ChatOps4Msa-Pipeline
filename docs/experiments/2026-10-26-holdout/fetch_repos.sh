#!/bin/bash
# 把 5 個 hold-out 系統取到固定的 commit（和寫參考邊集時同一版；sha 來自 docs/generalization/holdout{,-c}/heads.tsv）。
# 用法：bash fetch_repos.sh <目的目錄>   例如 bash docs/experiments/2026-10-26-holdout/fetch_repos.sh ~/Desktop/holdout-repos
set -eu
DEST=${1:?usage: fetch_repos.sh <dest-dir>}
mkdir -p "$DEST"
fetch() {  # fetch <repo> <sha> <dir>：只抓那一個 commit，不抓整段歷史
  local repo=$1 sha=$2 dir=$DEST/$3
  if [ -d "$dir" ]; then echo "skip $dir (exists)"; return; fi
  mkdir -p "$dir" && cd "$dir"
  git init -q && git remote add origin "https://github.com/$repo.git"
  git fetch -q --depth 1 origin "$sha" && git checkout -q FETCH_HEAD
  echo "$3 @ $(git rev-parse --short HEAD)"
}
fetch open-telemetry/opentelemetry-demo        ad06a6f9df40abde6c1f0c58b29b92f1076a74ec opentelemetry-demo
fetch macrozheng/mall-swarm                    04c442fe318356bae4445a109dde7af297ce1e84 mall-swarm
fetch delimitrou/DeathStarBench                6ecb09706140f8730b5385c08f1386c654c3c526 DeathStarBench
fetch nashtech-garage/yas                      179e813568c345bad3fce985088b5535e57481aa yas
fetch aspnetrun/run-aspnetcore-microservices   e3549abf009c71e8c2550f292f3a8edf110550a1 run-aspnetcore-microservices
# DeathStarBench 只用 hotelReservation/ 子目錄；腳本的 checkout 名寫成 DeathStarBench/hotelReservation，不用另外搬。
ls "$DEST"
