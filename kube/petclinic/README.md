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
| `config-server` | 8888 | Spring Cloud Config (clones config from GitHub) | upstream (Docker Hub) |
| `api-gateway` | 8080 | Spring Cloud Gateway — the entry point | **fork (local)** |
| `customers-service` | 8081 | business service | **fork (local)** |
| `vets-service` | 8083 | business service | **fork (local)** |
| `visits-service` | 8082 | business service | **fork (local)** |

There is no `discovery-server` any more.

## Build the fork images (on machine A — k3s uses containerd, not the Docker daemon)

k3s does **not** run pods from the Docker daemon's image store. A plain
`docker build` produces images the cluster cannot see (`ErrImageNeverPull`).
Build, then import each image into k3s' containerd:

```bash
# in your fork checkout (after running fork/de-eureka.sh — see fork/README.md)
./mvnw clean install -P buildDocker -Ddocker.image.prefix=petclinic-k8s -DskipTests

# import the four app images into k3s' containerd (config-server stays upstream)
for svc in api-gateway customers-service vets-service visits-service; do
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

# 3. business services — wait until Ready
kubectl apply -f 20-services.yaml
kubectl -n petclinic rollout status deploy/api-gateway --timeout=300s

# 4. ingress
kubectl apply -f 30-gateway.yaml

# 5. egress attribution — name config-server's github.com pull so Istio can see it
kubectl apply -f 40-egress-serviceentry.yaml
kubectl -n petclinic rollout restart deploy/config-server   # fresh clone -> attributed connection
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
```

If the last query lists `api-gateway -> customers-service` (etc.), the runtime edge
source is live and the pipeline can run. (Unlike the Eureka version, these edges
appear as soon as traffic crosses the gateway — no registry to warm up first.)

## Run the analysis (from Discord, machine B side)

Point the pipeline at YOUR fork (the upstream repo still has Eureka in its code):

```
repo_name = <your-github-user>/spring-petclinic-microservices
namespace = petclinic
entry_url = http://192.168.100.106/
auth_hint = none
```

## Notes

- **config-server is unchanged from upstream** — the fork does not touch it, so
  it is pulled from Docker Hub and still needs outbound internet to clone its
  config from GitHub. Only the four app images are local/forked.
- **config repo is unchanged too.** Its leftover `eureka.*` properties (and the
  services' test-only `eureka.client.enabled: false`) are inert once the
  eureka-client dependency is gone — nothing binds them.
- Databases: services run on in-memory HSQLDB by default (no MySQL deployed), so
  the graph's DB dependency edges come from code/config extraction, not runtime —
  which is exactly the point of the dependency (not traffic) graph.
- Do NOT remove the sidecars from the business services — the sidecar is how
  Istio observes the edges.
