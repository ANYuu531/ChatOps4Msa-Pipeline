; Generic pack: any PHP repository.
;
; Round 4 (2026-10-27): PHP was an LLM-tier language; it now has a grammar
; (tree-sitter-php) and this pack: getenv() with the `?:` default, $_ENV / $_SERVER
; reads, URL literals, curl / Guzzle / file_get_contents call sites, PDO / Doctrine
; connection strings, Symfony and Laravel routes. The same binding resolver as the
; other packs follows a variable URL to its assignment in the same file.

; ---------- Environment reads ----------

; getenv('CATALOGUE_URL') ?: 'http://catalogue:8080'
((conditional_expression
   condition: (function_call_expression
     function: (name) @_f
     arguments: (arguments (argument (string (string_content) @config.env))))
   alternative: (string (string_content) @config.envdefault))
 (#eq? @_f "getenv"))

; getenv('X')
((function_call_expression
   function: (name) @_f
   arguments: (arguments (argument (string (string_content) @config.env))))
 (#eq? @_f "getenv"))

; $_ENV['X'] ?? 'default'  /  $_SERVER['X'] ?: 'default'
((binary_expression
   left: (subscript_expression (variable_name (name) @_v) (string (string_content) @config.env))
   right: (string (string_content) @config.envdefault))
 (#any-of? @_v "_ENV" "_SERVER"))

((conditional_expression
   condition: (subscript_expression (variable_name (name) @_v) (string (string_content) @config.env))
   alternative: (string (string_content) @config.envdefault))
 (#any-of? @_v "_ENV" "_SERVER"))

((subscript_expression (variable_name (name) @_v) (string (string_content) @config.env))
 (#any-of? @_v "_ENV" "_SERVER"))

; ---------- Bindings ----------

; $url = getenv('X') ?: 'http://…'   /   $url = getenv('X')
((assignment_expression
   left: (variable_name (name) @var-binding.name)
   right: (conditional_expression
     condition: (function_call_expression
       function: (name) @_f
       arguments: (arguments (argument (string (string_content) @var-binding.env))))
     alternative: (string (string_content) @var-binding.envdefault)))
 (#eq? @_f "getenv"))

((assignment_expression
   left: (variable_name (name) @var-binding.name)
   right: (function_call_expression
     function: (name) @_f
     arguments: (arguments (argument (string (string_content) @var-binding.env)))))
 (#eq? @_f "getenv"))

; $base = 'http://orders:8080'
((assignment_expression
   left: (variable_name (name) @var-binding.name)
   right: (string (string_content) @var-binding.str))
 (#match? @var-binding.str "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5}(/.*)?)$"))

; $url = sprintf('%s/path', $base, …)  /  $url = $base . '/path'
((assignment_expression
   left: (variable_name (name) @var-binding.name)
   right: (function_call_expression
     function: (name) @_f
     arguments: (arguments
       . (argument (string (string_content) @_fmt))
       . (argument (variable_name (name) @var-binding.ref)))))
 (#eq? @_f "sprintf")
 (#match? @_fmt "^%s"))

((assignment_expression
   left: (variable_name (name) @var-binding.name)
   right: (binary_expression
     left: (variable_name (name) @var-binding.ref)
     right: (string)))
 )

; ---------- URL literals: only at a use site ----------
; As in the JavaScript and Go packs: a URL counts where it is used (a call's argument, a
; client option, an env default) or through a binding a call site reads (rule 47).

; ---------- HTTP client call sites ----------

; curl_init('http://…') / file_get_contents('http://…') / fopen('http://…')
((function_call_expression
   function: (name) @http-client.method
   arguments: (arguments . (argument (string (string_content) @http-client.url))))
 (#any-of? @http-client.method "curl_init" "file_get_contents" "fopen")
 (#match? @http-client.url "^https?://"))

((function_call_expression
   function: (name) @http-client.method
   arguments: (arguments . (argument (variable_name (name) @http-client.urlref))))
 (#any-of? @http-client.method "curl_init" "file_get_contents"))

; Guzzle: $client->get('http://…') / ->request('GET', 'http://…') / ->post(…)
((member_call_expression
   name: (name) @http-client.method
   arguments: (arguments . (argument (string (string_content) @http-client.url))))
 (#any-of? @http-client.method "get" "post" "put" "delete" "patch" "head" "getAsync" "postAsync" "sendRequest")
 (#match? @http-client.url "^https?://"))

((member_call_expression
   name: (name) @http-client.method
   arguments: (arguments . (argument (string)) . (argument (string (string_content) @http-client.url))))
 (#any-of? @http-client.method "request" "requestAsync")
 (#match? @http-client.url "^https?://"))

((member_call_expression
   name: (name) @http-client.method
   arguments: (arguments . (argument (variable_name (name) @http-client.urlref))))
 (#any-of? @http-client.method "get" "post" "put" "delete" "patch" "head"))

; curl_setopt($ch, CURLOPT_URL, $url)  /  curl_setopt($ch, CURLOPT_URL, 'http://…')
((function_call_expression
   function: (name) @_f
   arguments: (arguments . (argument) . (argument (name) @_opt) . (argument (variable_name (name) @http-client.urlref))))
 (#eq? @_f "curl_setopt")
 (#eq? @_opt "CURLOPT_URL"))

((function_call_expression
   function: (name) @_f
   arguments: (arguments . (argument) . (argument (name) @_opt) . (argument (string (string_content) @http-client.url))))
 (#eq? @_f "curl_setopt")
 (#eq? @_opt "CURLOPT_URL")
 (#match? @http-client.url "^https?://"))

; ['base_uri' => 'http://orders:8080']  (Guzzle client options)
((array_element_initializer
   (string (string_content) @config.key)
   (string (string_content) @config.value))
 (#match? @config.key "(?i)^(base_uri|baseuri|base_url|host|hostname|url|uri|endpoint|dsn)$"))

; ---------- Persistence ----------
; new PDO('mysql:host=mysql;dbname=…')  — the DSN names the host like a connection string
((object_creation_expression
   (name) @persistence.marker
   (arguments . (argument (string (string_content) @config.value))))
 (#any-of? @persistence.marker "PDO" "mysqli" "MongoClient" "Redis" "Predis" "Client")
 (#match? @config.value "(host=|://|^[a-z0-9.-]+:[0-9]{2,5}$)"))

((object_creation_expression (name) @persistence.marker)
 (#any-of? @persistence.marker "PDO" "mysqli" "EntityManager" "Redis" "Predis"))

((object_creation_expression (qualified_name (name) @persistence.marker .))
 (#any-of? @persistence.marker "PDO" "EntityManager" "Redis" "Client"))

; ---------- Inbound HTTP endpoints ----------
; Symfony: #[Route('/path')]    Laravel: Route::get('/path', …)
((attribute
   (name) @http-server.verb
   parameters: (arguments . (argument (string (string_content) @http-server.path))))
 (#match? @http-server.verb "^(Route|Get|Post|Put|Delete|Patch)$")
 (#match? @http-server.path "^/"))

((scoped_call_expression
   scope: (name) @_s
   name: (name) @http-server.verb
   arguments: (arguments . (argument (string (string_content) @http-server.path))))
 (#eq? @_s "Route")
 (#any-of? @http-server.verb "get" "post" "put" "delete" "patch" "any" "match")
 (#match? @http-server.path "^/"))
