; Generic pack: any C# repository (ASP.NET Core, .NET workers).
;
; Round 4 (2026-10-27): C# was read with a thin regex surface (rule 40); it now has a
; grammar (tree-sitter-c-sharp) and this pack. IConfiguration reads keep the thin
; surface's contract — the key `A:B` is emitted as `dotnetkey`, and the extractor
; writes the environment spelling `A__B` into `env`, so the merger's env table (from
; appsettings.json and the Compose override) resolves it as before. Added over the
; thin surface: the call sites themselves (HttpClient.BaseAddress / GetAsync /
; AddHttpClient / AddGrpcClient / Refit / MassTransit Host), variable URLs through the
; binding resolver, URL literals, inbound routes and persistence markers.

; ---------- Configuration reads ----------

; Configuration["GrpcSettings:DiscountUrl"]  /  builder.Configuration["X"]  /  config["X"]
((element_access_expression
   expression: (_) @_recv
   subscript: (bracketed_argument_list (argument (string_literal (string_literal_content) @config.dotnetkey))))
 (#match? @_recv "(?i)(configuration|config|settings|conf)$"))

; GetValue<T>("A:B") / GetSection("A:B") / GetRequiredSection("A:B")
((invocation_expression
   function: (member_access_expression name: (generic_name (identifier) @_m))
   arguments: (argument_list . (argument (string_literal (string_literal_content) @config.dotnetkey))))
 (#any-of? @_m "GetValue" "GetRequiredValue"))

((invocation_expression
   function: (member_access_expression name: (identifier) @_m)
   arguments: (argument_list . (argument (string_literal (string_literal_content) @config.dotnetkey))))
 (#any-of? @_m "GetSection" "GetRequiredSection" "GetValue"))

; GetConnectionString("Database") → ConnectionStrings:Database
((invocation_expression
   function: (member_access_expression name: (identifier) @_m)
   arguments: (argument_list . (argument (string_literal (string_literal_content) @config.connstr))))
 (#eq? @_m "GetConnectionString"))

; Environment.GetEnvironmentVariable("X") ?? "default"
((binary_expression
   left: (invocation_expression
     function: (member_access_expression name: (identifier) @_m)
     arguments: (argument_list . (argument (string_literal (string_literal_content) @config.env))))
   right: (string_literal (string_literal_content) @config.envdefault))
 (#eq? @_m "GetEnvironmentVariable"))

((invocation_expression
   function: (member_access_expression name: (identifier) @_m)
   arguments: (argument_list . (argument (string_literal (string_literal_content) @config.env))))
 (#eq? @_m "GetEnvironmentVariable"))

; ---------- Bindings ----------

; var redis = Environment.GetEnvironmentVariable("REDIS_ADDR") ?? "redis:6379"
((variable_declarator
   name: (identifier) @var-binding.name
   (binary_expression
     left: (invocation_expression
       function: (member_access_expression name: (identifier) @_m)
       arguments: (argument_list . (argument (string_literal (string_literal_content) @var-binding.env))))
     right: (string_literal (string_literal_content) @var-binding.envdefault)))
 (#eq? @_m "GetEnvironmentVariable"))

((variable_declarator
   name: (identifier) @var-binding.name
   (invocation_expression
     function: (member_access_expression name: (identifier) @_m)
     arguments: (argument_list . (argument (string_literal (string_literal_content) @var-binding.env)))))
 (#eq? @_m "GetEnvironmentVariable"))

; var s = Configuration["A:B"]  — bound to the configuration key (env spelling A__B)
((variable_declarator
   name: (identifier) @var-binding.name
   (element_access_expression
     expression: (_) @_recv
     subscript: (bracketed_argument_list (argument (string_literal (string_literal_content) @var-binding.dotnetkey)))))
 (#match? @_recv "(?i)(configuration|config|settings|conf)$"))

; var url = "http://…"
((variable_declarator
   name: (identifier) @var-binding.name
   (string_literal (string_literal_content) @var-binding.str))
 (#match? @var-binding.str "^(https?://[^\\s]+|[a-z0-9][a-z0-9.-]*:[0-9]{2,5}(/.*)?)$"))

; ---------- URL literals ----------

((string_literal (string_literal_content) @url.value)
 (#match? @url.value "^https?://"))

; $"http://{host}/x" — the host is a variable
((interpolated_string_expression
   . (interpolation_start)
   . (string_content) @url.scheme
   . (interpolation (identifier) @url.hostref))
 (#match? @url.scheme "^https?://$"))

; ---------- HTTP / gRPC client call sites ----------

; _httpClient.GetAsync("http://…") / PostAsync / GetFromJsonAsync / SendAsync / GetStringAsync
((invocation_expression
   function: (member_access_expression name: (_) @http-client.method)
   arguments: (argument_list . (argument (string_literal (string_literal_content) @http-client.url))))
 (#match? @http-client.method "^(Get|Post|Put|Delete|Patch|Send)[A-Za-z]*Async$")
 (#match? @http-client.url "^https?://"))

((invocation_expression
   function: (member_access_expression name: (_) @http-client.method)
   arguments: (argument_list . (argument (identifier) @http-client.urlref)))
 (#match? @http-client.method "^(Get|Post|Put|Delete|Patch|Send)[A-Za-z]*Async$"))

((invocation_expression
   function: (member_access_expression name: (_) @http-client.method)
   arguments: (argument_list . (argument (interpolated_string_expression
     . (interpolation_start)
     . (string_content) @http-client.scheme
     . (interpolation (identifier) @http-client.hostref)))))
 (#match? @http-client.method "^(Get|Post|Put|Delete|Patch|Send)[A-Za-z]*Async$")
 (#match? @http-client.scheme "^https?://$"))

; c.BaseAddress = new Uri("http://…")  /  o.Address = new Uri(configuration["X"])  /  new Uri(url)
((assignment_expression
   left: (member_access_expression name: (identifier) @http-client.method)
   right: (object_creation_expression
     type: (identifier) @_t
     arguments: (argument_list . (argument (string_literal (string_literal_content) @http-client.url)))))
 (#any-of? @http-client.method "BaseAddress" "Address")
 (#eq? @_t "Uri")
 (#match? @http-client.url "^https?://"))

((assignment_expression
   left: (member_access_expression name: (identifier) @http-client.method)
   right: (object_creation_expression
     type: (identifier) @_t
     arguments: (argument_list . (argument (identifier) @http-client.urlref))))
 (#any-of? @http-client.method "BaseAddress" "Address")
 (#eq? @_t "Uri"))

; Refit: RestService.For<IApi>("http://…")
((invocation_expression
   function: (member_access_expression
     expression: (identifier) @_recv
     name: (generic_name (identifier) @http-client.method))
   arguments: (argument_list . (argument (string_literal (string_literal_content) @http-client.url))))
 (#eq? @_recv "RestService")
 (#eq? @http-client.method "For")
 (#match? @http-client.url "^https?://"))

; MassTransit: cfg.Host("rabbitmq", "/", h => …) — a bare host; cfg.Host(new Uri(…)) is caught by the Uri rules
((invocation_expression
   function: (member_access_expression name: (identifier) @_m)
   arguments: (argument_list . (argument (string_literal (string_literal_content) @config.value))))
 (#eq? @_m "Host")
 (#match? @config.value "^([a-z0-9][a-z0-9.-]*(:[0-9]{2,5})?|[a-z]+://.*)$")
 (#set! config.key "host"))

; ---------- Inbound HTTP endpoints ----------
; [HttpGet("/api/v1/x")] / [Route("api/[controller]")]
((attribute
   name: (identifier) @http-server.verb
   (attribute_argument_list (attribute_argument (string_literal (string_literal_content) @http-server.path))))
 (#match? @http-server.verb "^(HttpGet|HttpPost|HttpPut|HttpDelete|HttpPatch|Route)$"))

; ---------- Persistence (Entity Framework / Marten / Dapper connection setup) ----------
((invocation_expression
   function: (member_access_expression name: (generic_name (identifier) @persistence.marker)))
 (#any-of? @persistence.marker "AddDbContext" "AddDbContextPool" "AddDbContextFactory" "AddMarten"))

((invocation_expression
   function: (member_access_expression name: (identifier) @persistence.marker))
 (#any-of? @persistence.marker "UseSqlServer" "UseNpgsql" "UseMySql" "UseMySQL" "UseSqlite" "UseMongoDB" "UseCosmos" "AddMarten" "AddStackExchangeRedisCache"))

((class_declaration (base_list (identifier) @persistence.marker))
 (#match? @persistence.marker "DbContext$"))
