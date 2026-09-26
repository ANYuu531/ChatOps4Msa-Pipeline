## stacks
- java (GENERIC, from pom.xml)
- javascript (LLM, from 13 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- python (GENERIC, from .py sources)

## ledger sections
- config = 3
- http-server = 52
- url = 3
- jpa = 6
- env-address = 22
- workload-env = 70
- k8s-workload = 44
- service-root = 14
- TOTAL = 214 | files with syntax errors = 0

## graph
- after merge: 10 nodes / 12 edges | after normalize: 10 nodes / 12 edges | unresolved code edges = 3
- persistence services: [teastore-persistence]

## nodes (kind, layer)
- rabbitmq  [queue, L1]
- teastore-all  [service, L0]
- teastore-auth  [service, L0]
- teastore-db  [db, L1]
- teastore-image  [service, L0]
- teastore-kieker-rabbitmq  [queue, L1]
- teastore-persistence  [service, L0]
- teastore-recommender  [service, L0]
- teastore-registry  [service, L2]
- teastore-webui  [service, L0]

## edges (type, confidence, evidence)
- teastore-all -> teastore-db  (db, inferred)  code: examples/kubernetes/teastore-all.yaml
- teastore-auth -> rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-auth -> teastore-kieker-rabbitmq  (async, documented)  code: examples/kubernetes/teastore-ribbon-kieker_v16.yaml
- teastore-image -> rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-image -> teastore-kieker-rabbitmq  (async, documented)  code: examples/kubernetes/teastore-ribbon-kieker_v16.yaml
- teastore-persistence -> rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-persistence -> teastore-db  (db, documented)  code: examples/docker/docker-compose_https.yaml
- teastore-persistence -> teastore-kieker-rabbitmq  (async, documented)  code: examples/kubernetes/teastore-ribbon-kieker_v16.yaml
- teastore-recommender -> rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-recommender -> teastore-kieker-rabbitmq  (async, documented)  code: examples/kubernetes/teastore-ribbon-kieker_v16.yaml
- teastore-webui -> rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-webui -> teastore-kieker-rabbitmq  (async, documented)  code: examples/kubernetes/teastore-ribbon-kieker_v16.yaml

## unresolved (source hint / raw target / file:line), first 40
- tools-descartes-teastore-registryclient  =>  https://   @ utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/RESTClient.java:86
- tools-descartes-teastore-registryclient  =>  http://   @ utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/RESTClient.java:88
- tools-descartes-teastore-dockerbase  =>  amqp://admin:nimda@RABBITMQ_PORT_PLACEHOLDER   @ utilities/tools.descartes.teastore.dockerbase/kieker.monitoring.properties:-1

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    teastore_persistence["teastore-persistence"]
    teastore_auth["teastore-auth"]
    teastore_image["teastore-image"]
    teastore_recommender["teastore-recommender"]
    teastore_webui["teastore-webui"]
    teastore_all["teastore-all"]
  end
  subgraph layer1 ["data stores"]
    direction LR
    teastore_db[("teastore-db")]:::db
    teastore_kieker_rabbitmq{{"teastore-kieker-rabbitmq"}}:::queue
    rabbitmq{{"rabbitmq"}}:::queue
  end
  subgraph layer2 ["no dependencies found"]
    direction LR
    teastore_registry["teastore-registry"]
  end
  teastore_persistence -. db .-> teastore_db
  teastore_persistence -. async .-> rabbitmq
  teastore_auth -. async .-> rabbitmq
  teastore_image -. async .-> rabbitmq
  teastore_recommender -. async .-> rabbitmq
  teastore_webui -. async .-> rabbitmq
  teastore_persistence -. async .-> teastore_kieker_rabbitmq
  teastore_auth -. async .-> teastore_kieker_rabbitmq
  teastore_image -. async .-> teastore_kieker_rabbitmq
  teastore_recommender -. async .-> teastore_kieker_rabbitmq
  teastore_webui -. async .-> teastore_kieker_rabbitmq
  teastore_all -. db? .-> teastore_db
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```
