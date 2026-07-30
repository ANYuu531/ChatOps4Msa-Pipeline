#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# de-eureka.sh — turn spring-petclinic-microservices into a k8s-native fork.
#
# Run this at the ROOT of a fresh checkout of your fork of
#   github.com/spring-petclinic/spring-petclinic-microservices
#
# It removes Netflix Eureka / Spring Cloud discovery entirely and rewires the
# api-gateway to reach the business services over Kubernetes Service DNS
# (http://<service>:<port>) instead of lb://<service>. Idempotent-ish: it is
# meant to run once on the pristine upstream tree.
#
# Needs: perl, python3, git (all present on machine A).
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"
test -f pom.xml -a -d spring-petclinic-api-gateway \
  || { echo "ERROR: run me at the petclinic repo root"; exit 1; }

echo "==> 1/8  gateway routes: lb:// -> http://<service>:<k8s-port>"
GW_YML=spring-petclinic-api-gateway/src/main/resources/application.yml
perl -i -pe '
  s{lb://vets-service}{http://vets-service:8083};
  s{lb://visits-service}{http://visits-service:8082};
  s{lb://customers-service}{http://customers-service:8081};
  s{lb://genai-service}{http://genai-service:8084};
' "$GW_YML"

echo "==> 2/8  api-gateway aggregation clients: add k8s ports (no more lb resolution)"
perl -i -pe 's{http://customers-service/owners}{http://customers-service:8081/owners}' \
  spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/CustomersServiceClient.java
perl -i -pe 's{http://visits-service/}{http://visits-service:8082/}' \
  spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/VisitsServiceClient.java

echo "==> 3/8  drop @LoadBalanced (no load-balancer client without discovery)"
perl -i -ne 'print unless /^\s*\@LoadBalanced\s*$/ or /import org\.springframework\.cloud\.client\.loadbalancer\.LoadBalanced;/' \
  spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/ApiGatewayApplication.java

echo "==> 4/8  remove @EnableDiscoveryClient annotation + import from every service"
grep -rl "EnableDiscoveryClient" --include="*.java" . | while read -r f; do
  perl -i -ne 'print unless /EnableDiscoveryClient/' "$f"
done

echo "==> 5/8  remove the spring-cloud-starter-netflix-eureka-client dependency block"
grep -rl "spring-cloud-starter-netflix-eureka-client" --include=pom.xml . | while read -r f; do
  perl -0777 -i -pe 's{\n[ \t]*<dependency>\s*<groupId>org\.springframework\.cloud</groupId>\s*<artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>\s*</dependency>}{}g' "$f"
done

echo "==> 6/8  delete the discovery-server module"
perl -i -ne 'print unless m{<module>spring-petclinic-discovery-server</module>}' pom.xml
git rm -rq spring-petclinic-discovery-server 2>/dev/null || rm -rf spring-petclinic-discovery-server

echo "==> 7/8  docker-compose: remove discovery-server service + its depends_on edges"
python3 - "$@" <<'PY'
import io, re
p = "docker-compose.yml"
src = open(p, encoding="utf-8").read().splitlines(keepends=True)
out, skip_service = [], False
i = 0
while i < len(src):
    line = src[i]
    # top-level service block "  discovery-server:" -> skip until next 2-space service
    if re.match(r'^  discovery-server:\s*$', line):
        skip_service = True
        i += 1
        continue
    if skip_service:
        if re.match(r'^  \S', line):          # next top-level service starts
            skip_service = False
        else:
            i += 1
            continue
    # depends_on entry "      discovery-server:" + following condition line
    if re.match(r'^      discovery-server:\s*$', line):
        i += 1
        if i < len(src) and 'condition:' in src[i]:
            i += 1
        continue
    out.append(line)
    i += 1
open(p, "w", encoding="utf-8").write("".join(out))
PY

echo "==> 8/8  done. Review with: git diff --stat && git diff"
