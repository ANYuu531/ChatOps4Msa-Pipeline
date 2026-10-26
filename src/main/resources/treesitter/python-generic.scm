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

; ---------- Imports (who uses a library module) ----------
; `from pkg.sub import f` / `import pkg.sub`. A lookup signal, not an edge: a Python
; distribution with no image of its own runs inside the services that import it, so a
; call in pkg/sub.py is attributed to the modules importing pkg.sub (CodeGraphMerger).
((import_from_statement
   module_name: (dotted_name) @import.module
   name: (dotted_name) @import.name))

((import_from_statement
   module_name: (dotted_name) @import.module
   name: (aliased_import name: (dotted_name) @import.name)))

; from . import x / from .sub import y — resolved against the importing file's package
((import_from_statement
   module_name: (relative_import) @import.relative
   name: (dotted_name) @import.name))

((import_statement
   name: (dotted_name) @import.module))

((import_statement
   name: (aliased_import name: (dotted_name) @import.module)))

; ---------- Data-store clients whose host is a default parameter ----------
; def __init__(self, host='redis-service.aics.svc.cluster.local', port=6379): the
; library's connection helper names the store in a parameter default. Emitted as a
; config row under a host-shaped key so the merger reads it like DB_HOST.
((default_parameter
   name: (identifier) @config.key
   value: (string (string_content) @config.value))
 (#match? @config.key "(?i)^(host|hostname|db_host|redis_host|mongo_host)$")
 (#match? @config.value "^[a-z][a-z0-9.-]*[a-z0-9]$"))

; ---------- Persistence / ORM models (proves the service really uses a DB) ----------
; The language-neutral persistence signal: a mapped model class is emitted into the
; `persistence` section, exactly like Java's @Entity is emitted into `jpa` — the merger
; treats both the same (CodeGraphMerger.PERSISTENCE_SECTIONS). Recall over precision:
; any ONE of these markers is enough, and a stray match is inert unless the service also
; declares a datasource (then the promotion it triggers is the correct one).

; SQLAlchemy declarative / Django model via a bare base class:
;   class User(Base):  /  class User(DeclarativeBase):  /  class User(Model):
((class_definition
   name: (identifier) @persistence.model
   superclasses: (argument_list (identifier) @_base))
 (#any-of? @_base "Base" "DeclarativeBase" "Model"))

; Django (and dotted SQLAlchemy) via an attribute base class:
;   class User(models.Model):  /  class User(orm.DeclarativeBase):
((class_definition
   name: (identifier) @persistence.model
   superclasses: (argument_list (attribute attribute: (identifier) @_base)))
 (#any-of? @_base "Model" "DeclarativeBase"))

; SQLAlchemy model identified by its table mapping, regardless of how Base is named:
;   class User(...): __tablename__ = "users"
((class_definition
   name: (identifier) @persistence.model
   body: (block
     (expression_statement
       (assignment left: (identifier) @_attr))))
 (#eq? @_attr "__tablename__"))

; SQLAlchemy Core / imperative mapping: Table('users', metadata, Column(...), ...)
;   users = Table('users', MetaData(), Column('id', Integer), Column('name', String))
; This is the non-declarative style (no Base / no __tablename__) — used e.g. by
; Google Bank of Anthos. Requiring a Column(...) argument distinguishes a mapped
; table from an unrelated Table(...) call (rich/prettytable), keeping precision.
((call
   function: (identifier) @_fn
   arguments: (argument_list
     . (string (string_content) @persistence.table)
     (call function: (identifier) @_col)))
 (#eq? @_fn "Table")
 (#eq? @_col "Column"))
