package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/**
 * Convention ③ for environment variables: which declared value a read in a given module
 * gets. The same name may be declared with a different value per workload — four
 * manifests each saying {@code MONGO_CLIENT_HOST} — so a read in module X is resolved
 * against X's own scope first: its workload's env block, a ConfigMap its workload
 * receives, its Dockerfile. Only when no scoped value exists does the repo-wide table
 * answer, and then only when every declaration agrees (or exactly one names a known
 * service: a monolith variant's collapsed addresses lose to the microservice wiring).
 * Several different scoped values are ambiguous and resolve to nothing rather than to
 * a guess (rule 20, 2026-10-23).
 *
 * <p>Who a scope belongs to, and which host is a known node, are the merger's name
 * table (convention ①); this class asks through the two functions it is given.
 */
public final class EnvScope {

    /**
     * One declared value of an env var, with the scope it holds in: {@code workload:<name>}
     * (a workload's own env block, or a Compose service's environment), {@code
     * configmap:<name>} (a ConfigMap's data), {@code module:<file>} (a Dockerfile's ENV),
     * or null (a global .env). {@code kind} is what the value's shape said the target is
     * ({@code db} / {@code queue}, as a graph kind) or null; {@code listed} whether the
     * value was one entry of a list-valued declaration (rule 55).
     */
    public record Value(String host, String scope, String kind, boolean listed) {
        public Value(String host, String scope, String kind) {
            this(host, scope, kind, false);
        }
    }

    /** env var name -> every declared value with its scope. */
    private final Map<String, List<Value>> values = new LinkedHashMap<>();
    /** (scope, source) -> whether the scope is the module {@code source}. */
    private final BiPredicate<String, String> scopeIs;
    /** host -> the node it names, or null. */
    private final UnaryOperator<String> matchNode;

    public EnvScope(BiPredicate<String, String> scopeIs, UnaryOperator<String> matchNode) {
        this.scopeIs = scopeIs;
        this.matchNode = matchNode;
    }

    /** Records one declared value of {@code name}. */
    public void declare(String name, String host, String scope, String kind, boolean listed) {
        values.computeIfAbsent(name, k -> new ArrayList<>()).add(new Value(host, scope, kind, listed));
    }

    /** The host {@code name} resolves to for a read in {@code source}, or null. */
    public String resolve(String name, String source) {
        Value v = resolveValue(name, source);
        return v == null ? null : v.host();
    }

    /**
     * Rule 55: every host of a list-valued variable when the reader's own scope declares the
     * variable as one list (travels' PORTAL_SERVICES); otherwise the single value of
     * {@link #resolveValue}, or nothing.
     */
    public List<Value> resolveAll(String name, String source) {
        List<Value> declared = values.get(name);
        if (declared != null && source != null) {
            List<Value> scoped = new ArrayList<>();
            for (Value v : declared) if (v.scope() != null && scopeIs.test(v.scope(), source)) scoped.add(v);
            if (scoped.size() > 1 && scoped.stream().allMatch(Value::listed)
                    && scoped.stream().map(Value::scope).distinct().count() == 1) {
                RuleTrace.hit(55, "read-list " + name + " @" + source + " " + scoped.size());
                return scoped;
            }
        }
        Value one = resolveValue(name, source);
        return one == null ? List.of() : List.of(one);
    }

    /** As {@link #resolve}, with the kind the declaring value's shape stated (or null). */
    public Value resolveValue(String name, String source) {
        List<Value> declared = values.get(name);
        if (declared == null) {
            for (Map.Entry<String, List<Value>> e : values.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    declared = e.getValue();
                    break;
                }
            }
        }
        if (declared == null || declared.isEmpty()) return null;

        Set<String> scoped = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (Value v : declared) {
            all.add(v.host());
            if (source != null && v.scope() != null && scopeIs.test(v.scope(), source)) scoped.add(v.host());
        }
        String pick = uniqueHost(scoped);
        if (RuleTrace.hitIf(pick == null && !scoped.isEmpty(), 20, "ambiguous " + name + " @" + source)) return null;
        RuleTrace.hitIf(pick != null, 20, "scoped " + name + "=" + pick + " @" + source);
        if (pick == null) pick = uniqueHost(all);
        RuleTrace.hitIf(scoped.isEmpty() && all.size() > 1, 20, (pick == null ? "global-ambiguous " : "global ") + name + " @" + source);
        if (pick == null) return null;
        for (Value v : declared) {
            if (v.host().equals(pick) && v.kind() != null) return v;
        }
        return new Value(pick, null, null);
    }

    /** One host, or the one host among several that is a known node; null when ambiguous. */
    private String uniqueHost(Set<String> hosts) {
        if (hosts.isEmpty()) return null;
        if (hosts.size() == 1) return hosts.iterator().next();
        String known = null;
        for (String h : hosts) {
            if (matchNode.apply(h) != null) {
                if (known != null) return null;
                known = h;
            }
        }
        return known;
    }
}
