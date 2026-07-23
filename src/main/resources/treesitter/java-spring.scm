; Framework pack: Java / Spring Boot (Tier 1)
;
; Capture-name convention (parsed by TreeSitterExtractor, no Java changes needed
; to add a pattern):
;   <section>.<field>  -> emitted as a ledger field under that section
;   _name              -> helper capture, used by predicates only, never emitted
;
; Predicates (#eq? #match? #any-of? ...) are evaluated by TreeSitterQueryEngine,
; NOT by the native library. Do not assume an unguarded pattern is safe.

; ---------- Feign ----------

; @FeignClient(name = "catalog", url = "http://catalog:8080")
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @feign.attr
       value: (string_literal (string_fragment) @feign.value))))
 (#eq? @_ann "FeignClient")
 (#any-of? @feign.attr "name" "value" "url" "path" "contextId"))

; @FeignClient("catalog")
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (string_literal (string_fragment) @feign.value)))
 (#eq? @_ann "FeignClient"))

; ---------- Synchronous HTTP clients ----------

; The URL is anchored to the FIRST argument (`.`) in both patterns below. Without
; the anchor, any string argument matches, so getForObject("http://x/{id}", C.class, "123")
; would report "123" as a URL. It must also look like a URL or a path, which keeps
; unrelated single-string calls out of the ledger.

; Receiver-qualified: restTemplate.getForObject("http://..."), webClient.baseUrl("...")
((method_invocation
   object: (identifier) @_recv
   name: (identifier) @http-client.method
   arguments: (argument_list . (string_literal (string_fragment) @http-client.url)))
 (#match? @_recv "(?i)(resttemplate|webclient|restclient|httpclient|okhttp)")
 (#match? @http-client.url "^(https?://|/)"))

; Method-name-qualified: catches chained builders such as
; WebClient.builder().baseUrl("http://x") where the receiver is not an identifier.
((method_invocation
   name: (identifier) @http-client.method
   arguments: (argument_list . (string_literal (string_fragment) @http-client.url)))
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "postForLocation"
   "exchange" "baseUrl" "uri")
 (#match? @http-client.url "^(https?://|/)"))

; Concatenated URI: webClientBuilder.build().get().uri(hostname + "pets/visits?petId={petId}")
; A very common idiom — the host lives in a field or a property and only the path
; is a literal here. Only the path is captured; it is reported as a path (not a
; url) so the report does not claim to know the host from this expression alone.
; The host itself is picked up separately as a URL literal / config key.
((method_invocation
   name: (identifier) @http-client.method
   arguments: (argument_list . (binary_expression
     right: (string_literal (string_fragment) @http-client.path))))
 (#any-of? @http-client.method
   "getForObject" "getForEntity" "postForObject" "postForEntity" "exchange" "baseUrl" "uri"))

; ---------- Inbound HTTP endpoints (the service's own API surface) ----------
; This is what traffic generation aims at: to observe an edge, a request has to
; actually reach the endpoint that makes the downstream call.

; @GetMapping("/items/{id}")
((annotation
   name: (identifier) @http-server.verb
   arguments: (annotation_argument_list
     (string_literal (string_fragment) @http-server.path)))
 (#any-of? @http-server.verb
   "GetMapping" "PostMapping" "PutMapping" "DeleteMapping" "PatchMapping" "RequestMapping"))

; @RequestMapping(path = "/items", method = RequestMethod.GET)
((annotation
   name: (identifier) @http-server.verb
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (string_literal (string_fragment) @http-server.path))))
 (#any-of? @http-server.verb
   "GetMapping" "PostMapping" "PutMapping" "DeleteMapping" "PatchMapping" "RequestMapping")
 (#any-of? @_k "value" "path"))

; ---------- Kafka ----------

; kafkaTemplate.send("order-created", payload)  -- topic is the first argument
((method_invocation
   object: (identifier) @_recv
   name: (identifier) @_m
   arguments: (argument_list . (string_literal (string_fragment) @kafka-produce.topic)))
 (#match? @_recv "(?i)(kafkatemplate|producer)")
 (#any-of? @_m "send" "sendDefault" "sendOffsetsToTransaction"))

; @KafkaListener(topics = "order-created")
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (string_literal (string_fragment) @kafka-consume.topic))))
 (#eq? @_ann "KafkaListener")
 (#any-of? @_k "topics" "topicPattern"))

; @KafkaListener(topics = {"order-created", "order-paid"})
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (element_value_array_initializer
         (string_literal (string_fragment) @kafka-consume.topic)))))
 (#eq? @_ann "KafkaListener")
 (#any-of? @_k "topics" "topicPattern"))

; ---------- RabbitMQ ----------

; rabbitTemplate.convertAndSend("exchange", "routingKey", payload)
; The routing key is optional so the 2-arg convertAndSend(queue, payload) form
; also matches, binding only the exchange capture.
((method_invocation
   object: (identifier) @_recv
   name: (identifier) @_m
   arguments: (argument_list
     . (string_literal (string_fragment) @rabbit-produce.exchange)
     . (string_literal (string_fragment) @rabbit-produce.routingKey)?))
 (#match? @_recv "(?i)(rabbittemplate|amqptemplate)")
 (#any-of? @_m "convertAndSend" "send" "convertSendAndReceive"))

; @RabbitListener(queues = "order.queue")
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (string_literal (string_fragment) @rabbit-consume.queue))))
 (#eq? @_ann "RabbitListener")
 (#any-of? @_k "queues" "queuesToDeclare"))

; @RabbitListener(queues = {"a", "b"})
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (element_value_array_initializer
         (string_literal (string_fragment) @rabbit-consume.queue)))))
 (#eq? @_ann "RabbitListener")
 (#any-of? @_k "queues" "queuesToDeclare"))

; ---------- Persistence / JPA (a DB-USAGE signal, not a target) ----------
; These markers prove the service actually has persistence code (entities /
; repositories), which is what separates a database the service REALLY uses from
; one that is merely declared by a datasource URL in config. The captured value is
; NOT a target host — the FILE the marker is found in attributes it to a service,
; and the graph merge then upgrades that service's datasource-declared db edge from
; "declared" to "really used". @Entity/@Table are the reliable signal (every JPA
; entity carries one); @Repository catches annotated Spring Data repositories.
((marker_annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository"))

((annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository"))
