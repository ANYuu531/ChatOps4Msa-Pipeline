## Updated Istio Runtime-Observed Edge Ledger
**Namespace:** `petclinic`

# Runtime-Observed Edge Ledger

## Collection Status
- Query returned series count: 10
- Traffic evidence usable: Yes
- Note: Prometheus responded successfully with runtime observed edges for namespace "petclinic".

## Observed Business Edges
### Edge: discovery-server -> config-server
- Runtime observed: Yes
- Total observed request count: 2
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: api-gateway -> config-server
- Runtime observed: Yes
- Total observed request count: 2
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: visits-service -> config-server
- Runtime observed: Yes
- Total observed request count: 2
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: api-gateway -> discovery-server
- Runtime observed: Yes
- Total observed request count: 8050
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: customers-service -> config-server
- Runtime observed: Yes
- Total observed request count: 2
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: vets-service -> config-server
- Runtime observed: Yes
- Total observed request count: 2
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: customers-service -> discovery-server
- Runtime observed: Yes
- Total observed request count: 8049
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: vets-service -> discovery-server
- Runtime observed: Yes
- Total observed request count: 8049
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: visits-service -> discovery-server
- Runtime observed: Yes
- Total observed request count: 8050
- Entry edge (from ingress gateway): No
- Evidence: istio_requests_total (reporter=source), cumulative counter

### Edge: istio-ingressgateway -> api-gateway
- Runtime observed: Yes
- Total observed request count: 121
- Entry edge (from ingress gateway): Yes
- Evidence: istio_requests_total (reporter=source), cumulative counter

## Excluded / Non-Business Edges
- None. All edges have valid source and destination workloads and are not telemetry or infrastructure components (except istio-ingressgateway which is allowed as a legitimate entry edge).

## Runtime Coverage Notes
- Workloads that appear ONLY as a destination (never a source): config-server
- Workloads that appear ONLY as a source (never a destination): vets-service, visits-service, customers-service, istio-ingressgateway
- One-line caveat: these are all-time observed edges; absence of an edge does not prove the call never happens, only that it was not observed.
