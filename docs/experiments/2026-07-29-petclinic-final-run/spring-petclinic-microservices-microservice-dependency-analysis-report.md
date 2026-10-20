## Microservice Dependency Analysis Report
**Repository:** `spring-petclinic/spring-petclinic-microservices` | **Namespace:** `petclinic`

# 1. Collection Status

### DeepWiki Evidence Ledger
- Command / source status: Provided as static documentation and source code references.
- Analysis usability: Usable for documented and configured components and dependencies.
- What can be used: Documentation of components, configuration references, source code call references.
- What cannot be concluded: Runtime invocation or actual traffic flows.
- Limitation: Cannot prove runtime invocation; runtime observed status must be Unknown for DeepWiki-only items.

### Kubernetes and Istio Evidence Ledger
- Command / source status: Successful collection; Kubernetes inventory complete except EndpointSlices partial.
- Analysis usability: Usable for deployed components, services, pods, and partial endpoint mapping.
- What can be used: Service existence, deployment existence, pod readiness, service-to-deployment name-based matches, VirtualService presence.
- What cannot be concluded: Complete endpoint mapping (due to EndpointSlice label issues), sidecar injection status unknown.
- Limitation: EndpointSlices have missing or empty service-name labels, preventing valid Service-to-Endpoint mapping.

### Istio Runtime-Observed Edge Ledger
- Command / source status: Successful query; runtime observed edges present.
- Analysis usability: Usable for confirming runtime-observed synchronous service-to-service calls.
- What can be used: Runtime-observed edges from istio_requests_total metrics.
- What cannot be concluded: Runtime calls not observed in the ledger; absence does not prove non-existence.
- Limitation: Runtime evidence limited to observed edges; no distributed traces or logs provided.

# 2. Repository-Documented Components

### Component: Customers Service
- Category: Application Service
- Role: Manages customer data, including pet owners and their pets.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-customers-service/pom.xml`
- Confidence: High
- Limitations: Default database is HSQLDB; MySQL optional with profile activation.

### Component: Vets Service
- Category: Application Service
- Role: Handles information about veterinarians and their specialties.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-vets-service/pom.xml`
- Confidence: High
- Limitations: Default database is HSQLDB; MySQL optional with profile activation.

### Component: Visits Service
- Category: Application Service
- Role: Manages pet visit records.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-visits-service/pom.xml`
- Confidence: High
- Limitations: Default database is HSQLDB; MySQL optional with profile activation.

### Component: GenAI Service
- Category: Application Service
- Role: Provides a chatbot interface powered by OpenAI/Azure OpenAI.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: No (not observed in Kubernetes inventory)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-genai-service/src/main/resources/application.yml`
- Confidence: High
- Limitations: Requires API keys; demo key available with limitations.

### Component: API Gateway
- Category: Gateway
- Role: Routes client requests to services; implements circuit breakers.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-api-gateway/pom.xml`, `application.yml`
- Confidence: High
- Limitations: Uses load-balanced URIs and service discovery hostnames resolved at runtime.

### Component: Config Server
- Category: Application Service
- Role: Provides centralized configuration for all services.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-config-server/pom.xml`
- Confidence: High
- Limitations: None.

