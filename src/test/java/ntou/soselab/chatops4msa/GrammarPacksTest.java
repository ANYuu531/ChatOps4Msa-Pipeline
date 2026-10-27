package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.DetectedStack;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.EdgeLedger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.StackDetector;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.TreeSitterExtractor;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.CodeGraphMerger;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSTree;
import org.treesitter.TSInputEncoding;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPhp;
import org.treesitter.TreeSitterTypescript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 4 (2026-10-27): the six grammars that replaced the LLM tier (JavaScript,
 * TypeScript, Go, PHP) and the thin regex surfaces (Kotlin, C#). Each grammar must load on
 * the running platform and parse a snippet without error, each query pack must compile
 * against its grammar (an unknown node type fails at load time), and the packs must emit
 * the capture vocabulary the Java/Python packs already use, so the merger needs no
 * per-language code. Rule 47's binding resolver is exercised through every language.
 */
class GrammarPacksTest {

    private static void write(Path dir, String relative, String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<EdgeLedger.Edge> section(EdgeLedger ledger, String section) {
        return ledger.getEdges().stream().filter(e -> e.section.equals(section)).toList();
    }

    private static EdgeLedger extract(Path dir, String language) {
        DetectedStack stack = new StackDetector().detect(dir).stream()
                .filter(s -> s.language.equals(language)).findFirst().orElse(null);
        assertNotNull(stack, language + " is detected");
        assertEquals(DetectedStack.Tier.GENERIC, stack.tier);
        EdgeLedger ledger = new EdgeLedger();
        TreeSitterExtractor extractor = new TreeSitterExtractor();
        assertTrue(extractor.supports(language));
        extractor.extract(dir, stack, ledger);
        assertTrue(ledger.getEdges().stream().noneMatch(e -> e.section.startsWith("var-")), "bindings are consumed");
        return ledger;
    }

    private static boolean row(EdgeLedger ledger, String section, Map<String, String> fields) {
        return section(ledger, section).stream().anyMatch(e -> fields.entrySet().stream()
                .allMatch(f -> f.getValue().equals(e.fields.get(f.getKey()))));
    }

    @Test
    void everyGrammarLoadsParsesAndCompilesItsPack() throws Exception {
        Map<String, Object[]> cases = Map.of(
                "javascript", new Object[]{new TreeSitterJavascript(), "javascript-generic", "const a = process.env.A || 'x';\nfetch(`http://${a}/b`);\n"},
                "typescript", new Object[]{new TreeSitterTypescript(), "javascript-generic", "const a: string = process.env.A ?? 'x';\nexport async function f(): Promise<void> { await fetch(a); }\n"},
                "go", new Object[]{new TreeSitterGo(), "go-generic", "package main\nimport \"os\"\nfunc main() { a := os.Getenv(\"A\"); if a == \"\" { a = \"x:1\" } }\n"},
                "php", new Object[]{new TreeSitterPhp(), "php-generic", "<?php\n$u = getenv('A') ?: 'http://x';\n$c = curl_init($u);\n"},
                "kotlin", new Object[]{new TreeSitterKotlin(), "kotlin-generic", "val a = System.getenv(\"A\") ?: \"x\"\nfun main() { println(a) }\n"},
                "csharp", new Object[]{new TreeSitterCSharp(), "csharp-generic", "var a = builder.Configuration[\"A:B\"];\nclass C { }\n"});
        for (Map.Entry<String, Object[]> c : cases.entrySet()) {
            TSLanguage language = (TSLanguage) c.getValue()[0];
            TSParser parser = new TSParser();
            parser.setLanguage(language);
            TSTree tree = parser.parseStringEncoding(null, (String) c.getValue()[2], TSInputEncoding.TSInputEncodingUTF8);
            assertFalse(tree.getRootNode().hasError(), c.getKey() + " parses a trivial snippet");
            String pack = new String(getClass().getClassLoader()
                    .getResourceAsStream("treesitter/" + c.getValue()[1] + ".scm").readAllBytes(), StandardCharsets.UTF_8);
            new TSQuery(language, pack); // throws on an unknown node type or field
        }
    }

    @Test
    void javascriptCallSitesResolveThroughBindings(@TempDir Path dir) throws Exception {
        // robot-shop cart (rule 47: 'http://' + catalogueHost + ':8080/…' with the host an env read)
        write(dir, "cart/package.json", "{ \"name\": \"cart\" }\n");
        write(dir, "cart/server.js", """
                const redis = require('redis');
                var redisHost = process.env.REDIS_HOST || 'redis'
                var catalogueHost = process.env.CATALOGUE_HOST || 'catalogue'
                const GATEWAY = process.env.GATEWAY || 'gateway';
                function f(sku) {
                    request('http://' + catalogueHost + ':8080/product/' + sku, (err, res, body) => {});
                    axios.get(`http://${GATEWAY}/songs/top`, { headers });
                    console.log('see https://example.org/docs');
                }
                var redisClient = redis.createClient({ host: redisHost });
                mongoose.connect('mongodb://mongodb:27017/users');
                """);
        EdgeLedger ledger = extract(dir, "javascript");
        assertTrue(row(ledger, "http-client", Map.of("env", "CATALOGUE_HOST", "envdefault", "http://catalogue")), "scheme + host default");
        assertTrue(row(ledger, "http-client", Map.of("env", "GATEWAY", "envdefault", "http://gateway")));
        assertTrue(row(ledger, "config", Map.of("key", "host", "env", "REDIS_HOST", "envdefault", "redis", "usesite", "true")));
        assertTrue(row(ledger, "url", Map.of("value", "mongodb://mongodb:27017/users")), "a connection call's URL");
        assertTrue(section(ledger, "url").stream().noneMatch(e -> e.fields.getOrDefault("value", "").contains("example.org")),
                "a URL literal that no call uses is not a row");
        assertTrue(section(ledger, "config").stream().filter(e -> "CATALOGUE_HOST".equals(e.fields.get("env")) && !e.fields.containsKey("usesite"))
                .allMatch(e -> "true".equals(e.fields.get("superseded"))), "the call site carries the edge");
    }

    @Test
    void typescriptTemplateWithAnInlineEnvReadAndAModuleDotenv(@TempDir Path dir) throws Exception {
        // yas storefront: `${process.env.API_BASE_PATH}/product/storefront`, read by a call two hops away,
        // and the module's own .env.production (rule 50).
        write(dir, "storefront/package.json", "{ \"name\": \"storefront\" }\n");
        write(dir, "storefront/.env.production", "API_BASE_PATH=http://bff/api\n");
        write(dir, "storefront/services/ProductService.ts", """
                const serverSideRenderUrl = `${process.env.API_BASE_PATH}/product/storefront`;
                export async function getProductDetail(slug: string): Promise<ProductDetail> {
                  const response = await apiClientService.get(`${serverSideRenderUrl}/product/${slug}`);
                  return response.json();
                }
                """);
        write(dir, "bff/pom.xml", "<project><artifactId>bff</artifactId></project>\n");
        EdgeLedger ledger = extract(dir, "typescript");
        assertTrue(row(ledger, "http-client", Map.of("urlref", "serverSideRenderUrl", "env", "API_BASE_PATH")));
        new ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ConfigExtractor().extract(dir, ledger);
        new ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner().scan(dir, ledger);
        assertTrue(row(ledger, "env-address", Map.of("name", "API_BASE_PATH", "host", "bff", "workload", "dockerfile")));
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, ledger.toJson().toString(), "owner/repo");
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("storefront") && e.target.equals("bff")));
    }

    @Test
    void goEnvDefaultsGrpcTargetsAndRegistryNames(@TempDir Path dir) throws Exception {
        write(dir, "go.mod", "module example.com/hotel\n");
        write(dir, "services/frontend/server.go", """
                package frontend
                import ("os"; "google.golang.org/grpc")
                func (s *Server) Run() error {
                	addr := os.Getenv("CART_ADDR")
                	if addr == "" {
                		addr = "cart:7070"
                	}
                	conn, _ := grpc.Dial(addr, grpc.WithInsecure())
                	if err := s.initSearchClient("srv-search"); err != nil { return err }
                	if err := s.initReservation("srv-reservation"); err != nil { return err }
                	return nil
                }
                func (s *Server) initSearchClient(name string) error {
                	conn, err := dialer.Dial(
                		name,
                		dialer.WithTracer(s.Tracer),
                	)
                	return err
                }
                func (s *Server) initReservation(name string) error {
                	conn, err := s.getGprcConn(name)
                	return err
                }
                func (s *Server) getGprcConn(name string) (*grpc.ClientConn, error) {
                	return dialer.Dial(fmt.Sprintf("consul://%s/%s", s.ConsulAddr, name), dialer.WithTracer(s.Tracer))
                }
                """);
        EdgeLedger ledger = extract(dir, "go");
        assertTrue(row(ledger, "grpc", Map.of("urlref", "addr", "env", "CART_ADDR", "envdefault", "cart:7070")), "the if-empty default idiom");
        assertTrue(row(ledger, "registry-ref", Map.of("name", "srv-search")), "a literal reaching a dial through a parameter (rule 49)");
        assertTrue(row(ledger, "registry-ref", Map.of("name", "srv-reservation")), "…through two functions");
        DependencyGraph g = new DependencyGraph("");
        g.addNode("frontend", DependencyGraph.KIND_SERVICE);
        g.addNode("search", DependencyGraph.KIND_SERVICE);
        List<CodeGraphMerger.Unresolved> left = CodeGraphMerger.merge(g, ledger.toJson().toString(), "owner/repo");
        assertTrue(left.stream().anyMatch(u -> "srv-search".equals(u.rawTarget)), "listed for the operator");
        assertTrue(g.getEdges().stream().noneMatch(e -> e.target.contains("srv") || e.target.equals("search")), "never drawn by guessing");
    }

    @Test
    void phpEnvReadWithAUrlDefaultNamesAKnownService(@TempDir Path dir) throws Exception {
        // robot-shop ratings (rule 48): getenv('CATALOGUE_URL') ?: 'http://catalogue:8080' in the container wiring.
        write(dir, "ratings/composer.json", "{ \"name\": \"instana/ratings\" }\n");
        write(dir, "ratings/src/Kernel.php", """
                <?php
                class Kernel {
                    protected function configureContainer($c) {
                        $c->setParameter('catalogueUrl', getenv('CATALOGUE_URL') ?: 'http://catalogue:8080');
                        $c->setParameter('pdo_dsn', getenv('PDO_URL') ?: 'mysql:host=mysql;dbname=ratings');
                    }
                }
                """);
        write(dir, "ratings/src/Client.php", """
                <?php
                $url = getenv('USER_URL') ?: 'http://user:8080';
                $ch = curl_init($url);
                """);
        write(dir, "catalogue/package.json", "{ \"name\": \"catalogue\" }\n");
        write(dir, "user/package.json", "{ \"name\": \"user\" }\n");
        EdgeLedger ledger = extract(dir, "php");
        assertTrue(row(ledger, "config", Map.of("env", "CATALOGUE_URL", "envdefault", "http://catalogue:8080")));
        assertTrue(row(ledger, "http-client", Map.of("urlref", "url", "env", "USER_URL", "envdefault", "http://user:8080")));
        new ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.ServiceRootScanner().scan(dir, ledger);
        DependencyGraph g = new DependencyGraph("");
        CodeGraphMerger.merge(g, ledger.toJson().toString(), "owner/repo");
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("ratings") && e.target.equals("catalogue")));
        assertTrue(g.getEdges().stream().anyMatch(e -> e.source.equals("ratings") && e.target.equals("user")));
    }

    @Test
    void pythonUsesTheSameResolver(@TempDir Path dir) throws Exception {
        write(dir, "svc/requirements.txt", "requests\n");
        write(dir, "svc/app.py", """
                import os, requests
                PREFIX = os.getenv("CONFIG_API_PREFIX", "http://config-service/api")
                req_url = f"{PREFIX}/configs"
                def get():
                    return requests.get(url=req_url)
                """);
        DetectedStack python = new StackDetector().detect(dir).stream().filter(s -> s.language.equals("python")).findFirst().orElseThrow();
        EdgeLedger ledger = new EdgeLedger();
        new TreeSitterExtractor().extract(dir, python, ledger);
        assertTrue(row(ledger, "http-client", Map.of("urlref", "req_url", "env", "CONFIG_API_PREFIX", "envdefault", "http://config-service/api")),
                "two hops: an f-string over a getenv");
        assertTrue(section(ledger, "config").stream().filter(e -> "CONFIG_API_PREFIX".equals(e.fields.get("env")))
                .allMatch(e -> "true".equals(e.fields.get("superseded"))));
    }

    @Test
    void scriptsAnHtmlPageLoadsAreBrowserCodeAndToolchainTestFilesAreTests(@TempDir Path dir) throws Exception {
        write(dir, "frontend/package.json", "{ \"name\": \"frontend\" }\n");
        write(dir, "frontend/static/sheet.html", "<html><script type=\"text/javascript\" src=\"sheet.js\"></script></html>\n");
        write(dir, "frontend/static/sheet.js", "gapi.client.load('https://sheets.googleapis.com/$discovery/rest?version=v4');\n");
        write(dir, "frontend/server.js", "fetch('https://api.payments.io/v1/charge');\n");
        write(dir, "frontend/tests-examples/demo.spec.js", "fetch('https://demo.playwright.dev/todomvc');\n");
        EdgeLedger ledger = extract(dir, "javascript");
        assertTrue(section(ledger, "http-client").stream().filter(e -> e.file.endsWith("sheet.js"))
                .allMatch(e -> "browser-script".equals(e.fields.get("not-an-address"))), "rule 51");
        assertTrue(section(ledger, "http-client").stream().anyMatch(e -> e.file.endsWith("server.js") && !e.fields.containsKey("unreferenced")));
        assertTrue(ledger.getEdges().stream().noneMatch(e -> e.file.contains(".spec.")), "rule 42: *.spec.js is a test file");
    }

    @Test
    void kotlinAndCsharpUseTheSharedVocabulary(@TempDir Path dir) throws Exception {
        write(dir, "orders/build.gradle.kts", "plugins { kotlin(\"jvm\") }\n");
        write(dir, "orders/src/main/kotlin/App.kt", """
                val kafkaAddr = System.getenv("KAFKA_ADDR") ?: "kafka:9092"
                class Client(private val restTemplate: RestTemplate) {
                    fun f() = restTemplate.getForObject("http://inventory:8080/stock", String::class.java)
                }
                """);
        EdgeLedger kt = extract(dir, "kotlin");
        assertTrue(row(kt, "http-client", Map.of("method", "getForObject", "url", "http://inventory:8080/stock")));
        assertTrue(row(kt, "config", Map.of("env", "KAFKA_ADDR", "envdefault", "kafka:9092")));

        write(dir, "Basket.API/Basket.API.csproj", "<Project/>\n");
        write(dir, "Basket.API/Program.cs", """
                var redis = Environment.GetEnvironmentVariable("REDIS_ADDR") ?? "redis:6379";
                var discountUrl = builder.Configuration["GrpcSettings:DiscountUrl"];
                builder.Services.AddHttpClient<ICatalog>(c => c.BaseAddress = new Uri(discountUrl));
                var client = new HttpClient();
                await client.GetAsync("http://catalog.api:8080/items");
                """);
        EdgeLedger cs = extract(dir, "csharp");
        assertTrue(row(cs, "http-client", Map.of("method", "BaseAddress", "env", "GrpcSettings__DiscountUrl", "dotnetkey", "GrpcSettings:DiscountUrl")),
                "a variable bound to a configuration key carries the key's env spelling");
        assertTrue(row(cs, "http-client", Map.of("url", "http://catalog.api:8080/items")));
        assertTrue(row(cs, "config", Map.of("env", "REDIS_ADDR", "envdefault", "redis:6379")));
    }
}
