; Generic pack: any Java repository (Tier 2)
;
; Framework-agnostic signals. Always loaded, including alongside java-spring.scm,
; so a Spring repo also gets its bare URL literals.

; Absolute URL literals anywhere in code.
((string_literal (string_fragment) @url.value)
 (#match? @url.value "^https?://"))

; A URL kept in a static constant: `static final String ORDER_API = "http://..."`.
; Emitted beside the url row above (same line) so the extractor can check whether
; the constant is ever referenced in its module. A constants file copied into every
; service (each declaring the URL of every other service) otherwise reads as a
; complete graph — 19 of 25 such edges in one shop were never used (2026-09-22).
((field_declaration
   (modifiers) @_mods
   declarator: (variable_declarator
     name: (identifier) @url-constant.name
     value: (string_literal (string_fragment) @url-constant.value)))
 (#match? @_mods "static")
 (#match? @url-constant.value "^https?://"))

; A format string whose host placeholder is a getenv with a default:
; String.format("http://%s/shipping/", getenv("CART_ENDPOINT", "cart")). The env name and
; its default ride on the url row; the merger fills %s from the module's env (Dockerfile
; ENV, manifest env) or, failing that, the default (2026-10-23).
((method_invocation
   object: (identifier) @_o
   name: (identifier) @_m
   arguments: (argument_list
     . (string_literal (string_fragment) @url.value)
     . (method_invocation
         name: (identifier) @_g
         arguments: (argument_list
           . (string_literal (string_fragment) @url.env)
           . (string_literal (string_fragment) @url.envdefault)?))))
 (#eq? @_o "String")
 (#eq? @_m "format")
 (#match? @_g "(?i)getenv")
 (#match? @url.value "^https?://%s"))

; ---------- Named constants that stand for a service ----------
; An enum constant with a string value: PERSISTENCE("tools.descartes.teastore.persistence").
; When the value equals a module's name, a call that passes Service.PERSISTENCE names its
; callee — TeaStore dispatches every REST call this way through its own registry, and the
; static layer drew none of its seven business edges (2026-10-23). The declaration is a
; lookup row (name-constant); the reference at a call site (name-ref) is the edge.
((enum_declaration
   name: (identifier) @name-constant.type
   body: (enum_body
     (enum_constant
       name: (identifier) @name-constant.name
       arguments: (argument_list . (string_literal (string_fragment) @name-constant.value))))))

; static final String SERVICE = "catalog-service" (a non-URL value; URLs have their own rule)
((class_declaration
   name: (identifier) @name-constant.type
   body: (class_body
     (field_declaration
       (modifiers) @_mods
       declarator: (variable_declarator
         name: (identifier) @name-constant.name
         value: (string_literal (string_fragment) @name-constant.value)))))
 (#match? @_mods "static")
 (#not-match? @name-constant.value "://"))

; Type.NAME passed as an argument: loadBalanceRESTOperation(Service.PERSISTENCE, "generatedb", …)
((method_invocation
   arguments: (argument_list
     (field_access
       object: (identifier) @name-ref.type
       field: (identifier) @name-ref.name)))
 (#match? @name-ref.name "^[A-Z][A-Z0-9_]*$"))

; System.getenv("ORDER_SERVICE_URL") — service targets injected via environment.
((method_invocation
   object: (identifier) @_o
   name: (identifier) @_m
   arguments: (argument_list . (string_literal (string_fragment) @config.env)))
 (#eq? @_o "System")
 (#eq? @_m "getenv"))

; @Value("${order.service.url}") — property-indirected service targets.
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (string_literal (string_fragment) @config.property)))
 (#eq? @_ann "Value")
 (#match? @config.property "(?i)(url|uri|host|endpoint|address)"))

; ---------- Inbound HTTP endpoints: JAX-RS ----------
; @Path("/categories") on a resource class or method (Jakarta EE / Jersey / RESTEasy).
((annotation
   name: (identifier) @http-server.verb
   arguments: (annotation_argument_list
     (string_literal (string_fragment) @http-server.path)))
 (#eq? @http-server.verb "Path"))

; ---------- Persistence / JPA (a DB-USAGE signal, not a target) ----------
; These markers prove the service actually has persistence code (entities /
; repositories), which is what separates a database the service REALLY uses from
; one that is merely declared by a datasource URL in config. The captured value is
; NOT a target host — the FILE the marker is found in attributes it to a service,
; and the graph merge then upgrades that service's datasource-declared db edge from
; "declared" to "really used". @Entity/@Table are the reliable signal (every JPA
; entity carries one, Spring or not — TeaStore's Jakarta EE persistence service was
; invisible while these sat in the Spring pack); @Repository catches annotated
; Spring Data repositories.
; @MapperScan is MyBatis's mapper registration (org.mybatis.spring.annotation): the same
; "this service persists" signal for the other common Java data layer (2026-10-26,
; mall-swarm). @Mapper alone is not taken — MapStruct uses the same name for bean mapping.
((marker_annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository" "MapperScan"))

((annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository" "MapperScan"))

; ---------- OpenAPI / Swagger metadata: a URL that documents, not one that is called ----------
; new License().name("Apache 2.0").url("https://github.com/…"), new ExternalDocumentation()
; .description(…).url("http://www.macrozheng.com"), new Contact().url(…), new Info()
; .termsOfService(…), new Server().url(…); springfox's ApiInfoBuilder.termsOfServiceUrl /
; licenseUrl. The literal is an argument of a known metadata builder — that is the test,
; not the domain — and it is emitted as url-metadata so the extractor marks the matching
; url row as not an address. mall-swarm's five SpringDocConfig classes each put github.com
; and the author's site on the graph as external edges (2026-10-26).
((method_invocation
   object: (_) @_chain
   name: (identifier) @_m
   arguments: (argument_list (string_literal (string_fragment) @url-metadata.value)))
 (#any-of? @_m "url" "termsOfService" "termsOfServiceUrl" "licenseUrl")
 (#match? @_chain "^new\\s+(License|ExternalDocumentation|Contact|Info|Server|ApiInfoBuilder)\\s*\\(")
 (#match? @url-metadata.value "^https?://"))

; The constructor forms: new Contact("name", "https://…", "mail"), new License("Apache", "https://…")
((object_creation_expression
   type: (type_identifier) @_t
   arguments: (argument_list (string_literal (string_fragment) @url-metadata.value)))
 (#any-of? @_t "Contact" "License" "ExternalDocumentation")
 (#match? @url-metadata.value "^https?://"))

; The annotation forms: @OpenAPIDefinition(info = @Info(license = @License(url = "…"),
; contact = @Contact(url = "…"), termsOfService = "…"), externalDocs = @ExternalDocumentation(url = "…"))
((annotation
   name: (identifier) @_ann
   arguments: (annotation_argument_list
     (element_value_pair
       key: (identifier) @_k
       value: (string_literal (string_fragment) @url-metadata.value))))
 (#any-of? @_ann "Info" "License" "Contact" "ExternalDocumentation" "Server" "ApiInfo")
 (#any-of? @_k "url" "termsOfService" "termsOfServiceUrl" "licenseUrl")
 (#match? @url-metadata.value "^https?://"))
