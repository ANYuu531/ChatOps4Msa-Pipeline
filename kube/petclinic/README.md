# spring-petclinic-microservices on Kubernetes + Istio (k8s-native fork)

The analysis target for the dependency-analysis pipeline. This is a **fork** of
`spring-petclinic-microservices` with Netflix Eureka removed — the api-gateway
reaches the business services over Kubernetes Service DNS
(`http://customers-service:8081` …) instead of `lb://` + a discovery-server.
See [fork/README.md](fork/README.md) for how the fork is produced (one script)
and why.

Because service discovery is now the mesh/Service layer itself, the runtime
graph and the code-extracted graph agree: **no discovery-server node, no
control-plane edges, no `lb://`**. The old Eureka workaround (taking port 8761
out of the mesh, registering by Service name) is gone with the problem.

Deployed on the cluster machine (machine A). Nothing here runs on the ChatOps4Msa
machine.

## Workloads → graph nodes

Istio names each edge by workload, so the Deployment names ARE the node names:

| workload | port | role | image |
|---|---|---|---|
| `config-server` | 8888 | Spring Cloud Config (clones config from GitHub) | **fork (local)** |
| `api-gateway` | 8080 | Spring Cloud Gateway — the entry point | **fork (local)** |
| `customers-service` | 8081 | business service | **fork (local)** |
| `vets-service` | 8083 | business service | **fork (local)** |
| `visits-service` | 8082 | business service | **fork (local)** |
| `mysql` | 3306 | the database the three data services really use | `mysql:8.0` |

There is no `discovery-server` any more.

## Build the fork images (on machine A — k3s uses containerd, not the Docker daemon)

k3s does **not** run pods from the Docker daemon's image store. A plain
`docker build` produces images the cluster cannot see (`ErrImageNeverPull`).
Build, then import each image into k3s' containerd:

```bash
# in your fork checkout (after running fork/de-eureka.sh — see fork/README.md).
# Needs a JDK 17 (the v3.4.1 tree targets Java 17; older JDKs fail the enforcer).
./mvnw clean install -P buildDocker -Ddocker.image.prefix=petclinic-k8s -DskipTests

# import the five images this cluster runs into k3s' containerd
for svc in config-server api-gateway customers-service vets-service visits-service; do
  docker save petclinic-k8s/spring-petclinic-$svc:latest \
    | sudo k3s ctr images import -
done

# confirm they are in containerd
sudo k3s ctr images ls | grep petclinic-k8s
```

`-Ddocker.image.prefix=petclinic-k8s` keeps the fork images from colliding with
the upstream `springcommunity/…` builds; `imagePullPolicy: Never` in
20-services.yaml guarantees k3s uses the imported build and never pulls the
Eureka one from Docker Hub.

## Deploy (on machine A, or with `KUBECONFIG` pointed at the cluster)

```bash
# 0. remove the previous analysis target (frees memory + sidecars)
kubectl delete namespace petclinic --ignore-not-found   # if re-deploying
# (or: kubectl delete namespace sock-shop --ignore-not-found)

# 1. namespace with sidecar injection
kubectl apply -f 00-namespace.yaml

# 2. platform service — wait until Ready
kubectl apply -f 10-config-server.yaml
kubectl -n petclinic rollout status deploy/config-server --timeout=300s

# 3. the database — MUST be Ready BEFORE the services start.
#    A connection pool is built once, at service startup: if MySQL is not up yet, the
#    services retry against a dead host and the sidecar may never attribute the
#    connection, so the db edge silently never appears. See 15-mysql.yaml.
kubectl apply -f 15-mysql.yaml
kubectl -n petclinic rollout status deploy/mysql --timeout=300s

# 4. business services — wait until Ready
kubectl apply -f 20-services.yaml
kubectl -n petclinic rollout status deploy/api-gateway --timeout=300s

# 5. ingress
kubectl apply -f 30-gateway.yaml

# 6. egress attribution — name config-server's github.com pull so Istio can see it
kubectl apply -f 40-egress-serviceentry.yaml
kubectl -n petclinic rollout restart deploy/config-server   # fresh clone -> attributed connection
```

