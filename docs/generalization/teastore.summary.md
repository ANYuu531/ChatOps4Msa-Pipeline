## stacks
- java (GENERIC, from pom.xml)
- javascript (GENERIC, from 13 .js/.mjs/.cjs/.jsx files, package.json; tree-sitter grammar)
- python (GENERIC, from .py sources)

## ledger sections
- config = 4
- name-ref = 170
- name-constant = 49
- http-server = 52
- url = 3
- jpa = 6
- import = 5
- env-address = 77
- k8s-service = 35
- compose-service = 22
- workload-env = 70
- k8s-workload = 44
- service-root = 14
- module-deps = 13
- TOTAL = 564 | files with syntax errors = 0

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

## 規則觸發
計數＝該規則改變了多少個不同的東西（邊、節點、名稱、檔案；同一個東西重複碰到算一次）；括號內最多列 5 個例子。
- rule 1: 14 (align tools-descartes-teastore-kieker-rabbitmq=teastore-kieker-rabbitmq; not-preadded tools-descartes-teastore-dockermemoryconfigurator; not-preadded tools-descartes-teastore-database; not-preadded tools-descartes-teastore-docker-all; not-preadded tools-descartes-teastore-dockerbase)
- rule 2: 15 (test-dir e2e-tests; name tools.descartes.teastore.dockermemoryconfigurator=tools-descartes-teastore-dockermemoryconfigurator; name tools.descartes.teastore.database=tools-descartes-teastore-database; name tools.descartes.teastore.kieker.probes=tools-descartes-teastore-kieker-probes; name tools.descartes.teastore.docker.all=tools-descartes-teastore-docker-all)
- rule 3: 0
- rule 4: 0
- rule 5: 9 (env REGISTRY_HOST=127.0.0.1; env DB_HOST=127.0.0.1; env RABBITMQ_HOST=unset; env REGISTRY_HOST=teastore-registry; env DB_HOST=teastore-db)
- rule 6: 0
- rule 7: 25 (env persistence.REGISTRY_HOST=teastore-registry; env persistence.DB_HOST=teastore-db; env auth.REGISTRY_HOST=teastore-registry; env image.REGISTRY_HOST=teastore-registry; env recommender.REGISTRY_HOST=teastore-registry)
- rule 8: 1 (config-signal url)
- rule 9: 58 (jaxrs utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/AbstractCRUDEndpoint.java:37; jaxrs utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/AbstractCRUDEndpoint.java:70; jaxrs utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/AbstractCRUDEndpoint.java:124; jaxrs utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/util/AbstractCRUDEndpoint.java:153; jaxrs services/tools.descartes.teastore.webui/src/main/java/tools/descartes/teastore/webui/rest/ReadyRest.java:27)
- rule 10: 0
- rule 11: 1 (k8s-over-compose 8 compose names ignored)
- rule 12: 0
- rule 13: 0
- rule 14: 5 (edge teastore-persistence->teastore-registry; edge teastore-auth->teastore-registry; edge teastore-image->teastore-registry; edge teastore-recommender->teastore-registry; edge teastore-webui->teastore-registry)
- rule 15: 0
- rule 16: 0
- rule 17: 7 (edge teastore-webui->teastore-persistence; edge teastore-webui->teastore-recommender; edge teastore-webui->teastore-auth; edge teastore-webui->teastore-image; edge teastore-recommender->teastore-persistence)
- rule 18: 1 (dockerfile-env RABBITMQ_HOST=unset)
- rule 19: 0
- rule 20: 0
- rule 21: 0
- rule 22: 0
- rule 23: 0
- rule 24: 0
- rule 25: 0
- rule 26: 0
- rule 27: 0
- rule 28: 0
- rule 29: 0
- rule 30: 0
- rule 31: 0
- rule 32: 0
- rule 33: 0
- rule 34: 0
- rule 35: 0
- rule 36: 0
- rule 37: 0
- rule 38: 0
- rule 39: 0
- rule 40: 0
- rule 41: 14 (lib tools-descartes-teastore-kieker-probes; lib tools-descartes-teastore-registryclient; lib tools-descartes-teastore-entities; no-edge utilities/tools.descartes.teastore.kieker.probes/src/main/java/tools/descartes/teastore/kieker/probes/AbstractOperationExecutionWithParameterAspect.java; no-edge utilities/tools.descartes.teastore.registryclient/src/main/java/tools/descartes/teastore/registryclient/tracing/Tracing.java)
- rule 42: 0
- rule 43: 0
- rule 44: 12 (image tools-descartes-teastore-webui=webui; map tools-descartes-teastore-webui=teastore-webui; image tools-descartes-teastore-recommender=recommender; map tools-descartes-teastore-recommender=teastore-recommender; image tools-descartes-teastore-persistence=persistence)
- rule 45: 0
- rule 46: 0
- rule 52: 14 (render examples/helm/templates/auth-service.yaml; render examples/helm/templates/auth-statefulset.yaml; render examples/helm/templates/db-service.yaml; render examples/helm/templates/db-statefulset.yaml; render examples/helm/templates/image-service.yaml)
