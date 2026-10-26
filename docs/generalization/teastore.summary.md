## stacks
- java (GENERIC, from pom.xml)
- javascript (LLM, from 13 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- python (GENERIC, from .py sources)

## ledger sections
- config = 3
- name-ref = 170
- name-constant = 49
- http-server = 52
- url = 3
- jpa = 6
- import = 5
- env-address = 71
- compose-service = 22
- workload-env = 70
- k8s-workload = 44
- k8s-service = 28
- service-root = 14
- module-deps = 13
- TOTAL = 550 | files with syntax errors = 0

## graph
- after merge: 9 nodes / 19 edges | after normalize: 9 nodes / 19 edges | unresolved code edges = 1
- persistence services: [teastore-persistence]

## nodes (kind, layer)
- teastore-all  [service, L0]
- teastore-auth  [service, L1]
- teastore-db  [db, L4]
- teastore-image  [service, L1]
- teastore-kieker-rabbitmq  [queue, L4]
- teastore-persistence  [service, L2]
- teastore-recommender  [service, L1]
- teastore-registry  [service, L3]
- teastore-webui  [service, L0]

## edges (type, confidence, provenance ref)
- teastore-all -> teastore-db  (db, inferred)  code: examples/kubernetes/teastore-all.yaml
- teastore-auth -> teastore-kieker-rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-auth -> teastore-persistence  (sync-http, documented)  code: services/tools.descartes.teastore.auth/src/main/java/tools/descartes/teastore/auth/startup/AuthStartup.java:67
- teastore-auth -> teastore-registry  (sync-http, inferred)  code: examples/docker/docker-compose_https.yaml
- teastore-image -> teastore-kieker-rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-image -> teastore-persistence  (sync-http, documented)  code: services/tools.descartes.teastore.image/src/main/java/tools/descartes/teastore/image/setup/SetupController.java:158
- teastore-image -> teastore-registry  (sync-http, inferred)  code: examples/docker/docker-compose_https.yaml
- teastore-persistence -> teastore-db  (db, documented)  code: examples/docker/docker-compose_https.yaml
- teastore-persistence -> teastore-kieker-rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-persistence -> teastore-registry  (sync-http, inferred)  code: examples/docker/docker-compose_https.yaml
- teastore-recommender -> teastore-kieker-rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-recommender -> teastore-persistence  (sync-http, documented)  code: services/tools.descartes.teastore.recommender/src/main/java/tools/descartes/teastore/recommender/servlet/TrainingSynchronizer.java:138
- teastore-recommender -> teastore-registry  (sync-http, inferred)  code: examples/docker/docker-compose_https.yaml
- teastore-webui -> teastore-auth  (sync-http, documented)  code: services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:71
- teastore-webui -> teastore-image  (sync-http, documented)  code: services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/StatusServlet.java:75
- teastore-webui -> teastore-kieker-rabbitmq  (async, documented)  code: examples/docker/docker-compose_kieker.yaml
- teastore-webui -> teastore-persistence  (sync-http, documented)  code: services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:67
- teastore-webui -> teastore-recommender  (sync-http, documented)  code: services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/servlet/DataBaseActionServlet.java:84
- teastore-webui -> teastore-registry  (sync-http, inferred)  code: examples/docker/docker-compose_https.yaml

## unresolved (source hint / raw target / file:line), first 40
- tools-descartes-teastore-dockerbase  =>  amqp://admin:nimda@RABBITMQ_PORT_PLACEHOLDER   @ utilities/tools.descartes.teastore.dockerbase/kieker.monitoring.properties:-1

## alias questions (name [origin] seen in | candidates)
- tools-descartes-teastore-dockerbase  [code]  the module directory of config at utilities/tools.descartes.teastore.dockerbase/kieker.monitoring.properties — no service on the graph has this name   | candidates: -

## alias answers applied
- none

## mermaid
```mermaid
flowchart TB
%% DepWeaver — microservice dependency graph
%% solid arrow = observed at runtime (Istio) · dashed = declared in code/doc only
%% node shape: [service] ([gateway]) [(db)] {{queue}} [/external/]
  subgraph layer0 ["entry services"]
    direction LR
    teastore_webui["teastore-webui"]
    teastore_all["teastore-all"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    teastore_auth["teastore-auth"]
    teastore_image["teastore-image"]
    teastore_recommender["teastore-recommender"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    teastore_persistence["teastore-persistence"]
  end
  subgraph layer3 ["services · depth 3"]
    direction LR
    teastore_registry["teastore-registry"]
  end
  subgraph layer4 ["data stores"]
    direction LR
    teastore_db[("teastore-db")]:::db
    teastore_kieker_rabbitmq{{"teastore-kieker-rabbitmq"}}:::queue
  end
  teastore_webui -.-> teastore_persistence
  teastore_webui -.-> teastore_recommender
  teastore_webui -.-> teastore_auth
  teastore_webui -.-> teastore_image
  teastore_recommender -.-> teastore_persistence
  teastore_image -.-> teastore_persistence
  teastore_auth -.-> teastore_persistence
  teastore_persistence -. declared? .-> teastore_registry
  teastore_persistence -. db .-> teastore_db
  teastore_auth -. declared? .-> teastore_registry
  teastore_image -. declared? .-> teastore_registry
  teastore_recommender -. declared? .-> teastore_registry
  teastore_webui -. declared? .-> teastore_registry
  teastore_persistence -. async .-> teastore_kieker_rabbitmq
  teastore_auth -. async .-> teastore_kieker_rabbitmq
  teastore_image -. async .-> teastore_kieker_rabbitmq
  teastore_recommender -. async .-> teastore_kieker_rabbitmq
  teastore_webui -. async .-> teastore_kieker_rabbitmq
  teastore_all -. db? .-> teastore_db
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;

```
