; Generic pack: any Kotlin repository (Spring Boot, Ktor, plain JVM).
;
; Round 4 (2026-10-27): Kotlin was read with a thin regex surface (rule 28); it now has
; a grammar (tree-sitter-kotlin) and this pack, which is the Java packs' signals in
; Kotlin syntax: System.getenv with the `?:` default, @Value, URL literals and
; constants, RestTemplate / WebClient / OkHttp / Ktor call sites, Feign, Kafka and
; RabbitMQ listeners and templates, JPA markers, Spring mappings. The thin surface
; stays only as the fallback for a platform where the grammar fails to load.
;
; The Kotlin grammar has no field names, so patterns rely on child order.

; ---------- Environment reads ----------

; System.getenv("KAFKA_ADDR") ?: "kafka:9092"
((elvis_expression
   (call_expression
     (navigation_expression
       (simple_identifier) @_o
       (navigation_suffix (simple_identifier) @_m))
     (call_suffix (value_arguments (value_argument (string_literal (string_content) @config.env)))))
   (string_literal (string_content) @config.envdefault))
 (#eq? @_o "System")
 (#eq? @_m "getenv"))

; System.getenv("X")
((call_expression
   (navigation_expression
     (simple_identifier) @_o
     (navigation_suffix (simple_identifier) @_m))
   (call_suffix (value_arguments (value_argument (string_literal (string_content) @config.env)))))
 (#eq? @_o "System")
 (#eq? @_m "getenv"))

; ---------- Bindings ----------

; val addr = System.getenv("X") ?: "d"   /   val addr = System.getenv("X")
((property_declaration
   (variable_declaration (simple_identifier) @var-binding.name)
   (elvis_expression
     (call_expression
       (navigation_expression
         (simple_identifier) @_o
         (navigation_suffix (simple_identifier) @_m))
       (call_suffix (value_arguments (value_argument (string_literal (string_content) @var-binding.env)))))
     (string_literal (string_content) @var-binding.envdefault)))
 (#eq? @_o "System")
 (#eq? @_m "getenv"))

((property_declaration
   (variable_declaration (simple_identifier) @var-binding.name)
   (call_expression
     (navigation_expression
       (simple_identifier) @_o
       (navigation_suffix (simple_identifier) @_m))
     (call_suffix (value_arguments (value_argument (string_literal (string_content) @var-binding.env))))))
 (#eq? @_o "System")
 (#eq? @_m "getenv"))

; val url = "http://product:8080/items"
((property_declaration
   (variable_declaration (simple_identifier) @var-binding.name)
   (string_literal (string_content) @var-binding.str .))
 (#match? @var-binding.str "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5}(/.*)?)$"))

; const val ORDER_API = "http://…"  — a URL constant, checked for references like Java's
((property_declaration
   (modifiers (property_modifier) @_c)
   (variable_declaration (simple_identifier) @url-constant.name)
   (string_literal (string_content) @url-constant.value .))
 (#eq? @_c "const")
 (#match? @url-constant.value "^https?://"))

; ---------- URL literals ----------

((string_literal (string_content) @url.value .)
 (#match? @url.value "^https?://"))

; "http://$host/path": a template whose host is a variable
((string_literal
   . (string_content) @url.scheme
   . (interpolated_identifier) @url.hostref)
 (#match? @url.scheme "^https?://$"))

; ---------- HTTP client call sites ----------
; restTemplate.getForObject("http://…", …), WebClient.builder().baseUrl("…"), client.get("…") (Ktor),
; Request.Builder().url("…") (OkHttp), HttpClient.newBuilder()…uri(…)
((call_expression
   (navigation_expression (_) (navigation_suffix (simple_identifier) @http-client.method))
   (call_suffix (value_arguments . (value_argument (string_literal (string_content) @http-client.url .)))))
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "postForLocation" "exchange"
   "baseUrl" "uri" "url" "get" "post" "put" "delete" "patch" "head" "request" "create")
 (#match? @http-client.url "^https?://"))

; The host is a variable in a template: client.get("http://$host/x")
((call_expression
   (navigation_expression (_) (navigation_suffix (simple_identifier) @http-client.method))
   (call_suffix (value_arguments . (value_argument (string_literal
     . (string_content) @http-client.scheme
     . (interpolated_identifier) @http-client.hostref)))))
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "postForLocation" "exchange"
   "baseUrl" "uri" "url" "get" "post" "put" "delete" "patch" "head" "request")
 (#match? @http-client.scheme "^https?://$"))

; The whole URL is a variable: client.get(url) / "$base/path"
((call_expression
   (navigation_expression (simple_identifier) @_recv (navigation_suffix (simple_identifier) @http-client.method))
   (call_suffix (value_arguments . (value_argument (simple_identifier) @http-client.urlref))))
 (#match? @_recv "(?i)(resttemplate|webclient|restclient|httpclient|okhttp|client|http)")
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "postForLocation" "exchange"
   "baseUrl" "uri" "url" "get" "post" "put" "delete" "patch" "head" "request"))

((call_expression
   (navigation_expression (simple_identifier) @_recv (navigation_suffix (simple_identifier) @http-client.method))
   (call_suffix (value_arguments . (value_argument (string_literal . (interpolated_identifier) @http-client.urlref)))))
 (#match? @_recv "(?i)(resttemplate|webclient|restclient|httpclient|okhttp|client|http)")
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "postForLocation" "exchange"
   "baseUrl" "uri" "url" "get" "post" "put" "delete" "patch" "head" "request"))

; ---------- Spring annotations ----------

; @FeignClient(name = "catalog", url = "http://catalog:8080")
((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument
       (simple_identifier) @feign.attr
       (string_literal (string_content) @feign.value)))))
 (#eq? @_ann "FeignClient")
 (#any-of? @feign.attr "name" "value" "url" "path" "contextId"))

((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument (string_literal (string_content) @feign.value)))))
 (#eq? @_ann "FeignClient"))

; @Value("\${order.service.url}")
((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument (string_literal (string_content) @config.property)))))
 (#eq? @_ann "Value")
 (#match? @config.property "(?i)(url|uri|host|endpoint|address)"))

; @GetMapping("/items/{id}") / @RequestMapping(path = "/items")
((annotation
   (constructor_invocation
     (user_type (type_identifier) @http-server.verb)
     (value_arguments (value_argument (string_literal (string_content) @http-server.path)))))
 (#any-of? @http-server.verb "GetMapping" "PostMapping" "PutMapping" "DeleteMapping" "PatchMapping" "RequestMapping"))

((annotation
   (constructor_invocation
     (user_type (type_identifier) @http-server.verb)
     (value_arguments (value_argument (simple_identifier) @_k (string_literal (string_content) @http-server.path)))))
 (#any-of? @http-server.verb "GetMapping" "PostMapping" "PutMapping" "DeleteMapping" "PatchMapping" "RequestMapping")
 (#any-of? @_k "value" "path"))

; ---------- Kafka / RabbitMQ ----------

; @KafkaListener(topics = ["orders"]) / @RabbitListener(queues = ["q"]) — array or single literal
((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument
       (simple_identifier) @_k
       (collection_literal (string_literal (string_content) @kafka-consume.topic))))))
 (#eq? @_ann "KafkaListener")
 (#any-of? @_k "topics" "topicPattern"))

((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument
       (simple_identifier) @_k
       (string_literal (string_content) @kafka-consume.topic)))))
 (#eq? @_ann "KafkaListener")
 (#any-of? @_k "topics" "topicPattern"))

((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument
       (simple_identifier) @_k
       (collection_literal (string_literal (string_content) @rabbit-consume.queue))))))
 (#eq? @_ann "RabbitListener")
 (#any-of? @_k "queues" "queuesToDeclare"))

((annotation
   (constructor_invocation
     (user_type (type_identifier) @_ann)
     (value_arguments (value_argument
       (simple_identifier) @_k
       (string_literal (string_content) @rabbit-consume.queue)))))
 (#eq? @_ann "RabbitListener")
 (#any-of? @_k "queues" "queuesToDeclare"))

; kafkaTemplate.send("topic", payload) / rabbitTemplate.convertAndSend("exchange", …)
((call_expression
   (navigation_expression (simple_identifier) @_recv (navigation_suffix (simple_identifier) @_m))
   (call_suffix (value_arguments . (value_argument (string_literal (string_content) @kafka-produce.topic)))))
 (#match? @_recv "(?i)(kafkatemplate|producer)")
 (#any-of? @_m "send" "sendDefault"))

((call_expression
   (navigation_expression (simple_identifier) @_recv (navigation_suffix (simple_identifier) @_m))
   (call_suffix (value_arguments . (value_argument (string_literal (string_content) @rabbit-produce.exchange)))))
 (#match? @_recv "(?i)(rabbittemplate|amqptemplate)")
 (#any-of? @_m "convertAndSend" "send" "convertSendAndReceive"))

; ---------- Persistence (JPA markers, as in java-generic) ----------
((annotation (user_type (type_identifier) @jpa.marker))
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository" "MapperScan" "Document"))

((annotation (constructor_invocation (user_type (type_identifier) @jpa.marker)))
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository" "MapperScan" "Document"))
