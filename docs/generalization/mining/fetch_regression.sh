#!/bin/bash
# 把回歸用的 23 個開源系統取到論文用的固定 commit（只抓那一個 commit，不抓歷史）。
# 歸納用 7 個的 sha 來自 docs/experiments/2026-10-20/budget.md；MicroDepGraph 7 個來自
# docs/generalization/external-agreement.md；hold-out B/C/D 來自各自的 heads.tsv；
# train-ticket 沒有記錄 sha，用 master（最後一次 commit 是 2022-11-01，之後沒有變動）。
# 用法：bash fetch_regression.sh <目的目錄>
# 不要放在 /tmp：macOS 會把 /private/tmp 裡三天沒動的檔案刪掉、只留空目錄（2026-11-03 遇到）。
set -eu
DEST=${1:?usage: fetch_regression.sh <dest-dir>}
mkdir -p "$DEST"
fetch() {  # fetch <repo> <sha> <dir>
  local repo=$1 sha=$2 dir=$DEST/$3
  if [ -n "$(ls -A "$dir" 2>/dev/null | grep -v '^.git$')" ]; then echo "skip $3 (exists)"; return; fi
  mkdir -p "$dir"
  ( cd "$dir" && git init -q && (git remote add origin "https://github.com/$repo.git" 2>/dev/null || true) \
      && git fetch -q --depth 1 origin "$sha" && git checkout -q FETCH_HEAD \
      && echo "$3 @ $(git rev-parse --short HEAD)" )
}
# 歸納用 7 個（表 VII）
fetch GoogleCloudPlatform/bank-of-anthos            db35fea9fd090150e2398106aadb475576f80d94 bank-of-anthos
fetch ewolff/microservice-kubernetes                db3af1b3c933c48871fab74d45b466f2dfedb6a3 microservice-kubernetes
fetch SelimHorri/ecommerce-microservice-backend-app 695a6d49a8fb5559b025e22017df1ec657104309 ecommerce-microservice-backend-app
fetch sqshq/piggymetrics                            6bb2cf9ddbca980b664d3edbb6ff775d75369278 piggymetrics
fetch DescartesResearch/TeaStore                    34b37f7e7be433ce72d5f9455e66922a13116749 TeaStore
fetch GoogleCloudPlatform/microservices-demo        38e7348eb289eb5b87c0c6e8cb19ced0449dc389 microservices-demo
fetch instana/robot-shop                            55292e2199f2fb00a165b1f7d3045fe7f8922038 robot-shop
fetch FudanSELab/train-ticket                       313886e99befb94be6cd45f085c98e0019f59829 train-ticket
# MicroDepGraph（表 VI）
fetch spring-petclinic/spring-petclinic-microservices 1860fffc083d351da5a1bdf308402246c8c98269 external/spring-petclinic-microservices
fetch ewolff/microservice                           c445384002c912e196cf967cfb4d39b82867ba3c external/microservice
fetch jferrater/Tap-And-Eat-MicroServices           3ad20b8fa421dc837ef423270a9bf9ece1615a03 external/Tap-And-Eat-MicroServices
fetch yidongnan/spring-cloud-netflix-example        f0e57c3fe0e421a99ffd58601f88c93bc48432f3 external/spring-cloud-netflix-example
fetch zpng/spring-cloud-microservice-examples       6938297335e924f8066f5558b79ee82fa204c4ee external/spring-cloud-microservice-examples
fetch Microservice-API-Patterns/LakesideMutual      4fc6b430da8a8c5db9a8d5918117e3c7a6a89c6d external/LakesideMutual
fetch instana/robot-shop                            5939f27e27facc2f1a96661dc04a652c20a9ca4b external/robot-shop
# hold-out B／C／D（表 VIII）
fetch open-telemetry/opentelemetry-demo             ad06a6f9df40abde6c1f0c58b29b92f1076a74ec holdout/opentelemetry-demo
fetch macrozheng/mall-swarm                         04c442fe318356bae4445a109dde7af297ce1e84 holdout/mall-swarm
fetch delimitrou/DeathStarBench                     6ecb09706140f8730b5385c08f1386c654c3c526 holdout/DeathStarBench
fetch nashtech-garage/yas                           179e813568c345bad3fce985088b5535e57481aa holdout-c/yas
fetch aspnetrun/run-aspnetcore-microservices        e3549abf009c71e8c2550f292f3a8edf110550a1 holdout-c/run-aspnetcore-microservices
fetch kiali/demos                                   38ceb5ab6653a0a587673c9293918c6b9b8d6b86 holdout-d/kiali-demos
fetch apache/skywalking-showcase                    7d955bedc31d83eba1de9513680a45b2be3c8c5f holdout-d/skywalking-showcase