If the services were already running before MySQL existed, restart them so their
connection pools are built (and observed) against the live database:

```bash
kubectl -n petclinic rollout restart deploy/customers-service deploy/vets-service deploy/visits-service
```

## Verify (the gate before running the analysis)

```bash
# every pod should be 2/2 (app + Envoy sidecar) — 2/2 is what proves injection worked
kubectl -n petclinic get pods

# the entry point answers through the ingress gateway
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.100.106/

# business endpoints go straight through the gateway to the services (k8s DNS)
curl -s http://192.168.100.106/api/customer/owners >/dev/null
curl -s http://192.168.100.106/api/vet/vets        >/dev/null
curl -s 'http://192.168.100.106:30090/api/v1/query?query=sum%20by(source_workload,destination_workload)(istio_requests_total)' | head -c 400

# the DB edges live in the TCP metric, NOT in istio_requests_total (Istio only parses
# HTTP/gRPC). This is the query that proves customers/vets/visits -> mysql is observable:
curl -s -G 'http://192.168.100.106:30090/api/v1/query' \
  --data-urlencode 'query=sum by(source_workload,destination_workload,destination_service_name)(istio_tcp_connections_opened_total{reporter="source",source_workload_namespace="petclinic"})' | head -c 600

# and that the services really are on MySQL rather than the default in-memory HSQLDB
kubectl -n petclinic exec deploy/mysql -c mysql -- \
  mysql -upetclinic -ppetclinic -e 'use petclinic; show tables;'
```

If the first query lists `api-gateway -> customers-service` (etc.), the runtime edge
source is live and the pipeline can run. (Unlike the Eureka version, these edges
appear as soon as traffic crosses the gateway — no registry to warm up first.)

If the second lists `customers-service -> mysql` with a non-zero count, the data-layer
edges will render solid instead of dashed. If it is EMPTY but the services are healthy,
the usual cause is ordering: the pools were opened before MySQL was Ready — restart the
three services (above) and re-query.

## Run the analysis (from Discord, machine B side)

Point the pipeline at YOUR fork (the upstream repo still has Eureka in its code):

```
repo_name = <your-github-user>/spring-petclinic-microservices
namespace = petclinic
entry_url = http://192.168.100.106/
auth_hint = none
```

## Notes

- **config-server is code-unchanged** but is still built+imported locally from the
  same v3.4.1 tree (imagePullPolicy: Never), so all five services are one version.
  It still needs outbound internet at runtime to clone its config from GitHub.
- **config repo is unchanged too.** Its leftover `eureka.*` properties (and the
  services' test-only `eureka.client.enabled: false`) are inert once the
  eureka-client dependency is gone — nothing binds them.
- **Databases: a real MySQL is now deployed** (`15-mysql.yaml`), and the three data
  services run with `SPRING_PROFILES_ACTIVE=docker,mysql` instead of the default
  in-memory HSQLDB. Before this, a db edge could only come from code/config
  extraction — correct, but permanently dashed, because there was no database in the
  mesh to observe. Now the same edge is confirmed on the wire via
  `istio_tcp_connections_opened_total` (Istio emits no `istio_requests_total` for a
  non-HTTP protocol), so it renders solid.
  - The db edges are reported as their OWN coverage number, not folded into the
    business service→service ratio: a db connection is not crossed by a user journey,
    so mixing them would change what that percentage means.
  - If the `mysql` profile does not pick the env up (the datasource lives in the
    unchanged config repo, whose `mysql` profile is expected to read
    `MYSQL_URL`/`MYSQL_USER`/`MYSQL_PASS`), the fallback is to set
    `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` plus
    `SPRING_CLOUD_CONFIG_OVERRIDE_NONE=true` — Spring Cloud Config otherwise
    overrides environment variables by default.
- Do NOT remove the sidecars from the business services — the sidecar is how
  Istio observes the edges.
