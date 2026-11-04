package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every closed word list the four conventions consult, in one place (2026-11-04).
 *
 * <p>The conventions decide; the lists only say how an ecosystem spells things — which
 * URI schemes name a database, which last word of a key says "host", which call sends a
 * request, which directory names never name a service. They were spread over six classes,
 * so nobody could answer "what is enumerated?" without reading all of them. Each list is
 * registered with the convention it serves, the principle behind it, the ecosystem it
 * came from and the condition for adding a word; {@link #inventoryMarkdown()} prints the
 * registry, and a test keeps {@code docs/generalization/vocabulary.md} equal to it.
 *
 * <p>The rule for growing a list: a word is added when an ecosystem's documentation
 * spells the thing that way, never because one system under test needed it.
 */
public final class Vocabulary {

    private Vocabulary() {
    }

    /** The convention a list serves. */
    public enum Convention {
        NAME_TABLE("①", "名字表", "誰能被連？部署宣告了哪些名字"),
        ADDRESS_SHAPE("②", "位址形狀", "什麼值是位址？"),
        OWNERSHIP("③", "歸屬", "這個值送到誰手上？"),
        USE("④", "使用", "程式有沒有在呼叫點用它？"),
        ADAPTER("格式", "格式轉接器", "讀得懂哪些檔案與語言；本身不判斷相依");

        public final String mark;
        public final String title;
        public final String question;

        Convention(String mark, String title, String question) {
            this.mark = mark;
            this.title = title;
            this.question = question;
        }
    }

    /** One registered list: where it is used, why it exists, and its words in declared order. */
    public record Entry(Convention convention, String name, String principle, String origin, String addWhen,
                        List<String> words) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    /**
     * Registers a list and returns it as a set that iterates in declaration order. Never
     * {@code Set.of}: its iteration order is salted per JVM run, and a list whose order
     * carries meaning ({@link #CLUSTER_SUFFIXES}: the longest suffix must be tried first)
     * would then differ from one analysis to the next (2026-11-04, found by the regression
     * run: {@code web.ns.svc.cluster.local} cut to {@code web.ns} on an unlucky start).
     */
    private static Set<String> list(Convention convention, String name, String principle, String origin,
                                    String addWhen, String... words) {
        Set<String> ordered = new LinkedHashSet<>(List.of(words));
        if (ordered.size() != words.length) throw new IllegalArgumentException(name + " repeats a word");
        ENTRIES.add(new Entry(convention, name, principle, origin, addWhen, List.of(words)));
        return Collections.unmodifiableSet(ordered);
    }

    /** The registry, in declaration order. */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    // ---------------------------------------------------------------- ② address shape

    /** Connection schemes that say the target is a database. */
    public static final Set<String> DB_SCHEMES = list(Convention.ADDRESS_SHAPE, "DB_SCHEMES",
            "URI 的 scheme 說明目標是什麼（RFC 3986 §3.1）；這些是各資料庫官方文件定義的連線 scheme，看到就把目標畫成資料庫",
            "JDBC／R2DBC、MySQL、PostgreSQL、MongoDB、Redis、Cassandra、MariaDB、SQL Server、Oracle、CouchDB、Neo4j（bolt）、Memcached",
            "某資料庫的官方文件定義了自己的連線 URL scheme",
            "jdbc", "r2dbc", "mysql", "postgres", "postgresql", "mongodb", "mongodb+srv", "redis", "rediss",
            "cassandra", "mariadb", "sqlserver", "oracle", "couchdb", "neo4j", "bolt", "memcached");

    /** Connection schemes that say the target is a message broker. */
    public static final Set<String> QUEUE_SCHEMES = list(Convention.ADDRESS_SHAPE, "QUEUE_SCHEMES",
            "同上；這些是訊息中介（broker）的連線 scheme，看到就把目標畫成 queue",
            "AMQP 0-9-1／1.0、Kafka、NATS、MQTT、STOMP",
            "某 broker 的官方文件定義了自己的連線 scheme",
            "amqp", "amqps", "kafka", "nats", "mqtt", "mqtts", "stomp");

    /** Schemes whose authority is not a network host. */
    public static final Set<String> NON_NETWORK_SCHEMES = list(Convention.ADDRESS_SHAPE, "NON_NETWORK_SCHEMES",
            "URI 的 authority 不是網路主機：檔案路徑（RFC 8089）、Spring 的 resource／config-import scheme、Vault 的 secret 路徑、瀏覽器內部 scheme。這種值不是位址",
            "RFC 8089 file、Spring Boot 外部化設定（classpath:、configtree:、optional:）、HashiCorp Vault、WHATWG URL（about:、blob:、javascript:、data:）",
            "某 scheme 的規格說它的 authority 不是主機",
            "vault", "file", "classpath", "configtree", "jar", "data", "mailto", "urn", "about", "javascript", "blob",
            "chrome", "chrome-extension");

    /** Web schemes that only name a host after {@code //}: {@code http:x} is an opaque URI, not host http. */
    public static final Set<String> URL_SCHEMES = list(Convention.ADDRESS_SHAPE, "URL_SCHEMES",
            "這些 scheme 只在 `//` 之後才有 authority；`http:${X}` 沒有 `//`，是 opaque URI（RFC 3986 §3），沒有主機，不能把 `http` 當主機名",
            "RFC 3986、RFC 7230（http／https）、RFC 6455（ws／wss）、gRPC naming、RFC 1738（ftp）、SSH URI、RFC 6068（mailto）",
            "某 web scheme 的規格採用 `scheme://authority` 形式",
            "http", "https", "ws", "wss", "grpc", "grpcs", "ftp", "sftp", "ssh", "mailto");

    /** Hosts that name the caller itself. */
    public static final Set<String> LOOPBACK = list(Convention.ADDRESS_SHAPE, "LOOPBACK",
            "指向呼叫者自己的主機名不是另一個服務；只有 port 能把它找回擁有者（規則 15）",
            "RFC 6761 localhost、RFC 5735 的 127/8 與 0.0.0.0、RFC 4291 的 ::1、Docker Desktop 的 host.docker.internal",
            "某執行環境的文件定義了一個指向本機的名字",
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]", "host.docker.internal");

    /** Top-level domains reserved for documentation, testing or mDNS. */
    public static final List<String> RESERVED_TLDS = List.copyOf(list(Convention.ADDRESS_SHAPE, "RESERVED_TLDS",
            "保留給文件、測試、mDNS 的網域不是任何人的位址",
            "RFC 2606（.test、.example、.invalid、.localhost）、RFC 6762（.local）",
            "IANA 的特殊用途網域登錄新增一個頂層網域",
            ".local", ".test", ".example", ".invalid", ".localhost"));

    /** Second-level example domains. */
    public static final List<String> RESERVED_DOMAINS = List.copyOf(list(Convention.ADDRESS_SHAPE, "RESERVED_DOMAINS",
            "同上，第二層的範例網域",
            "RFC 2606 §3",
            "IANA 新增一個範例網域",
            "example.com", "example.net", "example.org"));

    /** The last word of a key that says it holds one host. */
    public static final Set<String> HOST_WORDS = list(Convention.ADDRESS_SHAPE, "HOST_WORDS",
            "鍵名的最後一個字（或最後兩個字合起來）說它的值是一個主機；鍵名依 Spring 的 `.`、.NET 的 `:`／`__`、環境變數的 `_`、旗標的 `-`、camelCase 拆成字",
            "Spring Boot（spring.data.mongodb.host）、.NET（RabbitMq:HostName）、環境變數（DB_HOST）、Nacos（server-addr）、Eureka（serviceId）",
            "某框架的設定文件用一個新字表示「主機」",
            "host", "hostname", "addr", "address", "server", "serviceid", "serveraddr", "nameserver");

    /** The last word of a key that says it holds several {@code host:port}. */
    public static final Set<String> LIST_WORDS = list(Convention.ADDRESS_SHAPE, "LIST_WORDS",
            "鍵名說它的值是一個以上的 host:port，逗號分隔；每一個都是位址（規則 27、55）",
            "Kafka（bootstrap-servers）、RabbitMQ（addresses）、Redis Cluster（cluster-nodes）、Eureka／Consul（hosts）",
            "某 client 的設定文件用一個新字表示「主機清單」",
            "hosts", "addresses", "servers", "uris", "urls", "endpoints", "brokers", "bootstrapservers", "clusternodes");

    /** The last word of a key that says it holds a URL. */
    public static final Set<String> ADDRESS_WORDS = list(Convention.ADDRESS_SHAPE, "ADDRESS_WORDS",
            "鍵名說它的值是一個 URL；值若只是一個裸單字，要部署宣告過那個名字才算主機（規則 45）",
            "Twelve-Factor backing services、Spring（*.url／*.uri）、OpenAPI／gRPC（endpoint）",
            "某框架的設定文件用一個新字表示「URL」",
            "url", "uri", "endpoint");

    /** The last word of a key that says it holds a connection string. */
    public static final Set<String> CONNECTION_WORDS = list(Convention.ADDRESS_SHAPE, "CONNECTION_WORDS",
            "鍵名說它的值是連線字串，主機在 `key=value;` 裡（規則 32）",
            ".NET（ConnectionString）、Go／Python 的 DSN 慣例",
            "某生態系用一個新字表示「連線字串」",
            "connectionstring", "dsn");

    /** The host key of a {@code key=value;key=value} connection string. */
    public static final List<String> CONNECTION_STRING_HOST_KEYS = List.copyOf(list(Convention.ADDRESS_SHAPE,
            "CONNECTION_STRING_HOST_KEYS",
            "`key=value;` 連線字串裡，哪個鍵放主機",
            "ADO.NET SqlClient（Server、Data Source、Address、Addr、Network Address）、Npgsql（Host）、ODBC、MySQL Connector（Server）",
            "某資料庫 driver 的連線字串文件用一個新鍵放主機",
            "host", "server", "data source", "hostname", "address", "addr", "network address"));

    /**
     * Words of a key that declares who may reach this component, where to send a user's
     * browser, or the component's own public address — not a dependency.
     */
    public static final Set<String> INBOUND_WORDS = list(Convention.ADDRESS_SHAPE, "INBOUND_WORDS",
            "鍵名裡出現這些字的值是「誰能連進來」、「把使用者的瀏覽器送去哪」或「自己的公開位址」，不是這個元件要連出去的目標",
            "CORS（origins）、OAuth 2.0／OIDC（redirect_uri、post_logout_redirect_uri）、ASP.NET Core（AllowedHosts）、mkdocs（site_url）、Kafka（advertised.listeners）",
            "某規格定義了一個新的「對方連我」或「我自己的位址」的鍵",
            "origin", "origins", "cors", "redirect", "redirects", "callback", "callbacks", "logout", "postlogout",
            "site", "public", "canonical", "advertised", "self", "allowed", "trusted", "allowlist", "whitelist");

    // ---------------------------------------------------------------- ① name table

    /** Framework / library / tech-label names that are never a deployable workload. */
    public static final Set<String> LIBRARY_PHANTOMS = list(Convention.NAME_TABLE, "LIBRARY_PHANTOMS",
            "這些名字是函式庫或框架元件，不會是一個部署的 workload；圖上若出現，是把相依宣告當成了服務。資料庫引擎的名字（postgres、mysql、redis）刻意不在此：它們常常就是 workload 的名字",
            "Spring Cloud（gateway、config、loadbalancer）、Netflix OSS（eureka、hystrix、ribbon）、Resilience4j、Feign、Actuator／Micrometer／Jolokia、Lombok／MapStruct；資料庫 client 函式庫（Lettuce、Jedis、Redisson、HikariCP、psycopg、PyMongo、Mongoose、Sequelize、SQLAlchemy、JDBC／ODBC）",
            "某框架的一個元件名或某資料庫的 client 函式庫名被當成節點畫出來",
            "spring-cloud-gateway", "spring-cloud-config", "spring-cloud-loadbalancer", "netflix-eureka", "eureka-client",
            "resilience4j", "resilience4j-circuitbreaker", "hystrix", "ribbon", "feign", "openfeign", "jolokia",
            "spring-boot-actuator", "actuator", "micrometer", "lombok", "mapstruct", "spring-boot", "spring-framework",
            "lettuce", "jedis", "redisson", "hikari", "hikaricp", "psycopg2", "psycopg", "pymongo", "mongoose",
            "sequelize", "sqlalchemy", "jdbc", "odbc");

    /** Directory names that never name a service, so their files do not invent a source. */
    public static final Set<String> SOURCE_STOP = list(Convention.NAME_TABLE, "SOURCE_STOP",
            "一個檔案的來源服務由它所在的模組目錄決定；這些目錄名是語言或建置工具的慣例結構，不是服務名",
            "Maven／Gradle（src、target、build）、Go（cmd、internal、pkg、vendor）、Node（node_modules、dist）、Python（app、tests）、Kubernetes／Helm 的部署目錄慣例（manifests、k8s、charts、deploy）",
            "某語言或工具的專案結構文件定義了一個固定目錄名",
            "src", "lib", "libs", "pkg", "internal", "cmd", "app", "apps", "main", "test", "tests", "vendor", "target",
            "build", "dist", "node_modules", "common", "shared", "util", "utils", "core", "api", "web",
            "resources", "manifests", "kubernetes-manifests", "k8s", "kubernetes", "deploy", "deployment", "deployments",
            "charts", "helm", "scripts", "docs");

    /** Directory names that hold a service's files without naming it. */
    public static final Set<String> GENERIC_DIRS = list(Convention.NAME_TABLE, "GENERIC_DIRS",
            "建置標記（pom.xml、Dockerfile…）落在這些目錄時，服務名是再往上一層的目錄，不是這一層（規則 2）",
            "Maven／Gradle（src、main、build）、Go（cmd）、Docker 的慣例（docker）、常見的 server／backend／app 分層",
            "某語言或工具的專案結構文件定義了一個放檔案但不命名的目錄",
            "src", "app", "main", "docker", "build", "cmd", "server", "backend");

    /** Directory names of end-to-end suites that the {@code …tests} suffix rule does not catch. */
    public static final Set<String> TEST_ROOT_DIRS = list(Convention.NAME_TABLE, "TEST_ROOT_DIRS",
            "路徑上任一段是這些名字的模組是測試套件，不是 workload；它底下的設定檔不算任何服務的（規則 42）",
            "Jest（__tests__）、Cypress／Playwright 的 e2e 慣例、Selenium 的 automation-ui 慣例",
            "某測試框架的文件定義了一個固定的測試根目錄名",
            "automation-ui", "e2e", "__tests__");

    /** The in-cluster DNS suffixes a Service name may carry; the label before them is the namespace. */
    public static final List<String> CLUSTER_SUFFIXES = List.copyOf(list(Convention.NAME_TABLE, "CLUSTER_SUFFIXES",
            "叢集內 DNS 名的字尾；去掉字尾後，前一個 label 是 namespace，再前面才是 Service 名（規則 36）",
            "Kubernetes DNS for Services and Pods（`<svc>.<ns>.svc.cluster.local`）",
            "Kubernetes 的 DNS 規格定義了新的字尾",
            ".svc.cluster.local", ".cluster.local", ".svc"));

    /** Suffixes a Compose / Helm service name adds over the workload it runs. */
    public static final List<String> DEPLOY_NAME_SUFFIXES = List.copyOf(list(Convention.NAME_TABLE, "DEPLOY_NAME_SUFFIXES",
            "部署檔給同一個 workload 的另一種拼法：多了這些字尾（`order-service-container`）；去掉字尾後若恰好是一個已宣告的名字，就是它（規則 7）",
            "Compose／Helm 常見命名慣例",
            "某部署工具的慣例多了一種字尾",
            "-container", "-svc", "-pod", "-deployment"));

    /** Feign attributes that are bean wiring, not a target. */
    public static final Set<String> FEIGN_NON_TARGET_ATTRS = list(Convention.NAME_TABLE, "FEIGN_NON_TARGET_ATTRS",
            "`@FeignClient` 的這些屬性是 bean 接線，值不是目標服務名（規則 8）",
            "Spring Cloud OpenFeign 的 @FeignClient 屬性文件",
            "OpenFeign 新增一個不是目標名的屬性",
            "contextid", "qualifier", "qualifiers", "fallback", "fallbackfactory", "configuration", "primary",
            "decode404", "path");

    // ---------------------------------------------------------------- ④ use

    /** Ledger sections whose row is a component using an address. */
    public static final Set<String> USE_SITE_SECTIONS = list(Convention.USE, "USE_SITE_SECTIONS",
            "有這些列的那一行是使用處：client 呼叫、client 選項、producer／consumer、ORM；URL 字面值只在使用處（同一行或前後兩行）才是位址（規則 47）",
            "本工具 ledger 的 section 名",
            "某查詢樣式開始產出一種新的「使用」列",
            "http-client", "feign", "grpc", "persistence", "kafka-produce", "kafka-consume", "rabbit-produce",
            "rabbit-consume", "registry-ref", "name-ref");

    /** Methods that, called on anything ({@code .m(}), send a request or set the address a client will send to. */
    public static final List<String> CLIENT_CALL_METHODS = List.copyOf(list(Convention.USE, "CLIENT_CALL_METHODS",
            "一行呼叫了文件化的 client API，就是使用處，即使查詢樣式抓不到它的參數形狀（`getForObject(Const.URL + id)`）",
            "Spring RestTemplate／WebClient／UriComponentsBuilder、OkHttp（newCall）、java.net（openConnection）、gRPC Java（forTarget／forAddress）、.NET HttpClient（GetAsync…）、Grpc.Net（ForAddress）、grpc-python（insecure_channel）、urllib（urlopen、Request）",
            "某生態系的 HTTP／gRPC client 文件定義了一個發出請求或設定目標的方法",
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "exchange", "rootUri",
            "baseUrl", "uri", "url", "fromUriString", "fromHttpUrl", "newCall", "openConnection", "forTarget",
            "forAddress", "GetAsync", "PostAsync", "PutAsync", "DeleteAsync", "PatchAsync", "SendAsync",
            "GetFromJsonAsync", "PostAsJsonAsync", "PutAsJsonAsync", "GetStringAsync", "GetStreamAsync",
            "GetByteArrayAsync", "ForAddress", "insecure_channel", "secure_channel", "urlopen", "Request"));

    /** Modules whose any call ({@code mod.x(}) sends a request. */
    public static final List<String> CLIENT_CALL_MODULES = List.copyOf(list(Convention.USE, "CLIENT_CALL_MODULES",
            "這些模組的任何呼叫都會發出連線",
            "Python requests、httpx、aiohttp、urllib3、feedparser；requests.Session 的慣用變數名 session",
            "某 Python client 函式庫的文件說它的呼叫會發出請求",
            "requests", "httpx", "session", "aiohttp", "urllib3", "feedparser"));

    /** Fixed call forms that send a request, as regex fragments. */
    public static final List<String> CLIENT_CALL_FORMS = List.copyOf(list(Convention.USE, "CLIENT_CALL_FORMS",
            "其他固定寫法：.NET HttpClient 的 BaseAddress 指派、Selenium 的 driver.get、Serilog sink（WriteTo.*）、Apache HttpClient 的 HttpGet／HttpPost…、Python smtplib",
            ".NET HttpClient、Selenium WebDriver、Serilog、Apache HttpClient、Python smtplib",
            "某生態系的 client 文件定義了一種上面兩表寫不出來的呼叫形狀",
            "\\bBaseAddress\\s*=", "\\burlopen\\s*\\(", "\\bdriver\\.get\\s*\\(", "\\.WriteTo\\.\\w+\\s*\\(",
            "\\bHttp(?:Get|Post|Put|Patch|Delete|Head)\\s*\\(", "\\bsmtplib\\.SMTP(?:_SSL)?\\s*\\("));

    /** The client calls of each ecosystem's documented HTTP / gRPC client APIs, as one pattern. */
    public static final Pattern CLIENT_CALL = Pattern.compile(
            "(?:\\.(?:" + String.join("|", CLIENT_CALL_METHODS) + ")\\s*\\("
                    + "|\\b(?:" + String.join("|", CLIENT_CALL_MODULES) + ")\\.\\w+\\s*\\("
                    + "|" + String.join("|", CLIENT_CALL_FORMS) + ")");

    // ---------------------------------------------------------------- format adapters

    /** Files whose presence marks a directory as a buildable / deployable service. */
    public static final Set<String> BUILD_MARKERS = list(Convention.ADAPTER, "BUILD_MARKERS",
            "一個目錄有建置描述檔或 Dockerfile，就是一個可建置的單元，是服務名的候選（規則 1）",
            "Maven、Gradle、pip／Poetry／Pipenv、Go modules、npm、Bundler、Cargo、Composer、Docker",
            "某語言的建置工具定義了自己的專案描述檔名",
            "pom.xml", "build.gradle", "build.gradle.kts", "requirements.txt", "pyproject.toml", "setup.py", "Pipfile",
            "go.mod", "package.json", "Gemfile", "Cargo.toml", "composer.json", "dockerfile");

    /** Grammars added in round 4 (2026-10-27); each has a generic query pack. Java and Python are always present. */
    public static final List<String> OPTIONAL_GRAMMARS = List.copyOf(list(Convention.ADAPTER, "OPTIONAL_GRAMMARS",
            "有 tree-sitter 文法與查詢樣式的語言（Java、Python 之外）；缺文法時該語言降級，不影響其他語言",
            "tree-sitter 的官方文法",
            "為一個語言加了查詢樣式",
            "javascript", "typescript", "go", "php", "kotlin", "csharp"));

    /** Languages that keep the thin regex surface as a fallback when their grammar fails to load. */
    public static final Set<String> THIN_FALLBACK = list(Convention.ADAPTER, "THIN_FALLBACK",
            "文法載入失敗時，退回只抓 getenv／URL／listener 的薄表面（規則 28、40）",
            "本工具的降級路徑",
            "為一個語言寫了薄表面",
            "kotlin", "csharp");

    /** The packs that capture an absolute URL literal anywhere; the others capture it only at a use site. */
    public static final Set<String> ANYWHERE_URL_LANGUAGES = list(Convention.ADAPTER, "ANYWHERE_URL_LANGUAGES",
            "這些語言的查詢樣式抓任何地方的 URL 字面值，再由慣例④在使用處篩；其他語言的樣式只在呼叫點抓",
            "本工具的查詢樣式設計",
            "某語言的查詢樣式改成抓任何地方的字面值",
            "java", "csharp", "kotlin", "python");

    /** Where a key of a shared env source can be read: source code and the module's own config files. */
    public static final List<String> KEY_READ_EXTENSIONS = List.copyOf(list(Convention.ADAPTER, "KEY_READ_EXTENSIONS",
            "慣例③找「誰讀了這個鍵」時掃的副檔名：程式碼與模組自己的設定檔",
            "各語言的原始碼副檔名、常見設定檔格式",
            "支援一個新語言或設定檔格式",
            ".java", ".kt", ".cs", ".py", ".go", ".js", ".mjs", ".ts", ".php", ".rb", ".rs", ".scala", ".yml", ".yaml",
            ".properties", ".json", ".toml", ".ini", ".conf", ".xml", ".sh", ".gradle", ".kts", ".csproj"));

    // ---------------------------------------------------------------- inventory

    /** The registry as the Markdown of {@code docs/generalization/vocabulary.md}. */
    public static String inventoryMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 字詞表：四個慣例查的封閉清單\n\n");
        sb.append("由 `Vocabulary.inventoryMarkdown()` 產生，`VocabularyInventoryTest` 保證與程式碼一致；要改請改程式碼再跑 ")
                .append("`mvn -o -q test -Dtest=VocabularyInventoryTest -Dvocabulary.write=true`。\n\n");
        sb.append("慣例負責判斷；清單只記各生態系怎麼拼。新增一個詞的條件寫在每一列：是某生態系的文件這樣拼，不是某個受測系統需要。\n\n");
        Set<Convention> seen = new LinkedHashSet<>();
        for (Entry e : ENTRIES) seen.add(e.convention());
        for (Convention c : seen) {
            sb.append("## ").append(c.mark).append(' ').append(c.title).append("：").append(c.question).append("\n\n");
            sb.append("| 清單 | 詞數 | 原則 | 來源生態系 | 新增條件 | 詞 |\n|---|---|---|---|---|---|\n");
            for (Entry e : ENTRIES) {
                if (e.convention() != c) continue;
                sb.append("| `").append(e.name()).append("` | ").append(e.words().size()).append(" | ")
                        .append(e.principle()).append(" | ").append(e.origin()).append(" | ").append(e.addWhen())
                        .append(" | ");
                List<String> quoted = new ArrayList<>();
                for (String w : e.words()) quoted.add("`" + w.replace("|", "\\|") + "`");
                sb.append(String.join(" ", quoted)).append(" |\n");
            }
            sb.append('\n');
        }
        int words = 0;
        for (Entry e : ENTRIES) words += e.words().size();
        sb.append("合計 ").append(ENTRIES.size()).append(" 份清單、").append(words).append(" 個詞。\n");
        return sb.toString();
    }
}
