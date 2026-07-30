# Producing the k8s-native petclinic fork (Eureka removed)

`de-eureka.sh` turns a pristine checkout of `spring-petclinic-microservices`
into a Kubernetes-native fork: no Netflix Eureka, no discovery-server, no
`lb://`. The api-gateway reaches the business services over Kubernetes Service
DNS (`http://<service>:<port>`) instead.

## Why (not just "make the demo cleaner")

The old deployment worked around Eureka by pulling port 8761 out of the Istio
mesh (Netflix's registry-fetch transport got a 403 from the inbound sidecar).
That workaround is real but has a cost, and it is a *deployment patch* — the
**code the analysis tool clones still declared Eureka**, so the dependency graph
would show a `discovery-server` node and control-plane edges no matter what the
cluster did.

Removing Eureka *in the code* makes the two agree:

- **Runtime is honest.** With `lb://`, Eureka resolved services to raw pod IPs,
  bypassing the Service VIP → Istio dropped those to passthrough (the 403 /
  coverage mess). Plain `http://customers-service:8081` goes through the meshed
  ClusterIP, so Istio observes every gateway→service edge immediately.
- **The graph is clean.** No discovery-server node, no compose `depends_on`
  control-plane edges, no `lb://` in code — the tool extracts a k8s-native app.
- **It generalises the story.** The tool is shown to handle service discovery
  that is *not* Eureka/`lb://` — the same direction as the multi-language /
  multi-ORM generalisation work.

## What the script changes (all in the code repo — the config repo is untouched)

| # | change | files |
|---|---|---|
| 1 | gateway routes `lb://x` → `http://x:<k8s-port>` | api-gateway `application.yml` |
| 2 | aggregation clients get explicit k8s ports | `CustomersServiceClient.java`, `VisitsServiceClient.java` |
| 3 | drop `@LoadBalanced` (no LB client without discovery) | `ApiGatewayApplication.java` |
| 4 | drop `@EnableDiscoveryClient` + import | every service `*Application.java` |
| 5 | remove `spring-cloud-starter-netflix-eureka-client` | every service `pom.xml` |
| 6 | delete the `discovery-server` module | parent `pom.xml` + module dir |
| 7 | remove discovery-server service + its `depends_on` | `docker-compose.yml` |

Ports used for the DNS URLs: customers 8081, visits 8082, vets 8083, genai 8084
(genai is not deployed on the cluster but is kept consistent).

Leftover `eureka.*` keys in the config repo and the services' test-only
`eureka.client.enabled: false` are inert once the dependency is gone — nothing
binds them, so the script leaves them alone.

## Workflow

```bash
# 1. Fork spring-petclinic/spring-petclinic-microservices on GitHub to your account,
#    then clone YOUR fork on machine A:
git clone https://github.com/<your-user>/spring-petclinic-microservices.git
cd spring-petclinic-microservices

# 2. Run the de-eureka transform (copy this script in first), review, commit, push:
cp <path>/de-eureka.sh .
bash de-eureka.sh
git diff --stat            # expect ~17 files changed + discovery-server module deleted
git commit -am "k8s-native: remove Eureka, route business services via Service DNS"
git push                   # the pipeline clones the fork by repo_name, so this must be on GitHub

# 3. Build + import the images, then deploy — see ../README.md
```

The script needs `perl`, `python3`, and `git` (all present on machine A) and is
meant to run once on the pristine upstream tree. It was validated against the
current upstream `main`; if upstream drifts, re-check `git diff` before pushing.
