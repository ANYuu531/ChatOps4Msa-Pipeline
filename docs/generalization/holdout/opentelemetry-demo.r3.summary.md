## stacks
- javascript (LLM, from 147 .js/.jsx/.ts/.tsx files)   <-- skipped (LLM tier)
- python/web (FRAMEWORK, from requirements.txt)
- go (LLM, from 15 .go files)   <-- skipped (LLM tier)
- csharp (GENERIC, from 12 .cs files; thin regex surface, no tree-sitter grammar offline)
- java/spring (FRAMEWORK, from build.gradle)
- rust (LLM, from 7 .rs files)   <-- skipped (LLM tier)
- php (LLM, from 6 .php files)   <-- skipped (LLM tier)
- kotlin (GENERIC, from build.gradle.kts; thin regex surface, no tree-sitter grammar offline)
- ruby (LLM, from 1 .rb files)   <-- skipped (LLM tier)

## ledger sections
- import = 160
- url = 28
- config = 84
- http-client = 9
- name-constant = 4
- name-ref = 4
- compose-service = 41
- compose-dependency = 69
- env-address = 96
- workload-env = 96
- service-root = 30
- module-deps = 8
- TOTAL = 629 | files with syntax errors = 0

## graph
- after merge: 34 nodes / 84 edges | after normalize: 34 nodes / 84 edges | unresolved code edges = 40
- persistence services: []

## nodes (kind, layer)
- accounting  [service, L0]
- ad  [service, L1]
- agent  [service, L1]
- astronomy-db  [db, L3]
- cart  [service, L1]
- chatbot  [service, L1]
- checkout  [service, L1]
- currency  [service, L1]
- email  [service, L1]
- firepit  [service, L2]
- flagd  [service, L1]
- flagd-ui  [service, L1]
- fraud-detection  [service, L0]
- frontend  [service, L1]
- frontend-proxy  [service, L1]
- grafana  [service, L2]
- image-provider  [service, L1]
- jaeger  [service, L1]
- kafka  [queue, L3]
- load-generator  [service, L1]
- local-llm.com  [external, L4]
- mcp  [service, L1]
- opamp-server  [service, L2]
- opensearch  [service, L5]
- otel-collector  [service, L1]
- otel-ebpf-profiler  [service, L0]
- payment  [service, L1]
- product-catalog  [service, L1]
- prometheus  [service, L2]
- quote  [service, L1]
- recommendation  [service, L1]
- shipping  [service, L1]
- telemetry-docs  [service, L1]
- valkey-cart  [service, L2]

