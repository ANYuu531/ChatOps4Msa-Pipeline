DEBUG: code_extraction_notes

# Code-Extracted Edge Ledger

Collection status: OK
Repository: spring-petclinic/spring-petclinic-microservices
Detected stack: java/spring (FRAMEWORK, from pom.xml); javascript (LLM, from 22 .js/.jsx/.ts/.tsx files)
Extraction method: tree-sitter java-spring+java-generic; LLM source reading (javascript); config parsing
Source files analysed: 60
Edges found: 40

## 1. Synchronous - HTTP client calls (RestTemplate / WebClient / requests / httpx)
- method=uri, path=vets [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/VectorStoreController.java:70, Medium (path only; host comes from a variable or property)]
- method=uri, path=/owners [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/AIDataProvider.java:45, Medium (path only; host comes from a variable or property)]
- method=uri, path=/pets [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/AIDataProvider.java:73, Medium (path only; host comes from a variable or property)]
- method=uri, path=/owners [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/AIDataProvider.java:82, Medium (path only; host comes from a variable or property)]
- method=uri, path=pets/visits?petId={petId} [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/VisitsServiceClient.java:45, Medium (path only; host comes from a variable or property)]
- method=uri, url=http://customers-service/owners/{ownerId} [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/CustomersServiceClient.java:37, High]

## 2. Inbound - HTTP endpoints exposed by this service
- verb=RequestMapping, path=/ [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/PetclinicChatClient.java:20, High]
- verb=PostMapping, path=/chatclient [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/PetclinicChatClient.java:57, High]
- verb=RequestMapping, path=/api/gateway [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/boundary/web/ApiGatewayController.java:37, High]
- verb=GetMapping, path=owners/{ownerId} [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/boundary/web/ApiGatewayController.java:54, High]
- verb=PostMapping, path=/fallback [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/boundary/web/FallbackController.java:11, High]
- verb=RequestMapping, path=/vets [Evidence: spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/web/VetResource.java:34, High]
- verb=RequestMapping, path=/owners [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java:39, High]
- verb=GetMapping, path=/{ownerId} [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java:67, High]
- verb=PutMapping, path=/{ownerId} [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java:83, High]
- verb=GetMapping, path=/petTypes [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetResource.java:49, High]
- verb=PostMapping, path=/owners/{ownerId}/pets [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetResource.java:54, High]
- verb=PutMapping, path=/owners/*/pets/{petId} [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetResource.java:68, High]
- verb=GetMapping, path=owners/*/pets/{petId} [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetResource.java:88, High]
- verb=PostMapping, path=owners/*/pets/{petId}/visits [Evidence: spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/web/VisitResource.java:56, High]
- verb=GetMapping, path=owners/*/pets/{petId}/visits [Evidence: spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/web/VisitResource.java:67, High]
- verb=GetMapping, path=pets/visits [Evidence: spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/web/VisitResource.java:72, High]

## 3. Synchronous - absolute URL literals in code
- value=http://vets-service/ [Evidence: spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/VectorStoreController.java:67, High]
- value=http://visits-service/ [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/VisitsServiceClient.java:34, High]
- value=http://customers-service/owners/{ownerId} [Evidence: spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/application/CustomersServiceClient.java:37, High]

## 4. Infrastructure / service-URL config keys
- key=spring.cloud.config.server.git.uri, value=https://github.com/spring-petclinic/spring-petclinic-microservices-config [Evidence: spring-petclinic-config-server/src/main/resources/application.yml, High]
- key=services.config-server.healthcheck.test[3], value=http://config-server:8888 [Evidence: docker-compose.yml, High]
- key=services.discovery-server.healthcheck.test[3], value=http://discovery-server:8761 [Evidence: docker-compose.yml, High]

## 5. Persistence - JPA/ORM markers (entities / repositories: proves DB is really used)
- marker=Entity [Evidence: spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Vet.java:36, High]
- marker=Table [Evidence: spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Vet.java:37, High]
- marker=Entity [Evidence: spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Specialty.java:27, High]
- marker=Table [Evidence: spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/model/Specialty.java:28, High]
- marker=Entity [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Owner.java:37, High]
- marker=Table [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Owner.java:38, High]
- marker=Entity [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Pet.java:34, High]
- marker=Table [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/Pet.java:35, High]
- marker=Entity [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/PetType.java:25, High]
- marker=Table [Evidence: spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/model/PetType.java:26, High]
- marker=Entity [Evidence: spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/model/Visit.java:31, High]
- marker=Table [Evidence: spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/model/Visit.java:32, High]

## Extraction warnings
- LLM extraction (javascript): response was not valid JSON and was discarded.
- LLM extraction (javascript): read 7 candidate files but returned no usable edges.

## Notes
- Every edge above is a literal found in source or config; absence means it was not found in code, not that it cannot happen at runtime.
- Property-indirected targets (${...} placeholders, env vars) are marked Medium; resolve them against application config for the concrete host.
- Syntax-only extraction: a call is matched on the receiver/identifier name, not on a resolved type, so a target whose client is declared in a parent class may be missed.

