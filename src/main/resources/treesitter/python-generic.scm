; Generic pack: any Python repository (Tier 2)

; Absolute URL literals anywhere in code.
((string (string_content) @url.value)
 (#match? @url.value "^https?://"))

; os.getenv("DETAILS_HOSTNAME") / os.environ.get("REVIEWS_URL")
; Service targets are very commonly injected this way in Python microservices.
((call
   function: (attribute
     object: (identifier) @_o
     attribute: (identifier) @_m)
   arguments: (argument_list . (string (string_content) @config.env)))
 (#eq? @_o "os")
 (#any-of? @_m "getenv" "environ"))

; os.environ["REVIEWS_URL"]
((subscript
   value: (attribute
     object: (identifier) @_o
     attribute: (identifier) @_a)
   subscript: (string (string_content) @config.env))
 (#eq? @_o "os")
 (#eq? @_a "environ"))

; os.environ.get("REVIEWS_URL")
((call
   function: (attribute
     object: (attribute
       object: (identifier) @_o
       attribute: (identifier) @_a)
     attribute: (identifier) @_m)
   arguments: (argument_list . (string (string_content) @config.env)))
 (#eq? @_o "os")
 (#eq? @_a "environ")
 (#eq? @_m "get"))