## edges (type, confidence, provenance ref)
- accounting -> astronomy-db  (db, documented)  code: src/accounting/Consumer.cs:22
- accounting -> kafka  (async, documented)  code: src/accounting/Consumer.cs:42
- accounting -> otel-collector  (sync-http, documented)  code: compose.full.yaml#depends_on
- ad -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- ad -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- agent -> flagd  (sync-http, documented)  code: src/agent/src/agents/feature_flags.py:16
- agent -> frontend  (sync-http, inferred)  code: compose.agent.yaml
- agent -> local-llm.com  (external, inferred)  code: compose.agent.yaml
- agent -> mcp  (sync-http, documented)  code: src/agent/src/agents/agents.py:45
- agent -> otel-collector  (sync-http, documented)  code: compose.agent.yaml#depends_on
- cart -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- cart -> opamp-server  (sync-http, documented)  code: src/cart/src/Program.cs:103
- cart -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- cart -> valkey-cart  (sync-http, documented)  code: src/cart/src/Program.cs:33
- chatbot -> agent  (sync-http, documented)  code: src/chatbot/src/chat_interface/chat_interface.py:151
- chatbot -> opamp-server  (sync-http, documented)  code: compose.agent.yaml#depends_on
- chatbot -> otel-collector  (sync-http, inferred)  code: compose.agent.yaml
- checkout -> cart  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> currency  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> email  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> kafka  (async, documented)  code: compose.full.yaml#depends_on
- checkout -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> payment  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> product-catalog  (sync-http, documented)  code: compose.yaml#depends_on
- checkout -> shipping  (sync-http, documented)  code: compose.yaml#depends_on
- currency -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- email -> flagd  (sync-http, inferred)  code: compose.yaml
- email -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- flagd -> otel-collector  (sync-http, inferred)  code: compose.yaml
- flagd-ui -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- fraud-detection -> flagd  (sync-http, inferred)  code: compose.full.yaml
- fraud-detection -> kafka  (async, documented)  code: src/fraud-detection/src/main/kotlin/frauddetection/main.kt:50
- fraud-detection -> otel-collector  (sync-http, documented)  code: compose.full.yaml#depends_on
- frontend -> ad  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> cart  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> checkout  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> currency  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> product-catalog  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> recommendation  (sync-http, documented)  code: compose.yaml#depends_on
- frontend -> shipping  (sync-http, documented)  code: compose.yaml#depends_on
- frontend-proxy -> chatbot  (sync-http, documented)  code: compose.agent.yaml#depends_on
- frontend-proxy -> firepit  (sync-http, documented)  code: src/frontend-proxy/envoy.tmpl.yaml
- frontend-proxy -> flagd  (sync-http, documented)  code: src/frontend-proxy/envoy.tmpl.yaml
- frontend-proxy -> flagd-ui  (sync-http, documented)  code: compose.yaml#depends_on
- frontend-proxy -> frontend  (sync-http, documented)  code: compose.yaml#depends_on
- frontend-proxy -> grafana  (sync-http, documented)  code: compose.observability.yaml#depends_on
- frontend-proxy -> image-provider  (sync-http, documented)  code: src/frontend-proxy/envoy.tmpl.yaml
- frontend-proxy -> jaeger  (sync-http, documented)  code: compose.observability.yaml#depends_on
- frontend-proxy -> load-generator  (sync-http, documented)  code: compose.yaml#depends_on
- frontend-proxy -> opamp-server  (sync-http, documented)  code: compose.observability.yaml#depends_on
- frontend-proxy -> otel-collector  (sync-http, documented)  code: src/frontend-proxy/envoy.tmpl.yaml
- frontend-proxy -> telemetry-docs  (sync-http, documented)  code: compose.yaml#depends_on
- image-provider -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- jaeger -> otel-collector  (sync-http, inferred)  code: compose.observability.yaml
- jaeger -> prometheus  (sync-http, inferred)  code: compose.observability.yaml
- kafka -> otel-collector  (sync-http, inferred)  code: compose.full.yaml
- load-generator -> agent  (sync-http, documented)  code: src/load-generator/locustfile.py:138
- load-generator -> flagd  (sync-http, documented)  code: src/load-generator/locustfile.py:95
- load-generator -> frontend-proxy  (sync-http, inferred)  code: compose.yaml
- load-generator -> otel-collector  (sync-http, inferred)  code: compose.yaml
- mcp -> frontend  (sync-http, documented)  code: compose.agent.yaml#depends_on
- mcp -> otel-collector  (sync-http, inferred)  code: compose.agent.yaml
- otel-collector -> astronomy-db  (db, inferred)  code: compose.yaml
- otel-collector -> frontend-proxy  (sync-http, inferred)  code: compose.yaml
- otel-collector -> image-provider  (sync-http, inferred)  code: compose.yaml
- otel-collector -> kafka  (async, documented)  code: compose.observability.yaml
- otel-ebpf-profiler -> otel-collector  (sync-http, documented)  code: compose.profiling.yaml#depends_on
- payment -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- payment -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- product-catalog -> astronomy-db  (db, documented)  code: compose.yaml#depends_on
- product-catalog -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- product-catalog -> opamp-server  (sync-http, documented)  code: compose.observability.yaml#depends_on
- product-catalog -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- quote -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- recommendation -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- recommendation -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- recommendation -> product-catalog  (sync-http, documented)  code: compose.yaml#depends_on
- shipping -> flagd  (sync-http, documented)  code: compose.yaml#depends_on
- shipping -> otel-collector  (sync-http, documented)  code: compose.yaml#depends_on
- shipping -> quote  (sync-http, inferred)  code: compose.yaml
- telemetry-docs -> otel-collector  (sync-http, inferred)  code: compose.yaml

