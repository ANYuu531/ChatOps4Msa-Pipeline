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
((marker_annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository"))

((annotation name: (identifier) @jpa.marker)
 (#any-of? @jpa.marker "Entity" "Table" "MappedSuperclass" "Embeddable" "Repository"))
