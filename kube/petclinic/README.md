# spring-petclinic-microservices on Kubernetes + Istio

The analysis target for the dependency-analysis pipeline. This is the SAME variant
the code-extraction ground truth uses (`spring-petclinic-microservices`, with
config-server + discovery-server/Eureka), so the runtime graph and the
code-extracted graph share one vocabulary of node names.

Deployed on the cluster machine (machine A). Nothing here runs on the ChatOps4Msa
machine.

## Workloads → graph nodes

Istio names each edge by workload, so the Deployment names ARE the node names:

| workload | port | role |
|---|---|---|
| `config-server` | 8888 | Spring Cloud Config (clones config from GitHub) |
| `discovery-server` | 8761 | Eureka registry |
| `api-gateway` | 8080 | Spring Cloud Gateway — the entry point |
| `customers-service` | 8081 | business service |
| `vets-service` | 8083 | business service |
| `visits-service` | 8082 | business service |

## Deploy (on machine A, or with `KUBECONFIG` pointed at the cluster)

Replace the old target first, then bring petclinic up in order — config-server and
discovery-server MUST be Ready before the business services start (they fetch
config and register on startup).

```bash
# 0. remove the previous analysis target (frees memory + sidecars)
kubectl delete namespace sock-shop --ignore-not-found

# 1. namespace with sidecar injection
kubectl apply -f 00-namespace.yaml

# 2. platform services — wait until BOTH are Ready
kubectl apply -f 10-config-discovery.yaml
kubectl -n petclinic rollout status deploy/config-server --timeout=300s
kubectl -n petclinic rollout status deploy/discovery-server --timeout=300s

# 3. business services — wait until Ready
kubectl apply -f 20-services.yaml
kubectl -n petclinic rollout status deploy/api-gateway --timeout=300s

# 4. ingress
kubectl apply -f 30-gateway.yaml
```

## Verify (the gate before running the analysis)

```bash
# every pod should be 2/2 (app + Envoy sidecar) — 2/2 is what proves injection worked
kubectl -n petclinic get pods

# the entry point answers through the ingress gateway
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.100.106/

# drive a little traffic so Istio records the deep edges, then check the metric
#   (api-gateway -> customers/vets/visits shows up only once a request crosses it)
curl -s http://192.168.100.106/api/customer/owners >/dev/null
curl -s http://192.168.100.106/api/vet/vets        >/dev/null
curl -s 'http://192.168.100.106:30090/api/v1/query?query=sum%20by(source_workload,destination_workload)(istio_requests_total)' | head -c 400
```

If the last query lists `api-gateway -> customers-service` (etc.), the runtime edge
source is live and the pipeline can run.

## Run the analysis (from Discord, machine B side)

```
repo_name = spring-petclinic/spring-petclinic-microservices
namespace = petclinic
entry_url = http://192.168.100.106/
auth_hint = none
```

## Notes

- **config-server needs outbound internet** — it clones its config from GitHub on
  startup. If it never becomes Ready, check the cluster machine can reach github.com.
- Images are the community builds `springcommunity/spring-petclinic-*:latest`.
  Pin a version tag if you want reproducibility.
- Databases: the services run on in-memory HSQLDB by default (no MySQL deployed),
  so the graph's DB dependency edges come from code/config extraction, not runtime —
  which is exactly the point of the dependency (not traffic) graph.
- Memory: 6 JVMs + 6 sidecars ≈ 7 GB with the limits set here; fine on the 31 GB
  machine. Do NOT remove the sidecars from the business services — the sidecar is
  how Istio observes the edges.