## unresolved (source hint / raw target / file:line), first 40
- load-generator  =>  http://   @ src/load-generator/locustfile.py:95
- load-generator  =>  http://   @ src/load-generator/locustfile.py:245
- chatbot  =>  http://   @ src/chatbot/src/chat_interface/chat_interface.py:151
- agent  =>  http://   @ src/agent/src/agents/agents.py:45
- null  =>  http://   @ src/shared/tools.py:42
- null  =>  http://   @ src/shared/tools.py:56
- null  =>  http://   @ src/shared/tools.py:76
- null  =>  http://   @ src/shared/tools.py:89
- null  =>  http://   @ src/shared/tools.py:105
- null  =>  http://   @ src/shared/tools.py:118
- null  =>  http://   @ src/shared/tools.py:135
- null  =>  http://   @ src/shared/tools.py:156
- null  =>  http://   @ src/shared/tools.py:169
- null  =>  http://   @ src/shared/tools.py:187
- null  =>  otel-collector   @ .env:-1
- null  =>  http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_GRPC}   @ .env:-1
- null  =>  http://localhost:8080/otlp-http/v1/traces   @ .env:-1
- null  =>  http://email:${EMAIL_PORT}   @ .env:-1
- null  =>  frontend   @ .env:-1
- null  =>  image-provider   @ .env:-1
- null  =>  http://frontend-proxy   @ .env:-1
- null  =>  load-generator   @ .env:-1
- null  =>  http://quote:${QUOTE_PORT}   @ .env:-1
- null  =>  http://shipping:${SHIPPING_PORT}   @ .env:-1
- null  =>  flagd   @ .env:-1
- null  =>  flagd-ui   @ .env:-1
- null  =>  kafka   @ .env:-1
- null  =>  astronomy-db   @ .env:-1
- null  =>  grafana   @ .env:-1
- null  =>  jaeger   @ .env:-1
- null  =>  opensearch   @ .env:-1
- null  =>  prometheus   @ .env:-1
- null  =>  mcp   @ .env:-1
- null  =>  agent   @ .env:-1
- null  =>  chatbot   @ .env:-1
- null  =>  https://local-llm.com   @ .env:-1
- null  =>  http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_HTTP}   @ .env:-1
- null  =>  opamp-server   @ .env:-1
- null  =>  firepit   @ .env:-1
- null  =>  telemetry-docs   @ .env:-1

