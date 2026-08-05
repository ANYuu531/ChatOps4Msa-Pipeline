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
