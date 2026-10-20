DEBUG: egress_edges_notes

# Egress (External Dependency) Edge Ledger

## Collection Status
- Query 1 (HTTP) series count: 5
- Query 2 (TCP) series count: 1
- Egress evidence usable: Yes

## Observed External Edges
### Edge: discovery-server -> config-server
- Observed via: HTTP (istio_requests_total)
- Count: 3
- Target identified: Yes
- Runtime observed: Yes

### Edge: api-gateway -> discovery-server
- Observed via: HTTP (istio_requests_total)
- Count: 8043
- Target identified: Yes
- Runtime observed: Yes

### Edge: vets-service -> discovery-server
- Observed via: HTTP (istio_requests_total)
- Count: 8043
- Target identified: Yes
- Runtime observed: Yes

### Edge: customers-service -> discovery-server
- Observed via: HTTP (istio_requests_total)
- Count: 8043
- Target identified: Yes
- Runtime observed: Yes

### Edge: visits-service -> discovery-server
- Observed via: HTTP (istio_requests_total)
- Count: 8043
- Target identified: Yes
- Runtime observed: Yes

### Edge: config-server -> (unidentified external target)
- Observed via: TCP only (istio_tcp_connections_opened_total)
- Count: 197
- Target identified: No (PassthroughCluster)
- Runtime observed: Yes

## Unidentified Egress
### Edge: config-server -> (unidentified external target)
- Count: 197
- Action: declare a ServiceEntry for the host so Istio can attribute it

## Notes
- HTTPS targets that do not have a matching ServiceEntry with the correct SNI host will only appear in the TCP query as opaque connections, potentially under-reporting HTTP-level request counts and details.
