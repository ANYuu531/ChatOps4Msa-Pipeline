package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.KeyReads;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.NameTable;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions.Vocabulary;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Language-agnostic extraction of service URLs and infrastructure endpoints from
 * configuration files (Spring application.yml/properties, .env, docker-compose).
 *
 * Config is a key/value problem, not a parsing problem, so tree-sitter buys
 * nothing here — this stays a plain reader.
 */
@Component
public class ConfigExtractor {

    public void extract(Path root, EdgeLedger ledger) {
        DescriptorRows rows = new DescriptorRows();
        KubernetesReader k8s = new KubernetesReader(rows);
        ComposeReader compose = new ComposeReader(rows);
        DockerReader docker = new DockerReader(rows);
        AppConfigReader appConfig = new AppConfigReader(rows);
        k8s.prepare(root, ledger);
        List<Path> files = SourceScanner.filesWithExtensions(root,
                List.of(".yml", ".yaml", ".properties", ".env", ".conf", ".conf.template"));
        // Which Spring profiles the deployment runs: a profile's file is read only then (rule 57).
        ActiveProfiles profiles = ActiveProfiles.scan(root, files);
        // A Compose project's .env, interpolated the way Compose does, per project directory.
        Map<Path, Map<String, String>> projectEnvs = new java.util.HashMap<>();

        // .NET's configuration file. Its keys are the same ones the Compose environment
        // overrides with `__` (ConnectionStrings__Database), and a YARP gateway's routing
        // table lives nowhere else (2026-10-26, run-aspnetcore-microservices).
        for (Path json : SourceScanner.filesWithExtensions(root, List.of(".json"))) {
            String jsonName = json.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean ocelot = jsonName.startsWith("ocelot");
            if (!jsonName.startsWith("appsettings") && !ocelot) {
                // Convention ②: an address is an address whatever file it is in. A JSON file of
                // no known kind (a Go service's config.development.json, a gateway's routes.json)
                // gives candidate rows, kept by the merger only toward a declared name and only
                // for the component that owns the file (its module, or the one container that
                // mounts it), as a mounted YAML's are (rule 60).
                if (!AppConfigReader.TOOLING_JSON.matcher(jsonName).find() && !AppConfigReader.TEST_CONFIG_NAME.matcher(jsonName).find()) {
                    try {
                        if (Files.size(json) <= 512_000) {
                            String text = Files.readString(json, StandardCharsets.UTF_8);
                            if (text.startsWith("\uFEFF")) text = text.substring(1);
                            AppConfigReader.mountedConfigAddresses("", new Yaml().load(text), SourceScanner.relative(root, json), ledger);
                        }
                    } catch (Exception ignored) {
                        // not JSON a YAML loader reads (comments, a JSON Lines file): no candidates
                    }
                }
                continue;
            }
            try {
                if (ocelot) appConfig.extractOcelotRoutes(json, SourceScanner.relative(root, json), ledger);
                else appConfig.extractDotnetAppSettings(json, SourceScanner.relative(root, json), ledger);
            } catch (Exception e) {
                // malformed JSON (comments, trailing commas): not worth failing the analysis over, but said
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, json) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }

        // A Dockerfile's ENV lines are the image's own environment: Robot Shop's shipping
        // image says CART_ENDPOINT=cart:8080 nowhere else (2026-10-23). They are scoped to
        // the module that owns the Dockerfile, like a workload's own env block.
        for (Path dockerfile : DockerReader.dockerfiles(root)) {
            try {
                docker.extractDockerfileEnv(dockerfile, SourceScanner.relative(root, dockerfile), ledger);
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, dockerfile) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }
        // The images those Dockerfiles are built into, when a build script names them.
        docker.extractImageBuilds(root, ledger);

        // Rule 50: a dotenv file inside a module (.env, .env.production) is that module's own
        // environment, the way a Dockerfile's ENV is — what dotenv, Next.js and Vite load at
        // start. A .env beside a Compose file is the Compose project's (read above, per
        // project) and is not a module scope. Templates (.env.sample / .example) are not an
        // environment; loopback values are a developer's machine and say nothing.
        for (Path dotenv : DockerReader.dotenvFiles(root)) {
            try {
                Path dir = dotenv.getParent();
                if (dir != null && DockerReader.hasComposeFile(dir)) continue;
                String relative = SourceScanner.relative(root, dotenv);
                for (Map.Entry<String, String> e : ComposeReader.readEnvFile(dotenv).entrySet()) {
                    String host = DescriptorRows.addressHost(e.getKey(), e.getValue());
                    if (host == null || AddressShape.isLoopback(host)) continue;
                    RuleTrace.hit(50, "dotenv " + relative + " " + e.getKey() + "=" + host);
                    rows.recordEnvAddress(e.getKey(), e.getValue(), null, "dockerfile", relative, ledger);
                }
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, dotenv) + ": " + e.getMessage());
            }
        }

        // Rule 52: Helm charts are rendered first, and their templates/ are not read as plain YAML.
        Set<Path> chartTemplates = HelmChartRenderer.templateDirs(root);
        for (HelmChartRenderer.Rendered r : HelmChartRenderer.render(root, ledger::addWarning)) {
            try {
                k8s.extractK8sYaml(r.yaml(), r.template(), ledger, r.chart());
            } catch (Exception e) {
                ledger.addWarning("unreadable descriptor " + r.template() + " (rendered from chart " + r.chart() + "): "
                        + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }

        for (Path file : files) {
            if (KubernetesReader.underAny(file, chartTemplates)) continue;
            if (file.getFileName().toString().endsWith(".conf")
                    || file.getFileName().toString().endsWith(".conf.template")) {
                // A reverse proxy's routing table is the edge list of the front door:
                // Apache's ProxyPass and nginx's proxy_pass name the upstreams.
                try {
                    appConfig.extractReverseProxy(file, SourceScanner.relative(root, file), ledger);
                } catch (Exception e) {
                    ledger.addWarning("unreadable descriptor " + SourceScanner.relative(root, file) + ": " + HelmChartRenderer.firstLine(e.getMessage()));
                }
                continue;
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean isSpringConfig = name.startsWith("application") || name.startsWith("bootstrap");
            boolean isEnv = name.equals(".env") || name.startsWith(".env.");
            // The Compose spec's default file names are compose.y*ml; docker-compose.y*ml
            // is the legacy spelling. Both are the same document.
            boolean isCompose = name.startsWith("docker-compose") || name.startsWith("compose.");
            boolean isYaml = name.endsWith(".yml") || name.endsWith(".yaml");

            String relative = SourceScanner.relative(root, file);
            // A config file whose name says it is a template or a sample (mate-infra-prod-template.yml,
            // application-example.properties) is a starting point for someone else's deployment,
            // as .env.sample is (rule 50), not this repo's configuration (rule 57, 2026-11-03).
            if (!isEnv && !isCompose && (isYaml || name.endsWith(".properties"))
                    && RuleTrace.hitIf(AppConfigReader.TEMPLATE_CONFIG.matcher(name).find(), 57, "template " + relative)) continue;
            // The build tool's own settings (gradle/wrapper/gradle-wrapper.properties,
            // .mvn/wrapper/maven-wrapper.properties: where to download the build tool) are
            // not the service's runtime configuration.
            if (name.endsWith("-wrapper.properties")) continue;
            // A profile's file (application-dev.yaml) is loaded only when the deployment runs
            // that profile; yas's dev file is a developer's local route template (2026-11-06).
            if (isSpringConfig && RuleTrace.hitIf(profiles.inactive(ActiveProfiles.profileOfFile(name), file), 57,
                    "inactive-profile " + relative + " " + profiles.activeFor(file))) continue;
            try {
                if (name.endsWith(".properties") || isEnv) {
                    appConfig.readKeyValueFile(file, relative, ledger);
                } else if (isCompose) {
                    // A Compose overlay whose name says it runs tests (compose.tests.yaml,
                    // docker-compose-e2e.yml) declares test runners, not workloads: the same
                    // exclusion the service-root scan applies to a tests/ directory (2026-10-26).
                    if (RuleTrace.hitIf(ComposeReader.isTestCompose(name), 31, "skip-compose " + relative)) continue;
                    Object loaded = new Yaml().load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
                    // docker-compose declares each service's startup dependencies
                    // (config-server, discovery-server, …) as depends_on — the one
                    // structured place a "service -> control-plane" edge is stated when
                    // the client-side URLs are externalised to a config repo — and each
                    // service's environment, which is the same wiring a k8s manifest
                    // carries (DB_HOST: db). It is not read as a service's own config:
                    // flattened, TeaStore's compose file made its examples/ directory the
                    // source of every database edge (2026-09-22).
                    Map<String, String> projectEnv = projectEnvs.computeIfAbsent(file.getParent(), ComposeReader::projectEnv);
                    compose.extractComposeServices(loaded, root, file.getParent(), projectEnv, relative, ledger);
                    compose.extractComposeDependsOn(loaded, relative, ledger);
                    compose.extractComposeEnvironment(loaded, root, file.getParent(), projectEnv, relative, ledger);
                } else if (isSpringConfig) {
                    for (Object doc : AppConfigReader.springDocuments(file, relative, profiles)) appConfig.flatten("", doc, relative, ledger);
                } else if (isYaml) {
                    // Any other YAML is either a k8s manifest (the workload inventory and
                    // the env -> host table that resolve targets with no cluster — the
                    // greenfield case) or a Spring config document that is not called
                    // application.yml: a Spring Cloud Config repository keeps one file per
                    // client service (shared/account-service.yml). Piggymetrics's datasources
                    // all live in such files, and the analysis saw none of them (2026-09-22).
                    if (!k8s.extractK8sEnvAddresses(file, relative, ledger)) {
                        List<Object> docs = AppConfigReader.springDocuments(file, relative, profiles);
                        Object loaded = docs.isEmpty() ? null : docs.get(0);
                        // An Envoy static config is a reverse proxy's routing table in YAML:
                        // its clusters are the upstreams, as nginx's proxy_pass are.
                        if (docs.size() <= 1 && (appConfig.extractEnvoyClusters(loaded, relative, ledger) || k8s.extractApisixRoutes(loaded, relative, ledger))) {
                            continue;
                        }
                        for (Object doc : docs) {
                            if (AppConfigReader.looksLikeSpringConfig(doc)) {
                                RuleTrace.hit(3, "spring-yml " + relative);
                                appConfig.flatten("", doc, relative, ledger);
                            } else {
                                AppConfigReader.mountedConfigAddresses("", doc, relative, ledger);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Unreadable or malformed config does not fail the analysis — but it is said:
                // a deployment descriptor that silently vanishes is a missing layer the
                // reader cannot see (skywalking-showcase's whole Helm chart, 2026-10-27).
                ledger.addWarning("unreadable descriptor " + relative + ": " + HelmChartRenderer.firstLine(e.getMessage()));
            }
        }
        KeyReads.record(root, ledger);
    }

}
