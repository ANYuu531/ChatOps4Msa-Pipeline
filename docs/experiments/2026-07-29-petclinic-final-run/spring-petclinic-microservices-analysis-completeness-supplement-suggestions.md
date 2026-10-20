## Analysis Completeness & Supplement Suggestions
**Namespace:** `petclinic`

### Gap: Runtime Observed Evidence for Synchronous Calls from API Gateway and Other Services
- What is missing: Despite high-confidence documented synchronous calls from API Gateway to Customers Service, Visits Service, Vets Service, and GenAI Service, no runtime-observed edges confirm these calls actually occurred. The runtime edge ledger shows no observed traffic originating from API Gateway to these services except discovery-server and config-server.
- Why it matters: Without runtime evidence, confidence in these documented dependencies is reduced. It is unclear if these calls are exercised in the current environment or traffic scenario, limiting completeness of the dependency analysis.
- Confidence impact: Medium
- Recommended action: Click "Pause & supplement", drive more traffic through the mesh targeting these services (e.g., exercising API Gateway routes to Customers, Visits, Vets, and GenAI), then click "Resume" to collect runtime evidence.

### Gap: Unidentified External Dependency for Config Server
- What is missing: The config-server makes external TLS calls (e.g., to github.com) that appear only as TCP connections routed through PassthroughCluster, with no ServiceEntry declared to identify the target host.
- Why it matters: Without a ServiceEntry, Istio cannot attribute these external calls to a real hostname, causing under-reporting and loss of detail in the egress dependency graph.
- Confidence impact: High
- Recommended action: Apply the suggested ServiceEntry manifest for github.com, then drive traffic and Resume to enable Istio to observe and attribute these external calls properly.

### Gap: EndpointSlices Service-to-Endpoint Mapping Incomplete
- What is missing: EndpointSlices exist with endpoint addresses and ports, but all have missing or empty service-name labels, preventing valid mapping of endpoints to services.
- Why it matters: This limits the ability to fully validate service-to-pod relationships and may affect traffic routing and observability completeness.
- Confidence impact: Low
- Recommended action: Investigate and fix EndpointSlice labeling in the cluster to ensure service-name labels are present, then re-collect Kubernetes inventory data.

### Gap: Sidecar Injection Status Unknown
- What is missing: No data is available to confirm sidecar injection status on pods, limiting certainty that Istio telemetry is fully capturing all service traffic.
- Why it matters: Without sidecar injection confirmation, some traffic may be invisible to Istio, reducing runtime coverage completeness.
- Confidence impact: Medium
- Recommended action: Verify sidecar injection is enabled for all workloads in the petclinic namespace and re-collect runtime data.

### Gap: No Asynchronous Workflow or Broker Evidence
- What is missing: No asynchronous workflows or broker relationships (queues, topics, exchanges) are documented or observed.
- Why it matters: If asynchronous messaging exists but is undocumented or unobserved, the dependency analysis is incomplete.
- Confidence impact: Low (based on current evidence)
- Recommended action: Confirm whether asynchronous messaging is used; if yes, provide source code or configuration evidence and collect runtime data accordingly.

### Gap: Traffic Execution Report Shows Many Unsent or Failed Requests
- What is missing: Several planned traffic steps failed due to unresolved variables or transport errors, limiting exercised paths and runtime evidence.
- Why it matters: Incomplete traffic execution reduces runtime coverage and confidence in observed edges.
- Confidence impact: Medium
- Recommended action: Fix traffic generation scripts to resolve variables and reduce transport errors, then re-run traffic execution and resume data collection.

---

Overall completeness: Medium  
Single most valuable next step: Apply the ServiceEntry for github.com and drive targeted traffic through API Gateway routes to Customers, Visits, Vets, and GenAI services before resuming data collection.
