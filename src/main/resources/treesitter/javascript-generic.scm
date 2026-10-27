; Generic pack: any JavaScript repository (Node.js server code and browser bundles alike).
;
; Round 4 (2026-10-27): JavaScript was an LLM-tier language; it now has a grammar
; (tree-sitter-javascript) and this pack. The pack reads the same framework-neutral
; signals the Java and Python packs read — call sites, environment reads, URL
; literals, data-store clients, listeners — plus the one thing a dynamic language
; needs: the BINDINGS a call site's URL comes from.
;
; Bindings (lookup rows, not report entries): `const GATEWAY = process.env.GATEWAY ||
; 'gateway'` is a var-binding row {name, env, envdefault}; `const BASE = 'http://x'` is
; {name, url}. A call whose URL is an identifier or a template (`axios.get(`http://${GATEWAY}
; /songs`)`, `fetch(BASE + '/x')`) captures only the identifier (hostref / urlref), and
; TreeSitterExtractor.resolveBindings follows it through the file's var-binding rows to
; the env name (plus default) or the literal — so the edge's provenance is the call site,
; as the reference edge sets expect. A reference nothing binds yields no edge.

; ---------- Bindings ----------

; const X = process.env.X || 'default'   /   process.env.X ?? 'default'
((variable_declarator
   name: (identifier) @var-binding.name
   value: (binary_expression
     left: (member_expression
       object: (member_expression
         object: (identifier) @_p
         property: (property_identifier) @_e)
       property: (property_identifier) @var-binding.env)
     right: (string (string_fragment) @var-binding.envdefault)))
 (#eq? @_p "process")
 (#eq? @_e "env"))

; const X = process.env.X   (no default; also the || <non-string> form, via the plain read below)
((variable_declarator
   name: (identifier) @var-binding.name
   value: (member_expression
     object: (member_expression
       object: (identifier) @_p
       property: (property_identifier) @_e)
     property: (property_identifier) @var-binding.env))
 (#eq? @_p "process")
 (#eq? @_e "env"))

((variable_declarator
   name: (identifier) @var-binding.name
   value: (binary_expression
     left: (member_expression
       object: (member_expression
         object: (identifier) @_p
         property: (property_identifier) @_e)
       property: (property_identifier) @var-binding.env)))
 (#eq? @_p "process")
 (#eq? @_e "env"))

; const { GATEWAY = 'gateway', PORT } = process.env   — the variable is the env name
((variable_declarator
   name: (object_pattern
     (object_assignment_pattern
       left: (shorthand_property_identifier_pattern) @var-binding.env
       right: (string (string_fragment) @var-binding.envdefault)))
   value: (member_expression
     object: (identifier) @_p
     property: (property_identifier) @_e))
 (#eq? @_p "process")
 (#eq? @_e "env"))

((variable_declarator
   name: (object_pattern (shorthand_property_identifier_pattern) @var-binding.env)
   value: (member_expression
     object: (identifier) @_p
     property: (property_identifier) @_e))
 (#eq? @_p "process")
 (#eq? @_e "env"))

; const BASE = 'http://users:8080'   /   const HOST = 'catalogue:8080'   /   const HOST = 'catalogue'
; Any literal: only a call site that reads the variable makes it an address.
((variable_declarator
   name: (identifier) @var-binding.name
   value: (string (string_fragment) @var-binding.str)))

((variable_declarator
   name: (identifier) @var-binding.name
   value: (template_string . (string_fragment) @var-binding.str .)))

; const URL = `${process.env.API_BASE_PATH}/product`  /  process.env.X + '/path'
((variable_declarator
   name: (identifier) @var-binding.name
   value: (template_string . (template_substitution (member_expression
     object: (member_expression
       object: (identifier) @_p
       property: (property_identifier) @_e)
     property: (property_identifier) @var-binding.env))))
 (#eq? @_p "process")
 (#eq? @_e "env"))

; const URL = `${BASE}/x`  /  BASE + '/x'  /  const URL = BASE
((variable_declarator
   name: (identifier) @var-binding.name
   value: (template_string . (template_substitution (identifier) @var-binding.ref))))

((variable_declarator
   name: (identifier) @var-binding.name
   value: (binary_expression left: (identifier) @var-binding.ref right: (string))))

((variable_declarator
   name: (identifier) @var-binding.name
   value: (identifier) @var-binding.ref))

; const u = `http://${HOST}:8080/x`  /  'http://' + HOST
((variable_declarator
   name: (identifier) @var-binding.name
   value: (template_string
     . (string_fragment) @var-binding.scheme
     . (template_substitution (identifier) @var-binding.hostref)))
 (#match? @var-binding.scheme "^https?://$"))

((variable_declarator
   name: (identifier) @var-binding.name
   value: (binary_expression
     left: (string (string_fragment) @var-binding.scheme)
     right: (identifier) @var-binding.hostref))
 (#match? @var-binding.scheme "^https?://$"))

; ---------- Environment reads (config rows: the env table resolves them) ----------

; process.env.X || 'default'
((binary_expression
   left: (member_expression
     object: (member_expression
       object: (identifier) @_p
       property: (property_identifier) @_e)
     property: (property_identifier) @config.env)
   right: (string (string_fragment) @config.envdefault))
 (#eq? @_p "process")
 (#eq? @_e "env"))

; process.env.X anywhere
((member_expression
   object: (member_expression
     object: (identifier) @_p
     property: (property_identifier) @_e)
   property: (property_identifier) @config.env)
 (#eq? @_p "process")
 (#eq? @_e "env"))

; ---------- URL literals: only at a use site ----------
; Unlike the Java and Python packs, a bare URL literal anywhere is NOT emitted: a JavaScript
; tree is as often a browser bundle as a server, full of links, CDN addresses and docs URLs
; that nothing calls. Java needed three rules (13, 29, unreferenced constants) to take such
; literals back out; here a URL counts only where it is used — a call's argument, a client
; option, a connection call — or through a binding a call site reads (rule 47).

; ---------- HTTP client call sites ----------
; Any call whose FIRST argument is an absolute URL literal is a call to that URL:
; fetch('http://…'), axios.get('http://…'), request('http://…'), got('http://…').
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (string (string_fragment) @http-client.url)))
 (#match? @http-client.method "(?i)(fetch|axios|request|http|got|client|api|ky|superagent|needle|\\.get$|\\.post$|\\.put$|\\.delete$|\\.patch$|\\.head$)")
 (#match? @http-client.url "^https?://"))

; `http://${HOST}/path`: the scheme is literal, the host is a variable (hostref)
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (template_string
     . (string_fragment) @http-client.scheme
     . (template_substitution (identifier) @http-client.hostref))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#match? @http-client.scheme "^https?://$"))

; `${BASE}/path`: the whole base URL is a variable (urlref)
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (template_string . (template_substitution (identifier) @http-client.urlref))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$"))

; `${process.env.X}/path`: the env read is inline
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (template_string . (template_substitution (member_expression
     object: (member_expression
       object: (identifier) @_p
       property: (property_identifier) @_e)
     property: (property_identifier) @http-client.env)))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#eq? @_p "process")
 (#eq? @_e "env"))

; 'http://' + host + ':8080/path'  (two concatenation depths)
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (binary_expression
     left: (binary_expression
       left: (binary_expression
         left: (string (string_fragment) @http-client.scheme)
         right: (identifier) @http-client.hostref)))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#match? @http-client.scheme "^https?://$"))

((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (binary_expression
     left: (binary_expression
       left: (string (string_fragment) @http-client.scheme)
       right: (identifier) @http-client.hostref))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#match? @http-client.scheme "^https?://$"))

((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (binary_expression
     left: (string (string_fragment) @http-client.scheme)
     right: (identifier) @http-client.hostref)))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#match? @http-client.scheme "^https?://$"))

; BASE + '/path'  /  process.env.X + '/path'  /  a bare identifier
((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (binary_expression
     left: (identifier) @http-client.urlref
     right: (string))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$"))

((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (binary_expression
     left: (member_expression
       object: (member_expression
         object: (identifier) @_p
         property: (property_identifier) @_e)
       property: (property_identifier) @http-client.env)
     right: (string))))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$")
 (#eq? @_p "process")
 (#eq? @_e "env"))

((call_expression
   function: (_) @http-client.method
   arguments: (arguments . (identifier) @http-client.urlref))
 (#match? @http-client.method "(?i)^(await\\s+)?(fetch|request|got|ky|needle|superagent|axios|\\$http|[A-Za-z_$.]*(http|axios|client|api|fetch|request)[A-Za-z_$]*\\.(get|post|put|delete|patch|head|request))$"))

; ---------- Connection options: { host: 'cart', port: 8080 } / { url: 'redis://…' } ----------
; An object literal whose key says it is an address: redis.createClient({ host }),
; mysql.createConnection({ host: 'mysql' }), http.request({ host: 'cart' }).
((pair
   key: (property_identifier) @config.key
   value: (string (string_fragment) @config.value))
 (#match? @config.key "(?i)^(host|hostname|baseURL|baseUrl|url|uri|endpoint|brokers?|servers|bootstrapServers)$"))

((pair
   key: (property_identifier) @config.key
   value: (identifier) @config.valueref)
 (#match? @config.key "(?i)^(host|hostname|baseURL|baseUrl|url|uri|endpoint)$"))

; ---------- Data-store / broker connection calls ----------
; mongoose.connect('mongodb://mongodb:27017/users') / amqp.connect('amqp://rabbitmq') /
; MongoClient.connect(url) / redis.createClient(url) / new Redis('redis://cache:6379'):
; the address is the connection call's first argument.
((call_expression
   function: (member_expression property: (property_identifier) @_m)
   arguments: (arguments . (string (string_fragment) @url.value)))
 (#any-of? @_m "connect" "createClient" "createConnection" "createPool" "createCluster")
 (#match? @url.value "^[a-z][a-z0-9+.-]*://"))

((call_expression
   function: (member_expression property: (property_identifier) @config.key)
   arguments: (arguments . (identifier) @config.valueref))
 (#any-of? @config.key "connect" "createClient" "createConnection" "createPool" "createCluster"))

((new_expression
   constructor: (identifier) @_c
   arguments: (arguments . (string (string_fragment) @url.value)))
 (#any-of? @_c "Redis" "MongoClient" "Pool" "Client" "Kafka" "Sequelize")
 (#match? @url.value "^[a-z][a-z0-9+.-]*://"))

; ---------- Messaging ----------

; consumer.subscribe({ topic: 'orders' })  (kafkajs)
((call_expression
   function: (member_expression property: (property_identifier) @_m)
   arguments: (arguments (object (pair
     key: (property_identifier) @_k
     value: (string (string_fragment) @kafka-consume.topic)))))
 (#eq? @_m "subscribe")
 (#any-of? @_k "topic" "topics"))

; channel.consume('order.queue', fn) / channel.assertQueue('q')  (amqplib)
((call_expression
   function: (member_expression property: (property_identifier) @_m)
   arguments: (arguments . (string (string_fragment) @rabbit-consume.queue)))
 (#any-of? @_m "consume" "assertQueue"))

; ---------- Inbound HTTP endpoints ----------
; app.get('/path', handler) / router.post('/orders', …)
((call_expression
   function: (member_expression
     object: (identifier) @_obj
     property: (property_identifier) @http-server.verb)
   arguments: (arguments . (string (string_fragment) @http-server.path)))
 (#match? @_obj "(?i)^(app|router|server|api|fastify|koa|express)$")
 (#any-of? @http-server.verb "get" "post" "put" "delete" "patch" "all" "route")
 (#match? @http-server.path "^/"))

; ---------- Persistence (a DB-USAGE signal: this service really has a data-store client) ----------
((call_expression
   function: (member_expression
     object: (identifier) @_o
     property: (property_identifier) @persistence.marker))
 (#match? @_o "(?i)^(mongoose|redis|mysql|mysql2|pg|sequelize|knex|mongodb|MongoClient|ioredis|cassandra|neo4j)$")
 (#any-of? @persistence.marker "connect" "createClient" "createConnection" "createPool" "Pool" "Client"))

((new_expression constructor: (identifier) @persistence.marker)
 (#any-of? @persistence.marker "Sequelize" "PrismaClient" "MongoClient" "Pool" "Redis" "Client" "DataSource"))
