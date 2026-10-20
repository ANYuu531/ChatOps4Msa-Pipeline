## Traffic Driven
# Traffic Execution Report

Target: http://192.168.100.106/
Steps executed: 11 (each repeated 2x)
Transport errors (never reached the service): 7

- [405] GET http://192.168.100.106/api/customer/owners  (list owners)
    intended to exercise: front-end -> customers-service owners list
- [ERROR] GET http://192.168.100.106/api/customer/owners/{{ownerId}}  (get owner details)
    intended to exercise: front-end -> customers-service owners detail
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] GET http://192.168.100.106/api/customer/owners/{{ownerId}}/pets  (list pets for owner)
    intended to exercise: front-end -> customers-service pets list for owner
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] GET http://192.168.100.106/api/customer/owners/{{ownerId}}/pets/{{petId}}  (get pet details)
    intended to exercise: front-end -> customers-service pet detail
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] GET http://192.168.100.106/api/visit/owners/{{ownerId}}/pets/{{petId}}/visits  (list visits for pet)
    intended to exercise: front-end -> visits-service visits list for pet
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [405] GET http://192.168.100.106/api/vet/vets  (list all vets)
    intended to exercise: front-end -> vets-service vets list
- [ERROR] GET http://192.168.100.106/api/vet/vets/{{vetId}}  (get vet details)
    intended to exercise: front-end -> vets-service vet detail
    error: not sent: the variable {{vetId}} was never captured by an earlier step
- [405] GET http://192.168.100.106/api/customer/petTypes  (list pet types)
    intended to exercise: front-end -> customers-service pet types list
- [ERROR] POST http://192.168.100.106/api/customer/owners/{{ownerId}}/pets  (add new pet for owner)
    intended to exercise: front-end -> customers-service add pet -> pets
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [ERROR] POST http://192.168.100.106/api/visit/owners/{{ownerId}}/pets/{{newPetId}}/visits  (add visit for new pet)
    intended to exercise: front-end -> visits-service add visit -> visits
    error: not sent: the variable {{ownerId}} was never captured by an earlier step
- [503] POST http://192.168.100.106/api/genai/chatclient  (genai chat client POST)
    intended to exercise: front-end -> genai-service chatclient POST

## How to read this
- A 4xx/5xx still proves a request reached that endpoint, so the edge INTO it is observed. It does NOT prove the endpoint's downstream calls happened: a service that rejects the input before calling downstream produces no deeper edge.
- A transport error means the request never arrived; that step produced no evidence at all.
- A [GAP] step is an UNREACHABLE: marker the generator emitted on purpose; it was not sent. The edge it names still needs whatever it says is missing.
- A step whose capture is missing means later steps depending on that variable were sent with an unresolved placeholder and probably failed.

