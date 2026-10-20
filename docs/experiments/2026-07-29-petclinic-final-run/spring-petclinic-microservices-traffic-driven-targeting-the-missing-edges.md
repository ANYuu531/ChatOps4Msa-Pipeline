## Traffic Driven (targeting the missing edges)
# Traffic Execution Report

Target: http://192.168.100.106/
Steps executed: 6 (each repeated 2x)
Transport errors (never reached the service): 3

- [405] GET http://192.168.100.106/api/customer/owners  (list owners)
    intended to exercise: api-gateway -> customers-service owners list
- [ERROR] GET http://192.168.100.106/api/customer/owners/{{ownerId}}  (get owner details)
    intended to exercise: api-gateway -> customers-service owners detail
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] GET http://192.168.100.106/api/customer/owners/{{ownerId}}/pets  (list pets for owner)
    intended to exercise: api-gateway -> customers-service pets list for owner
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] GET http://192.168.100.106/api/visit/owners/{{ownerId}}/pets/{{petId}}/visits  (list visits for pet)
    intended to exercise: api-gateway -> visits-service visits list for pet
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [405] GET http://192.168.100.106/api/vet/vets  (list all vets)
    intended to exercise: api-gateway -> vets-service vets list
- [503] POST http://192.168.100.106/api/genai/chatclient  (genai chat client POST)
    intended to exercise: front-end -> genai-service chatclient POST -> genai-service -> vets-service

## How to read this
- A 4xx/5xx still proves a request reached that endpoint, so the edge INTO it is observed. It does NOT prove the endpoint's downstream calls happened: a service that rejects the input before calling downstream produces no deeper edge.
- A transport error means the request never arrived; that step produced no evidence at all.
- A [GAP] step is an UNREACHABLE: marker the generator emitted on purpose; it was not sent. The edge it names still needs whatever it says is missing.
- A step whose capture is missing means later steps depending on that variable were sent with an unresolved placeholder and probably failed.

