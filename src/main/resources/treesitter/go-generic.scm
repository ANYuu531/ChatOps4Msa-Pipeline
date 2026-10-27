; Generic pack: any Go repository.
;
; Round 4 (2026-10-27): Go was an LLM-tier language; it now has a grammar
; (tree-sitter-go) and this pack. Same signals as the other generic packs — call
; sites, environment reads, URL literals, data-store clients — plus Go's own idioms:
;   addr := os.Getenv("X"); if addr == "" { addr = "default" }   (var-binding + var-default)
;   h := getEnv("USER_HOST", "user")  /  mustMapEnv(&svc.addr, "X")   (helper named *env*)
;   grpc.Dial(addr)  /  http.Get(url)  /  http.NewRequest("GET", url, nil)   (urlref)
;   dialer.Dial("srv-rate")  /  s.initRateClient("srv-rate")   (registry-ref: a service
;   name resolved by a registry at run time — surfaced, never guessed into a node)

; ---------- Bindings ----------

; addr := os.Getenv("CART_ADDR")   /   var addr = os.Getenv("X")
((short_var_declaration
   left: (expression_list (identifier) @var-binding.name)
   right: (expression_list (call_expression
     function: (selector_expression
       operand: (identifier) @_o
       field: (field_identifier) @_m)
     arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @var-binding.env)))))
 (#eq? @_o "os")
 (#any-of? @_m "Getenv" "LookupEnv"))

((var_declaration (var_spec
   name: (identifier) @var-binding.name
   value: (expression_list (call_expression
     function: (selector_expression
       operand: (identifier) @_o
       field: (field_identifier) @_m)
     arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @var-binding.env))))))
 (#eq? @_o "os")
 (#any-of? @_m "Getenv" "LookupEnv"))

; h := getEnv("USER_HOST", "user")   — a helper whose name says it reads the environment
((short_var_declaration
   left: (expression_list (identifier) @var-binding.name)
   right: (expression_list (call_expression
     function: (identifier) @_f
     arguments: (argument_list
       . (interpreted_string_literal (interpreted_string_literal_content) @var-binding.env)
       . (interpreted_string_literal (interpreted_string_literal_content) @var-binding.envdefault)?))))
 (#match? @_f "(?i)env")
 (#match? @var-binding.env "^[A-Z][A-Z0-9_]*$"))

; mustMapEnv(&svc.adSvcAddr, "AD_SERVICE_ADDR")   — binds the pointed-to field
((call_expression
   function: (identifier) @_f
   arguments: (argument_list
     . (unary_expression operand: (_) @var-binding.name)
     . (interpreted_string_literal (interpreted_string_literal_content) @var-binding.env)))
 (#match? @_f "(?i)env")
 (#match? @var-binding.env "^[A-Z][A-Z0-9_]*$"))

; if addr == "" { addr = "cart:7070" }   — the default idiom
((if_statement
   condition: (binary_expression
     left: (identifier) @var-default.name
     right: (interpreted_string_literal) @_empty)
   consequence: (block (assignment_statement
     left: (expression_list (identifier) @_n)
     right: (expression_list (interpreted_string_literal (interpreted_string_literal_content) @var-default.value)))))
 (#eq? @_empty "\"\"")
 (#eq? @_n @var-default.name))

; const name = "srv-search"  /  const url = "http://x"  /  addr := "cart:7070"
((const_spec
   name: (identifier) @var-binding.name
   value: (expression_list (interpreted_string_literal (interpreted_string_literal_content) @var-binding.str)))
 (#match? @var-binding.str "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*(:[0-9]{2,5})?(/.*)?)$"))

((short_var_declaration
   left: (expression_list (identifier) @var-binding.name)
   right: (expression_list (interpreted_string_literal (interpreted_string_literal_content) @var-binding.str)))
 (#match? @var-binding.str "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5}(/.*)?)$"))

; u := "http://" + h + ":8080/check"   — a URL built around a variable host
((short_var_declaration
   left: (expression_list (identifier) @var-binding.name)
   right: (expression_list (binary_expression
     left: (binary_expression
       left: (interpreted_string_literal (interpreted_string_literal_content) @var-binding.scheme)
       right: (identifier) @var-binding.hostref))))
 (#match? @var-binding.scheme "^https?://$"))

((short_var_declaration
   left: (expression_list (identifier) @var-binding.name)
   right: (expression_list (binary_expression
     left: (interpreted_string_literal (interpreted_string_literal_content) @var-binding.scheme)
     right: (identifier) @var-binding.hostref)))
 (#match? @var-binding.scheme "^https?://$"))

; ---------- Environment reads (config rows) ----------

; os.Getenv("X") / os.LookupEnv("X")
((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @_m)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @config.env)))
 (#eq? @_o "os")
 (#any-of? @_m "Getenv" "LookupEnv"))

; getEnv("X", "default") / mustMapEnv(&x, "X") / env("X"): a helper named *env* given an
; ENV_NAME-shaped string, with an optional default right after it
((call_expression
   function: (identifier) @_f
   arguments: (argument_list
     (interpreted_string_literal (interpreted_string_literal_content) @config.env)
     . (interpreted_string_literal (interpreted_string_literal_content) @config.envdefault)?))
 (#match? @_f "(?i)env")
 (#match? @config.env "^[A-Z][A-Z0-9_]*$"))

; flag.String("addr", "jaeger:6831", "…"): a command-line default that is an address
((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @_m)
   arguments: (argument_list
     . (interpreted_string_literal (interpreted_string_literal_content) @config.key)
     . (interpreted_string_literal (interpreted_string_literal_content) @config.value)))
 (#eq? @_o "flag")
 (#eq? @_m "String")
 (#match? @config.value "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5}(/.*)?)$"))

; ---------- URL literals: only at a use site ----------
; As in the JavaScript pack, a bare URL literal anywhere is not an edge: only a call's
; argument, a client option, or a binding a call site reads (rule 47).

; ---------- HTTP client call sites ----------
; http.Get(url) / http.Post(url, …) / client.Get(url) / http.NewRequest("GET", url, nil)
((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (identifier) @http-client.urlref))
 (#match? @_recv "(?i)(http|client|resty|req)")
 (#any-of? @http-client.method "Get" "Post" "Head" "PostForm" "Do" "R" "Execute"))

((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @http-client.url)))
 (#match? @_recv "(?i)(http|client|resty|req)")
 (#any-of? @http-client.method "Get" "Post" "Head" "PostForm")
 (#match? @http-client.url "^https?://"))

((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (interpreted_string_literal) . (identifier) @http-client.urlref))
 (#match? @_recv "(?i)http")
 (#any-of? @http-client.method "NewRequest"))

((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (_) . (interpreted_string_literal) . (identifier) @http-client.urlref))
 (#match? @_recv "(?i)http")
 (#any-of? @http-client.method "NewRequestWithContext"))

; url := host + "/path" at the call site: http.Get(travelsService + "/travels")
((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (binary_expression left: (identifier) @http-client.urlref right: (interpreted_string_literal))))
 (#match? @_recv "(?i)(http|client|resty|req)")
 (#any-of? @http-client.method "Get" "Post" "Head" "PostForm"))

((call_expression
   function: (selector_expression
     operand: (_) @_recv
     field: (field_identifier) @http-client.method)
   arguments: (argument_list . (interpreted_string_literal) . (binary_expression left: (identifier) @http-client.urlref)))
 (#match? @_recv "(?i)http")
 (#any-of? @http-client.method "NewRequest"))

; ---------- gRPC ----------
; grpc.Dial(addr, …) / grpc.DialContext(ctx, addr, …) / grpc.NewClient(target)
((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @grpc.method)
   arguments: (argument_list . (identifier) @grpc.urlref))
 (#eq? @_o "grpc")
 (#any-of? @grpc.method "Dial" "NewClient"))

((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @grpc.method)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @grpc.url)))
 (#eq? @_o "grpc")
 (#any-of? @grpc.method "Dial" "NewClient"))

((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @grpc.method)
   arguments: (argument_list . (_) . (identifier) @grpc.urlref))
 (#eq? @_o "grpc")
 (#eq? @grpc.method "DialContext"))

; ---------- Registry names (surfaced, not resolved) ----------
; dialer.Dial("srv-rate", …): a bare label handed to a dial helper is a name a registry
; (Consul, etcd) resolves at run time. It is recorded so the report can list it; the
; merger never turns it into a node by guessing (rule 49). A literal that reaches such a
; dial through a function parameter — s.initRateClient("srv-rate") whose body dials its
; `name` — is found by the extractor's parameter-flow pass, not by a name pattern.
((call_expression
   function: (selector_expression operand: (identifier) @_o field: (field_identifier) @_m)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @registry-ref.name)))
 (#not-any-of? @_o "grpc" "http" "net")
 (#match? @_m "^Dial(Context)?$")
 (#match? @registry-ref.name "^[A-Za-z][A-Za-z0-9-]*$"))

((call_expression
   function: (selector_expression operand: (identifier) @_o field: (field_identifier) @_m)
   arguments: (argument_list . (identifier) @registry-ref.nameref))
 (#not-any-of? @_o "grpc" "http" "net")
 (#match? @_m "^Dial(Context)?$"))

; ---------- Data-store clients ----------
; sql.Open("mysql", dsn) / gorm.Open / mgo.Dial("host:port") / mongo.Connect / redis.NewClient
((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @persistence.marker))
 (#match? @_o "(?i)^(sql|sqlx|gorm|mgo|mongo|redis|amqp|kafka|sarama|pgx|pgxpool|bun|ent)$")
 (#any-of? @persistence.marker "Open" "Dial" "Connect" "NewClient" "NewClusterClient" "NewPool" "DialConfig" "NewConsumer" "NewProducer" "NewSyncProducer"))

; mgo.Dial("mongodb-rate:27017") / amqp.Dial("amqp://…") — the address literal itself
((call_expression
   function: (selector_expression
     operand: (identifier) @_o
     field: (field_identifier) @_m)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @url.value)))
 (#match? @_o "(?i)^(mgo|mongo|redis|amqp|memcache|nats|pgx|pgxpool)$")
 (#any-of? @_m "Dial" "Connect" "New" "NewClient")
 (#match? @url.value "^([a-z]+://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5})$"))

; &redis.Options{Addr: "redis:6379"}  /  Addr: addr   (a client option: a use site)
((keyed_element
   key: (literal_element (identifier) @_k)
   value: (literal_element (interpreted_string_literal (interpreted_string_literal_content) @url.value)))
 (#match? @_k "(?i)^(addr|address|addrs|host|hosts|url|endpoint|endpoints|brokers|servers)$")
 (#match? @url.value "^([a-z]+://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5})$"))

((keyed_element
   key: (literal_element (identifier) @_k)
   value: (literal_element (identifier) @url.valueref))
 (#match? @_k "(?i)^(addr|address|host|url|endpoint)$"))

; ---------- Inbound HTTP endpoints ----------
; http.HandleFunc("/path", h) / mux.HandleFunc / r.GET("/path", h)
((call_expression
   function: (selector_expression field: (field_identifier) @http-server.verb)
   arguments: (argument_list . (interpreted_string_literal (interpreted_string_literal_content) @http-server.path)))
 (#any-of? @http-server.verb "HandleFunc" "Handle" "GET" "POST" "PUT" "DELETE" "PATCH" "Get" "Post" "Put" "Delete" "Patch")
 (#match? @http-server.path "^/"))