## alias questions (name [origin] seen in | candidates)
- frontend-proxy  [graph]  looks like the same service as `frontend` (one name is the other with a prefix or suffix); both stay on the graph until you say   | candidates: frontend
- otel-collector  [code]  config at .env   | candidates: -
- frontend  [code]  config at .env   | candidates: frontend-proxy
- image-provider  [code]  config at .env   | candidates: -
- load-generator  [code]  config at .env   | candidates: ad
- flagd  [code]  config at .env   | candidates: flagd-ui
- flagd-ui  [code]  config at .env   | candidates: flagd
- kafka  [code]  config at .env   | candidates: -
- astronomy-db  [code]  config at .env   | candidates: -
- grafana  [code]  config at .env   | candidates: -
- jaeger  [code]  config at .env   | candidates: -
- opensearch  [code]  config at .env   | candidates: -
- prometheus  [code]  config at .env   | candidates: -
- mcp  [code]  config at .env   | candidates: -
- agent  [code]  config at .env   | candidates: -
- chatbot  [code]  config at .env   | candidates: -
- opamp-server  [code]  config at .env   | candidates: -
- firepit  [code]  config at .env   | candidates: -
- telemetry-docs  [code]  config at .env   | candidates: -

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
    otel_ebpf_profiler["otel-ebpf-profiler"]
    accounting["accounting"]
    fraud_detection["fraud-detection"]
  end
  subgraph layer1 ["services · depth 1"]
    direction LR
    ad["ad"]
    cart["cart"]
    checkout["checkout"]
    currency["currency"]
    email["email"]
    frontend["frontend"]
    frontend_proxy["frontend-proxy"]
    image_provider["image-provider"]
    load_generator["load-generator"]
    payment["payment"]
    product_catalog["product-catalog"]
    quote["quote"]
    recommendation["recommendation"]
    shipping["shipping"]
    flagd["flagd"]
    flagd_ui["flagd-ui"]
    telemetry_docs["telemetry-docs"]
    otel_collector["otel-collector"]
    jaeger["jaeger"]
    agent["agent"]
    mcp["mcp"]
    chatbot["chatbot"]
  end
  subgraph layer2 ["services · depth 2"]
    direction LR
    firepit["firepit"]
    valkey_cart["valkey-cart"]
    grafana["grafana"]
    prometheus["prometheus"]
    opamp_server["opamp-server"]
  end
  subgraph layer3 ["data stores"]
    direction LR
    astronomy_db[("astronomy-db")]:::db
    kafka{{"kafka"}}:::queue
  end
  subgraph layer4 ["external"]
    direction LR
    local_llm_com[/"local-llm.com"/]:::external
  end
  subgraph layer5 ["no dependencies found"]
    direction LR
    opensearch["opensearch"]
  end
  load_generator -.-> flagd
  load_generator -.-> agent
  chatbot -.-> agent
  agent -.-> flagd
  agent -.-> mcp
  accounting -. db .-> astronomy_db
  accounting -. async .-> kafka
  cart -.-> valkey_cart
  cart -.-> opamp_server
  fraud_detection -. async .-> kafka
  otel_ebpf_profiler -.-> otel_collector
  ad -.-> otel_collector
  ad -.-> flagd
  cart -.-> otel_collector
  cart -.-> flagd
  checkout -.-> cart
  checkout -.-> currency
  checkout -.-> email
  checkout -.-> payment
  checkout -.-> product_catalog
  checkout -.-> shipping
  checkout -.-> otel_collector
  checkout -.-> flagd
  currency -.-> otel_collector
  email -.-> otel_collector
  frontend -.-> ad
  frontend -.-> cart
  frontend -.-> checkout
  frontend -.-> currency
  frontend -.-> product_catalog
  frontend -.-> recommendation
  frontend -.-> shipping
  frontend -.-> otel_collector
  frontend -.-> flagd
  frontend_proxy -.-> frontend
  frontend_proxy -.-> load_generator
  frontend_proxy -.-> flagd_ui
  frontend_proxy -.-> telemetry_docs
  image_provider -.-> otel_collector
  payment -.-> otel_collector
  payment -.-> flagd
  product_catalog -.-> otel_collector
  product_catalog -.-> flagd
  product_catalog -. db .-> astronomy_db
  quote -.-> otel_collector
  recommendation -.-> product_catalog
  recommendation -.-> otel_collector
  recommendation -.-> flagd
  shipping -.-> otel_collector
  shipping -.-> flagd
  flagd_ui -.-> otel_collector
  frontend_proxy -.-> jaeger
  frontend_proxy -.-> grafana
  frontend_proxy -.-> opamp_server
  product_catalog -.-> opamp_server
  agent -.-> otel_collector
  mcp -.-> frontend
  chatbot -.-> opamp_server
  frontend_proxy -.-> chatbot
  accounting -.-> otel_collector
  fraud_detection -.-> otel_collector
  checkout -. async .-> kafka
  frontend_proxy -.-> otel_collector
  frontend_proxy -.-> image_provider
  frontend_proxy -.-> flagd
  frontend_proxy -.-> firepit
  email -. declared? .-> flagd
  load_generator -. declared? .-> frontend_proxy
  load_generator -. declared? .-> otel_collector
  shipping -. declared? .-> quote
  flagd -. declared? .-> otel_collector
  telemetry_docs -. declared? .-> otel_collector
  otel_collector -. declared? .-> frontend_proxy
  otel_collector -. declared? .-> image_provider
  otel_collector -. db? .-> astronomy_db
  jaeger -. declared? .-> prometheus
  jaeger -. declared? .-> otel_collector
  otel_collector -. async .-> kafka
  agent -. ext? .-> local_llm_com
  agent -. declared? .-> frontend
  mcp -. declared? .-> otel_collector
  chatbot -. declared? .-> otel_collector
  fraud_detection -. declared? .-> flagd
  kafka -. declared? .-> otel_collector
classDef db fill:#e8f0ff,stroke:#3a6ea5,color:#13294b;
classDef queue fill:#fff5e0,stroke:#c08a1e,color:#4a370a;
classDef external fill:#f3e8ff,stroke:#7a3fb0,color:#2e1440;

```
