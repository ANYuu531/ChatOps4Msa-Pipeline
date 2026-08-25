package ntou.soselab.chatops4msa;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Offline checks on the dependency-analysis capability config.
 *
 * The real verification runs at startup ({@code CapabilityConfigLoader}), which needs
 * the whole Spring context — Discord token included — so it cannot catch a mistake on
 * a developer machine. These two checks cover the failures that have actually
 * happened when editing this file: calling a toolkit that was never registered in
 * {@code toolkit_verify.yml}, and referencing a ${variable} that nothing in the same
 * operation assigns (which silently renders as the literal text).
 */
public class DependencyCapabilityConfigTest {

    /** Variables that come from the caller or the environment, not from an assign. */
    private static final Set<String> EXTERNAL_VARIABLES = Set.of(
            "repo_name", "namespace", "entry_url", "auth_hint",   // capability parameters
            "istio_prometheus_host_url", "deepwiki_mcp_url",      // property variables
            "github_token", "github_username");

    @Test
    void everyToolkitUsedByTheDependencyPipelineIsRegistered() {
        Map<String, Object> verify = load("toolkit_verify.yml");
        Map<String, Object> config = load("capability/devops-tool/dependency.yml");

        // Guard against a silently empty parse making both checks vacuous.
        assertTrue(steps(config).size() > 50,
                "expected to parse the whole pipeline, got " + steps(config).size() + " steps");
        assertTrue(verify.containsKey("toolkit-depstate-ask-button"));

        List<String> unregistered = new ArrayList<>();
        for (Map<String, Object> step : steps(config)) {
            for (String name : step.keySet()) {
                if (name.startsWith("toolkit-") && !verify.containsKey(name)) {
                    unregistered.add(name);
                }
            }
        }
        assertTrue(unregistered.isEmpty(),
                "these toolkits are called by dependency.yml but are not declared in "
                        + "toolkit_verify.yml, so the app fails to start: " + unregistered);
    }

    @Test
    void everyReferencedVariableIsAssignedEarlierInTheSameOperation() {
        Map<String, Object> config = load("capability/devops-tool/dependency.yml");

        for (Map<String, Object> operation : operations(config)) {
            String opName = String.valueOf(operation.get("name"));
            Set<String> available = new LinkedHashSet<>(EXTERNAL_VARIABLES);

            for (Map<String, Object> step : stepsOf(operation)) {
                for (Map.Entry<String, Object> call : step.entrySet()) {
                    Object arguments = call.getValue();
                    if (!(arguments instanceof Map)) continue;

                    // A reference must already be available at this point: the pipeline
                    // is executed top-down, so a later assign does not help.
                    for (Map.Entry<?, ?> argument : ((Map<?, ?>) arguments).entrySet()) {
                        if ("assign".equals(String.valueOf(argument.getKey()))) continue;
                        for (String variable : referenced(String.valueOf(argument.getValue()))) {
                            if (!available.contains(variable)) {
                                fail("operation `" + opName + "`, step `" + call.getKey()
                                        + "` references ${" + variable + "}, which nothing assigns "
                                        + "before it — it would render as literal text");
                            }
                        }
                    }

                    Object assign = ((Map<?, ?>) arguments).get("assign");
                    if (assign != null) available.add(String.valueOf(assign));
                }
            }
        }
    }

    // ---------- helpers ----------

    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private static List<String> referenced(String text) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String resource) {
        try (InputStream in = DependencyCapabilityConfigTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("resource not found: " + resource);
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> config) {
        Object lowCode = config.get("low_code");
        if (!(lowCode instanceof Map)) return List.of();
        Object operations = ((Map<String, Object>) lowCode).get("operation");
        return operations instanceof List ? (List<Map<String, Object>>) operations : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Map<String, Object> operation) {
        Object body = operation.get("body");
        return body instanceof List ? (List<Map<String, Object>>) body : List.of();
    }

    private static List<Map<String, Object>> steps(Map<String, Object> config) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> operation : operations(config)) all.addAll(stepsOf(operation));
        return all;
    }
}
