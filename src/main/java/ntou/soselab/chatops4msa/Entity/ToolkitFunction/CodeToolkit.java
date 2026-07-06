package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic (non-LLM) source-code extraction of microservice dependencies.
 *
 * Clones a Java / Spring Boot repository and walks its AST with JavaParser to
 * extract structured dependency signals (Feign clients, REST/WebClient URLs,
 * Kafka/RabbitMQ producers & consumers) plus service-URL / infra keys from
 * application.yml / application.properties.
 *
 * The output is a plain-text "Code-Extracted Edge Ledger" with file:line
 * evidence. A downstream LLM step only formats/merges it; the discovery itself
 * is deterministic so nothing is silently missed or hallucinated.
 *
 * Fails soft: any clone/parse error returns an explanatory ledger rather than
 * throwing, so the surrounding dependency-analysis pipeline still completes.
 */
@Component
public class CodeToolkit extends ToolkitFunction {

    private static final long CLONE_TIMEOUT_SECONDS = 120;
    private static final int MAX_GENERIC_URLS = 60;

    /**
     * @param repo GitHub repository as "owner/repo" or a full clone URL.
     * @return a deterministic Code-Extracted Edge Ledger (Markdown-ish text).
     */
    public String toolkitCodeExtractJava(String repo) {
        if (repo == null || repo.isBlank()) {
            return "# Code-Extracted Edge Ledger\n\nCollection status: FAILED (no repository provided).";
        }

        String cloneUrl = toCloneUrl(repo.trim());
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("code-extract-");
            CloneResult clone = gitCloneShallow(cloneUrl, tempDir);
            if (!clone.success) {
                return "# Code-Extracted Edge Ledger\n\n" +
                        "Collection status: FAILED\n" +
                        "Repository: " + repo + "\n" +
                        "Clone command: " + clone.detail + "\n" +
                        "Note: source code could not be retrieved; fall back to documentation-based analysis.";
            }

            Extraction ex = new Extraction();
            extractFromSources(tempDir, ex);
            extractFromConfig(tempDir, ex);
            return ex.render(repo);

        } catch (Exception e) {
            return "# Code-Extracted Edge Ledger\n\n" +
                    "Collection status: FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")\n" +
                    "Repository: " + repo;
        } finally {
            deleteQuietly(tempDir);
        }
    }

    // ---------- git ----------

    private String toCloneUrl(String repo) {
        if (repo.startsWith("http://") || repo.startsWith("https://") || repo.startsWith("git@")) return repo;
        return "https://github.com/" + repo + ".git";
    }

    private static class CloneResult {
        boolean success;
        String detail;
    }

    private CloneResult gitCloneShallow(String url, Path dest) {
        CloneResult result = new CloneResult();
        result.detail = "git clone --depth 1 " + url;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", url, dest.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }

            boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.detail += " (timed out after " + CLONE_TIMEOUT_SECONDS + "s)";
                return result;
            }
            result.success = process.exitValue() == 0;
            if (!result.success) result.detail += " (exit " + process.exitValue() + ": " + out.toString().trim() + ")";
            return result;

        } catch (Exception e) {
            result.detail += " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            return result;
        }
    }

    // ---------- extraction accumulator ----------

    private static class Extraction {
        final List<String> feign = new ArrayList<>();
        final List<String> restWeb = new ArrayList<>();
        final Set<String> genericUrls = new LinkedHashSet<>();
        final List<String> kafkaConsumers = new ArrayList<>();
        final List<String> kafkaProducers = new ArrayList<>();
        final List<String> rabbitConsumers = new ArrayList<>();
        final List<String> rabbitProducers = new ArrayList<>();
        final List<String> infra = new ArrayList<>();
        int javaFiles = 0;
        int parseErrors = 0;

        String render(String repo) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Code-Extracted Edge Ledger\n\n");
            sb.append("Collection status: OK\n");
            sb.append("Repository: ").append(repo).append('\n');
            sb.append("Java files parsed: ").append(javaFiles)
                    .append(" (parse errors: ").append(parseErrors).append(")\n");
            sb.append("Extraction method: JavaParser AST + config parsing (deterministic, non-LLM)\n\n");

            section(sb, "1. Synchronous - Feign clients", feign);
            section(sb, "2. Synchronous - RestTemplate / WebClient / RestClient", restWeb);
            section(sb, "2b. Synchronous - other absolute URL literals in code",
                    new ArrayList<>(genericUrls));
            section(sb, "3. Asynchronous - Kafka producers (service -> topic)", kafkaProducers);
            section(sb, "4. Asynchronous - Kafka consumers (topic -> service)", kafkaConsumers);
            section(sb, "5. Asynchronous - RabbitMQ producers (service -> exchange/routingKey)", rabbitProducers);
            section(sb, "6. Asynchronous - RabbitMQ consumers (queue -> service)", rabbitConsumers);
            section(sb, "7. Infrastructure / service-URL config keys", infra);

            sb.append("\n## Notes\n");
            sb.append("- Every edge above is a literal found in source or config; ")
                    .append("absence means it was not found in code, not that it cannot happen at runtime.\n");
            sb.append("- Property-indirected targets (@Value / ${...}) are marked; resolve them against ")
                    .append("application config for the concrete host.\n");
            return sb.toString();
        }

        private void section(StringBuilder sb, String title, List<String> items) {
            sb.append("## ").append(title).append('\n');
            if (items.isEmpty()) {
                sb.append("- none found in code\n\n");
                return;
            }
            for (String it : items) sb.append("- ").append(it).append('\n');
            sb.append('\n');
        }
    }

    // ---------- Java source extraction ----------

    private void extractFromSources(Path root, Extraction ex) {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        List<Path> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/") && !p.toString().contains("\\test\\"))
                    .forEach(javaFiles::add);
        } catch (Exception e) {
            return;
        }

        for (Path file : javaFiles) {
            ex.javaFiles++;
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(file);
            } catch (Exception e) {
                ex.parseErrors++;
                continue;
            }
            String rel = root.relativize(file).toString();
            boolean kafkaFile = cu.toString().contains("KafkaTemplate") || cu.toString().contains("org.springframework.kafka");
            boolean rabbitFile = cu.toString().contains("RabbitTemplate") || cu.toString().contains("AmqpTemplate");

            extractFeign(cu, rel, ex);
            extractListeners(cu, rel, ex);
            extractMethodCalls(cu, rel, ex, kafkaFile, rabbitFile);
            extractStringLiterals(cu, rel, ex);
        }
    }

    private void extractFeign(CompilationUnit cu, String rel, Extraction ex) {
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<AnnotationExpr> feign = type.getAnnotationByName("FeignClient");
            if (feign.isEmpty()) continue;
            String name = firstString(annMember(feign.get(), "value")
                    .or(() -> annMember(feign.get(), "name"))
                    .or(() -> singleMember(feign.get()))).orElse("unknown");
            String url = firstString(annMember(feign.get(), "url")).orElse("");
            String target = url.isEmpty() ? "service=" + name : "url=" + url + " (name=" + name + ")";

            List<String> methods = new ArrayList<>();
            for (MethodDeclaration m : type.getMethods()) {
                for (AnnotationExpr a : m.getAnnotations()) {
                    String http = httpMethodOf(a.getNameAsString());
                    if (http == null) continue;
                    String path = firstString(annMember(a, "value")
                            .or(() -> annMember(a, "path"))
                            .or(() -> singleMember(a))).orElse("");
                    methods.add(http + " " + path);
                }
            }
            String methodStr = methods.isEmpty() ? "" : " {" + String.join(", ", methods) + "}";
            ex.feign.add("@FeignClient -> " + target + methodStr
                    + " [Evidence: " + rel + ":" + line(type) + ", High]");
        }
    }

    private void extractListeners(CompilationUnit cu, String rel, Extraction ex) {
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            Optional<AnnotationExpr> kl = m.getAnnotationByName("KafkaListener");
            if (kl.isPresent()) {
                for (String topic : allStrings(annMember(kl.get(), "topics").orElse(null))) {
                    ex.kafkaConsumers.add("topic '" + topic + "' -> " + m.getNameAsString() + "()"
                            + " [Evidence: " + rel + ":" + line(m) + ", High]");
                }
            }
            Optional<AnnotationExpr> rl = m.getAnnotationByName("RabbitListener");
            if (rl.isPresent()) {
                for (String queue : allStrings(annMember(rl.get(), "queues").orElse(null))) {
                    ex.rabbitConsumers.add("queue '" + queue + "' -> " + m.getNameAsString() + "()"
                            + " [Evidence: " + rel + ":" + line(m) + ", High]");
                }
            }
        }
    }

    private void extractMethodCalls(CompilationUnit cu, String rel, Extraction ex,
                                    boolean kafkaFile, boolean rabbitFile) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String mn = call.getNameAsString();

            if (kafkaFile && mn.equals("send") && !call.getArguments().isEmpty()) {
                firstStringArg(call, 0).ifPresent(topic ->
                        ex.kafkaProducers.add("this service -> topic '" + topic + "'"
                                + " [Evidence: " + rel + ":" + line(call) + ", High]"));
            }

            if (rabbitFile && (mn.equals("convertAndSend") || mn.equals("send"))
                    && call.getArguments().size() >= 2) {
                String exchange = firstStringArg(call, 0).orElse("?");
                String routingKey = firstStringArg(call, 1).orElse("?");
                ex.rabbitProducers.add("this service -> exchange '" + exchange + "', routingKey '" + routingKey + "'"
                        + " [Evidence: " + rel + ":" + line(call) + ", High]");
            }

            if (mn.equals("baseUrl") && !call.getArguments().isEmpty()) {
                firstStringArg(call, 0).ifPresent(u ->
                        ex.restWeb.add("WebClient.baseUrl(\"" + u + "\")"
                                + " [Evidence: " + rel + ":" + line(call) + ", "
                                + (u.contains("${") ? "Medium (property indirection)" : "High") + "]"));
            }
        }
    }

    private void extractStringLiterals(CompilationUnit cu, String rel, Extraction ex) {
        for (StringLiteralExpr s : cu.findAll(StringLiteralExpr.class)) {
            String v = s.getValue();
            if (v.startsWith("http://") || v.startsWith("https://")) {
                if (ex.genericUrls.size() < MAX_GENERIC_URLS) {
                    ex.genericUrls.add(v + " [Evidence: " + rel + ":" + line(s) + "]");
                }
            }
        }
    }

    // ---------- config extraction ----------

    private void extractFromConfig(Path root, Extraction ex) {
        List<Path> configs = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("application") &&
                                (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
                    })
                    .forEach(configs::add);
        } catch (Exception e) {
            return;
        }

        for (Path cfg : configs) {
            String rel = root.relativize(cfg).toString();
            String name = cfg.getFileName().toString();
            try {
                if (name.endsWith(".properties")) {
                    for (String raw : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                        String l = raw.trim();
                        if (l.isEmpty() || l.startsWith("#")) continue;
                        int eq = l.indexOf('=');
                        if (eq < 0) continue;
                        recordConfigKey(l.substring(0, eq).trim(), l.substring(eq + 1).trim(), rel, ex);
                    }
                } else {
                    Object loaded = new Yaml().load(Files.newInputStream(cfg));
                    flattenYaml("", loaded, rel, ex);
                }
            } catch (Exception ignored) {
                // skip unreadable config
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenYaml(String prefix, Object node, String rel, Extraction ex) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flattenYaml(key, e.getValue(), rel, ex);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flattenYaml(prefix + "[" + i + "]", list.get(i), rel, ex);
        } else {
            recordConfigKey(prefix, String.valueOf(node), rel, ex);
        }
    }

    private void recordConfigKey(String key, String value, String rel, Extraction ex) {
        String lk = key.toLowerCase();
        boolean relevant = lk.startsWith("spring.datasource")
                || lk.startsWith("spring.kafka")
                || lk.startsWith("spring.rabbitmq")
                || lk.startsWith("spring.data.mongodb")
                || lk.startsWith("spring.redis")
                || lk.startsWith("eureka.")
                || lk.startsWith("spring.cloud.consul")
                || lk.endsWith(".url") || lk.endsWith(".uri") || lk.endsWith(".host")
                || lk.contains("service") && (lk.endsWith("url") || lk.endsWith("uri") || lk.endsWith("host"));
        if (relevant) {
            ex.infra.add(key + " = " + value + " [Evidence: " + rel + "]");
        }
    }

    // ---------- JavaParser helpers ----------

    private String httpMethodOf(String annName) {
        return switch (annName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            case "RequestMapping" -> "REQUEST";
            default -> null;
        };
    }

    private Optional<Expression> annMember(AnnotationExpr ann, String member) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(member)) return Optional.of(pair.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<Expression> singleMember(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) return Optional.of(single.getMemberValue());
        return Optional.empty();
    }

    private Optional<String> firstString(Optional<Expression> expr) {
        if (expr.isEmpty()) return Optional.empty();
        List<String> all = allStrings(expr.get());
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private List<String> allStrings(Expression expr) {
        List<String> out = new ArrayList<>();
        if (expr == null) return out;
        if (expr instanceof StringLiteralExpr s) {
            out.add(s.getValue());
        } else if (expr instanceof ArrayInitializerExpr arr) {
            for (Expression e : arr.getValues()) {
                if (e instanceof StringLiteralExpr s) out.add(s.getValue());
            }
        }
        return out;
    }

    private Optional<String> firstStringArg(MethodCallExpr call, int idx) {
        if (call.getArguments().size() <= idx) return Optional.empty();
        Expression arg = call.getArgument(idx);
        return arg instanceof StringLiteralExpr s ? Optional.of(s.getValue()) : Optional.empty();
    }

    private int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(-1);
    }

    // ---------- cleanup ----------

    private void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
        }
    }
}
