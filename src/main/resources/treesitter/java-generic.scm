; Generic pack: any Java repository (Tier 2)
;
; Framework-agnostic signals. Always loaded, including alongside java-spring.scm,
; so a Spring repo also gets its bare URL literals.

; Absolute URL literals anywhere in code.
((string_literal (string_fragment) @url.value)
 (#match? @url.value "^https?://"))

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
