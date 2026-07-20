; Framework pack: Python web / messaging (Tier 1)
; Covers requests, httpx, aiohttp, kafka-python / aiokafka, pika.
;
; Note: Python string content is (string (string_content)); an f-string with an
; interpolation yields several children, so only the literal prefix is captured.
; That is the honest result — the interpolated part is not a literal.

; ---------- Synchronous HTTP clients ----------

; requests.get("http://inventory:8000/stock"), httpx.post(...), session.get(...)
((call
   function: (attribute
     object: (identifier) @_recv
     attribute: (identifier) @http-client.method)
   arguments: (argument_list . (string (string_content) @http-client.url)))
 (#match? @_recv "(?i)(requests|httpx|aiohttp|session|client|http)")
 (#any-of? @http-client.method
   "get" "post" "put" "delete" "patch" "head" "options" "request" "stream"))

; urlopen("http://..."), requests.request("GET", "http://...")
((call
   function: (identifier) @_f
   arguments: (argument_list . (string (string_content) @http-client.url)))
 (#any-of? @_f "urlopen" "urlretrieve"))

; ---------- Kafka ----------

; producer.send("order-created", value=...)
((call
   function: (attribute
     object: (identifier) @_recv
     attribute: (identifier) @_m)
   arguments: (argument_list . (string (string_content) @kafka-produce.topic)))
 (#match? @_recv "(?i)(producer|kafka)")
 (#any-of? @_m "send" "produce" "send_and_wait"))

; KafkaConsumer("order-created", ...) / AIOKafkaConsumer("...")
((call
   function: (identifier) @_f
   arguments: (argument_list . (string (string_content) @kafka-consume.topic)))
 (#match? @_f "(?i)^(aio)?kafkaconsumer$"))

; consumer.subscribe(["order-created"])
((call
   function: (attribute
     object: (identifier) @_recv
     attribute: (identifier) @_m)
   arguments: (argument_list (list (string (string_content) @kafka-consume.topic))))
 (#match? @_recv "(?i)(consumer|kafka)")
 (#eq? @_m "subscribe"))

; ---------- Inbound HTTP endpoints (the service's own API surface) ----------
; @app.get("/items/{id}")  /  @router.post("/orders")  /  @app.route("/cart")

((decorator
   (call
     function: (attribute
       object: (identifier) @_obj
       attribute: (identifier) @http-server.verb)
     arguments: (argument_list . (string (string_content) @http-server.path))))
 (#match? @_obj "(?i)^(app|router|bp|blueprint|api)$")
 (#any-of? @http-server.verb "get" "post" "put" "delete" "patch" "route"))

; ---------- RabbitMQ (pika) ----------

; channel.basic_publish(exchange="orders", routing_key="order.created", body=...)
((call
   function: (attribute attribute: (identifier) @_m)
   arguments: (argument_list
     (keyword_argument
       name: (identifier) @rabbit-produce.attr
       value: (string (string_content) @rabbit-produce.value))))
 (#eq? @_m "basic_publish")
 (#any-of? @rabbit-produce.attr "exchange" "routing_key"))

; channel.basic_consume(queue="order.queue", ...)
((call
   function: (attribute attribute: (identifier) @_m)
   arguments: (argument_list
     (keyword_argument
       name: (identifier) @_k
       value: (string (string_content) @rabbit-consume.queue))))
 (#any-of? @_m "basic_consume" "queue_declare")
 (#eq? @_k "queue"))