### Component: Discovery Server
- Category: Application Service
- Role: Enables service registration and discovery using Netflix Eureka.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: Yes (service, deployment, pod)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-discovery-server/pom.xml`
- Confidence: High
- Limitations: None.

### Component: Admin Server
- Category: Observability
- Role: Dashboard for monitoring and managing microservices.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: No (not observed)
- Evidence reference: `README.md`, `docker-compose.yml`, `spring-petclinic-admin-server/pom.xml`
- Confidence: High
- Limitations: Not observed in current namespace.

### Component: Tracing Server (Zipkin)
- Category: Observability
- Role: Enables distributed tracing.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: No (not observed)
- Evidence reference: `README.md`, `docker-compose.yml`
- Confidence: High
- Limitations: Not observed in current namespace.

### Component: Prometheus
- Category: Observability
- Role: Collects metrics.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: No (not observed)
- Evidence reference: `README.md`, `docker-compose.yml`
- Confidence: High
- Limitations: Not observed in current namespace.

### Component: Grafana
- Category: Observability
- Role: Visualization dashboards.
- Documented: Yes
- Configured: Yes
- Observed in current namespace: No (not observed)
- Evidence reference: `README.md`, `docker-compose.yml`
- Confidence: High
- Limitations: Not observed in current namespace.

### Component: HSQLDB
- Category: Database
- Role: In-memory default database.
- Documented: Yes
- Configured: Unknown
- Observed in current namespace: No
- Evidence reference: `README.md`
- Confidence: High
- Limitations: No explicit runtime configuration evidence.

### Component: MySQL
- Category: Database
- Role: Optional persistent database.
- Documented: Yes
- Configured: Unknown
- Observed in current namespace: No
- Evidence reference: `README.md`
- Confidence: High
- Limitations: Requires profile activation; no direct config evidence.

# 3. Components Observed in the Kubernetes Namespace

### Kubernetes component: api-gateway
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

### Kubernetes component: config-server
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

### Kubernetes component: customers-service
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

### Kubernetes component: discovery-server
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

### Kubernetes component: vets-service
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

### Kubernetes component: visits-service
- Service observed: Yes
- Deployment observed: Yes
- Pod observed: Yes (1 pod)
- Pod state: Running
- Pod ready: Yes
- Relationship status: Name-based match only
- Evidence source: Kubernetes service and deployment inventory
- Limitations: Service selector and Pod labels were not directly validated.

# 4. Synchronous Dependency Candidates

- Direct runtime-observed synchronous invocations: 10  
- Documentation/configuration-supported candidates: 7

### Candidate: discovery-server -> config-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown (due to EndpointSlice label issue)
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: api-gateway -> config-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: visits-service -> config-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: customers-service -> config-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: vets-service -> config-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: customers-service -> discovery-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: vets-service -> discovery-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: visits-service -> discovery-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: api-gateway -> discovery-server
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Endpoint mapping incomplete.

### Candidate: istio-ingressgateway -> api-gateway
- Relationship role: ingress_edge
- Dependency type: application
- Protocol: HTTP
- Method: Unknown
- Endpoint: Unknown
- Documented: No (infrastructure)
- Configured: N/A
- Deployed: Yes
- Discoverable: N/A
- Endpoint available: Unknown
- Runtime observed: Yes
- Confidence: High
- Evidence reference: istio_requests_total metrics
- Limitations: Infrastructure component.

---

### Documented but runtime observed: Unknown synchronous candidates (API Gateway -> Application Services)

### Candidate: API Gateway -> Customers Service
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: GET
- Endpoint: /owners/{ownerId}
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code and configuration
- Limitations: No runtime invocation evidence observed.

### Candidate: API Gateway -> Visits Service
- Relationship role: direct_call
- Dependency type: application
- Protocol: HTTP
- Method: GET
- Endpoint: /pets/visits?petId={petId}
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code and configuration
- Limitations: No runtime invocation evidence observed.

### Candidate: API Gateway -> Customers Service (gateway route)
- Relationship role: gateway_route
- Dependency type: gateway_route
- Protocol: HTTP
- Method: Unknown
- Endpoint: /api/customer/**
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: HTTP method and exact endpoint unknown; no runtime evidence.

### Candidate: API Gateway -> Visits Service (gateway route)
- Relationship role: gateway_route
- Dependency type: gateway_route
- Protocol: HTTP
- Method: Unknown
- Endpoint: /api/visit/**
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: HTTP method and exact endpoint unknown; no runtime evidence.

### Candidate: API Gateway -> Vets Service (gateway route)
- Relationship role: gateway_route
- Dependency type: gateway_route
- Protocol: HTTP
- Method: Unknown
- Endpoint: /api/vet/**
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: HTTP method and exact endpoint unknown; no runtime evidence.

### Candidate: API Gateway -> GenAI Service (gateway route)
- Relationship role: gateway_route
- Dependency type: gateway_route
- Protocol: HTTP
- Method: Unknown
- Endpoint: /api/genai/**
- Documented: Yes
- Configured: Yes
- Deployed: No (not observed)
- Discoverable: No
- Endpoint available: Unknown
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: Service not observed in namespace; no runtime evidence.

# 5. Infrastructure Dependencies

### Infrastructure dependency: spring-petclinic-customers-service -> HSQLDB
- Dependency type: database
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No (database not observed)
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code and documentation
- Limitations: No runtime evidence of database usage.

### Infrastructure dependency: spring-petclinic-customers-service -> MySQL
- Dependency type: database
- Deployment scope: optional
- Documented: Yes
- Configured: Unknown
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: documentation
- Limitations: Requires profile activation; no runtime evidence.

### Infrastructure dependency: spring-petclinic-vets-service -> HSQLDB
- Dependency type: database
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code and documentation
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-vets-service -> MySQL
- Dependency type: database
- Deployment scope: optional
- Documented: Yes
- Configured: Unknown
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: documentation
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-visits-service -> HSQLDB
- Dependency type: database
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code and documentation
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-visits-service -> MySQL
- Dependency type: database
- Deployment scope: optional
- Documented: Yes
- Configured: Unknown
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: documentation
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-genai-service -> OpenAI API
- Dependency type: third_party_api
- Deployment scope: optional
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-genai-service -> Azure OpenAI API
- Dependency type: cloud_service
- Deployment scope: optional
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: No runtime evidence.

### Infrastructure dependency: All Services -> Prometheus
- Dependency type: observability
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: No runtime evidence.

### Infrastructure dependency: All Services -> Grafana
- Dependency type: observability
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: No runtime evidence.

### Infrastructure dependency: All Services -> Zipkin
- Dependency type: observability
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: configuration
- Limitations: No runtime evidence.

### Infrastructure dependency: All Services -> Netflix Eureka
- Dependency type: shared_platform_component
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Unknown
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: documentation
- Limitations: No explicit runtime configuration evidence.

### Infrastructure dependency: All Services -> Spring Cloud Config
- Dependency type: shared_platform_component
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Unknown
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: documentation
- Limitations: No explicit runtime configuration evidence.

### Infrastructure dependency: spring-petclinic-admin-server -> Caffeine
- Dependency type: cache
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-api-gateway -> Caffeine
- Dependency type: cache
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: Yes (api-gateway observed)
- Discoverable: Yes
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-vets-service -> Caffeine
- Dependency type: cache
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: Yes
- Discoverable: Yes
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code
- Limitations: No runtime evidence.

### Infrastructure dependency: spring-petclinic-genai-service -> Caffeine
- Dependency type: cache
- Deployment scope: repository_declared
- Documented: Yes
- Configured: Yes
- Deployed: No
- Discoverable: No
- Runtime observed: Unknown
- Confidence: High
- Evidence reference: source code
- Limitations: No runtime evidence.

### Infrastructure dependency: config-server -> (unidentified external target)
- Dependency type: external_api
- Deployment scope: runtime_observed
- Documented: Yes (github.com)
- Configured: Yes
- Deployed: Yes
- Discoverable: No
- Runtime observed: Yes (TCP only, PassthroughCluster)
- Confidence: Medium
- Evidence reference: istio_tcp_connections_opened_total metrics
- Limitations: No ServiceEntry for github.com; external target unidentified in Istio HTTP metrics.

# 6. Asynchronous Communication

## 6.1 Broker Relationships
- No broker relationships documented or observed.

## 6.2 Producer-Broker-Consumer Workflows
- No asynchronous workflows documented or observed.

# 7. Health Checks and Observability Relations

- No health check or observability relations documented or runtime observed.

- Observability components (Prometheus, Grafana, Zipkin) documented but not runtime verified.

# 8. Runtime Facts

- 6 Services observed in namespace petclinic.
- 6 Deployments observed in namespace petclinic.
- 6 Pods observed, all Running and Ready.
- EndpointSlices present but with missing service-name labels; endpoint mapping incomplete.
- One VirtualService observed; no DestinationRules found.
- Sidecar injection status unknown.
- Runtime-observed synchronous edges exist between application services and config-server and discovery-server.
- No runtime-observed edges from API Gateway to Customers, Visits, Vets, or GenAI services.
- Runtime-observed external TCP calls from config-server to unidentified external target (likely github.com).

# 9. Unresolved Candidates and Unknowns

### Unresolved item: API Gateway -> Customers Service (runtime invocation)
- Reason: No runtime evidence of invocation despite documented and configured calls.
- Available evidence: Source code and configuration.
- Missing evidence: Runtime traces, logs, or metrics.
- Impact on report: Runtime observed status must be Unknown.

### Unresolved item: API Gateway -> Visits Service (runtime invocation)
- Reason: No runtime evidence of invocation despite documented and configured calls.
- Available evidence: Source code and configuration.
- Missing evidence: Runtime traces, logs, or metrics.
- Impact on report: Runtime observed status must be Unknown.

### Unresolved item: API Gateway -> Vets Service (runtime invocation)
- Reason: No runtime evidence of invocation despite documented and configured gateway route.
- Available evidence: Configuration.
- Missing evidence: Runtime traces, logs, or metrics.
- Impact on report: Runtime observed status must be Unknown.

### Unresolved item: API Gateway -> GenAI Service (runtime invocation)
- Reason: Service not observed in namespace; no runtime evidence.
- Available evidence: Documentation and configuration.
- Missing evidence: Deployment, runtime traces.
- Impact on report: Cannot confirm runtime invocation or deployment.

### Unresolved item: EndpointSlice service-name labels
- Reason: Missing or empty labels prevent endpoint mapping.
- Available evidence: EndpointSlice data.
- Missing evidence: Correct labels.
- Impact on report: Endpoint availability unknown.

### Unresolved item: External dependency attribution for config-server
- Reason: External calls observed only as PassthroughCluster.
- Available evidence: TCP metrics.
- Missing evidence: ServiceEntry for github.com.
- Impact on report: External dependency attribution incomplete.

# 10. Conflicts and Inconsistencies

### Issue: GenAI Service documented but not observed
- Observed facts: GenAI Service documented and configured but not found in Kubernetes inventory.
- Why they differ: Possibly not deployed or deployed in another namespace.
- Interpretation: Deployment absent from current namespace.
- Impact: Cannot confirm runtime invocation or dependency.

### Issue: EndpointSlice service-name label missing
- Observed facts: EndpointSlices exist but lack service-name labels.
- Why they differ: Kubernetes EndpointSlice controller or configuration issue.
- Interpretation: Endpoint mapping incomplete.
- Impact: Limits ability to confirm endpoint availability.

### Issue: No runtime evidence for API Gateway synchronous calls
- Observed facts: API Gateway documented to call multiple services; no runtime edges observed.
- Why they differ: Possibly traffic not exercised during collection.
- Interpretation: Runtime invocation unknown.
- Impact: Limits confidence in runtime dependencies.

# 11. Confidence Summary

### Repository Inventory Confidence
- Result: High
- Reason: Comprehensive documentation and configuration references exist for all components and dependencies.

### Kubernetes Inventory Confidence
- Result: High for observed components; partial for endpoint mapping.
- Reason: All main services, deployments, and pods observed and ready; EndpointSlice mapping incomplete.

### Static Dependency Confidence
- Result: High
- Reason: Source code and configuration clearly document synchronous and infrastructure dependencies.

### Runtime Invocation Confidence
- Result: Medium
- Reason: Runtime evidence confirms some service-to-service calls (mostly to config-server and discovery-server), but no runtime evidence for API Gateway calls to application services.

### Infrastructure Dependency Confidence
- Result: Medium
- Reason: Infrastructure dependencies documented and configured but no runtime evidence except for config-server external TCP calls.

### Asynchronous Workflow Confidence
- Result: Not applicable / None
- Reason: No asynchronous communication documented or observed.

### Endpoint Mapping Confidence
- Result: Low to Medium
- Reason: EndpointSlices present but missing service-name labels prevent full mapping.

### Overall Report Confidence
- Result: Medium
- Reason: Strong documentation and deployment evidence; runtime invocation partially confirmed; endpoint mapping incomplete; some key runtime calls unobserved.

---

Key limitation: Runtime invocation of API Gateway synchronous calls to application services is unconfirmed due to lack of observed runtime evidence.

Recommended next evidence collection step: Generate representative API Gateway traffic exercising documented routes to Customers, Visits, Vets, and GenAI services, and apply a ServiceEntry for github.com to improve external dependency attribution.