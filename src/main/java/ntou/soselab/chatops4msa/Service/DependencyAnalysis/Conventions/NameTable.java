package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Conventions;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.AddressShape;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.RuleTrace;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convention ① — the name table — the part that needs no deployment to answer: how a
 * name is spelled, which spellings are one name, what a DNS suffix means, which
 * directory names a module. The merger holds the deployment's declared names and asks
 * these functions to compare against them.
 */
public final class NameTable {

    private NameTable() {
    }

    /** A bare fully-qualified host ({@code oss-cn-shenzhen.aliyuncs.com}, optionally {@code :port}): no scheme, no path. */
    public static final Pattern BARE_FQDN = Pattern.compile(
            "(?i)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{2,5})?");

    /** The in-cluster DNS suffixes a Service name may carry; the label before them is the namespace. */
    public static final String[] CLUSTER_SUFFIXES = Vocabulary.CLUSTER_SUFFIXES.toArray(new String[0]);

    /** Suffixes a Compose/Helm service name adds over the workload it runs (order-service-container). */
    public static final String[] DEPLOY_NAME_SUFFIXES = Vocabulary.DEPLOY_NAME_SUFFIXES.toArray(new String[0]);

    private static final String DNS_LABEL = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";

    private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.+)-v\\d+$");

    /**
     * A name safe to introduce as a node id: DNS labels, optionally dotted (a Compose
     * service may be called {@code catalog.api}), not a bare number.
     */
    public static boolean isPlausibleName(String s) {
        if (s == null || s.length() < 2 || s.length() > 63) return false;
        if (!s.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) return false;
        return !s.matches("[0-9.]+");
    }

    /**
     * A name with every run of non-alphanumerics as one hyphen: the module-directory
     * spelling (rule 2: {@code Basket.API} → {@code basket-api}), so a Compose service
     * {@code basket.api} and the directory it is built from compare equal — the same
     * letters at the same word boundaries, not a similarity guess (2026-10-26).
     */
    public static String hyphenated(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /**
     * A module directory's workload: the same name, or a workload the directory's
     * name ends with ({@code microservice-kubernetes-demo-catalog} → {@code catalog},
     * {@code tools-descartes-teastore-webui} → {@code teastore-webui}). The longest
     * workload wins so {@code ts-station-food-service} is not taken for {@code food-service}.
     */
    public static String matchWorkloadForModule(String module, Set<String> workloads) {
        String best = null;
        for (String w : workloads) {
            if (w.equalsIgnoreCase(module) || hyphenated(w).equals(hyphenated(module))) return w;
            if (w.length() >= 3 && (module.endsWith("-" + w) || module.endsWith("_" + w))
                    && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
    }

    /**
     * The workload a Compose service name stands for when manifests exist: the same name
     * (or the same name in module spelling), else the one workload that ends with
     * {@code -<name>}; null when none or several do.
     */
    public static String workloadForDeploymentName(String composeName, Set<String> workloads) {
        for (String w : workloads) {
            if (w.equalsIgnoreCase(composeName) || hyphenated(w).equals(hyphenated(composeName))) return w;
        }
        String unique = null;
        for (String w : workloads) {
            if (w.length() > composeName.length() + 1 && w.toLowerCase(Locale.ROOT).endsWith("-" + composeName.toLowerCase(Locale.ROOT))) {
                if (unique != null) return null;
                unique = w;
            }
        }
        return unique;
    }

    /**
     * The service a versioned Deployment is a version of: {@code reviews-v1} → {@code reviews}
     * when a k8s Service named {@code reviews} is declared, or when another workload differs
     * from it only by the version. Any other name is returned as is — a trailing {@code -v2}
     * alone is not evidence, and nothing else is ever stripped (rule 19).
     */
    public static String unversionedWorkload(String raw, Set<String> k8sServices, Set<String> allWorkloads) {
        Matcher m = VERSION_SUFFIX.matcher(raw);
        if (!m.matches()) return raw;
        String base = m.group(1);
        if (k8sServices.contains(base)) return base;
        for (String other : allWorkloads) {
            if (other.equals(raw)) continue;
            Matcher o = VERSION_SUFFIX.matcher(other);
            if (o.matches() && o.group(1).equals(base)) return base;
        }
        return raw;
    }

    /**
     * Lower-cased host name with scheme/port/path/userinfo stripped, and with exactly the
     * DNS suffixes that are suffixes removed: a cluster suffix together with its namespace
     * label ({@code <svc>.<ns>.svc.cluster.local} → {@code <svc>}), or a trailing label that
     * is a namespace the manifests declare ({@code <svc>.<ns>} → {@code <svc>}). Any other
     * dotted name is kept whole: a Compose service may be called {@code catalog.api}, and a
     * domain cut to its first label reads as a service that does not exist (rule 36, 2026-10-26).
     */
    public static String cleanHost(String raw, Set<String> namespaces) {
        // Scheme, userinfo, path, port, quotes off; a placeholder is no usable host (convention ②).
        String s = AddressShape.host(raw);
        if (s.isEmpty()) return "";
        boolean cluster = false;
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                RuleTrace.hit(36, "cluster-suffix " + s);
                s = s.substring(0, s.length() - suffix.length());
                cluster = true;
                break;
            }
        }
        int dot = s.lastIndexOf('.');
        if (RuleTrace.hitIf(dot > 0 && !cluster && namespaces.contains(s.substring(dot + 1)), 36, "ns-strip " + s) || (dot > 0 && cluster)) s = s.substring(0, dot);
        return s;
    }

    /** The namespace label of an in-cluster host ({@code travels.travel-agency:8000}, {@code x.ns.svc.cluster.local}), or null. */
    public static String namespaceLabel(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        for (String suffix : CLUSTER_SUFFIXES) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        String[] labels = s.split("\\.");
        return labels.length == 2 && labels[1].matches(DNS_LABEL) ? labels[1] : null;
    }

    /** A real external host: a dotted domain that is not an in-cluster k8s name or a raw IP. */
    public static boolean isExternal(String host, String namespace) {
        if (host == null || !host.contains(".")) return false;
        if (host.endsWith(".svc.cluster.local") || host.endsWith(".cluster.local")
                || host.endsWith(".svc") || host.endsWith(".local")) return false;
        if (namespace != null && !namespace.isBlank()
                && host.endsWith("." + namespace.toLowerCase(Locale.ROOT))) return false;
        if (host.matches("[0-9.]+")) return false; // a bare IP, not a business name
        return true;
    }

    /** The longest common prefix of a set of names ("" for none). */
    public static String commonPrefix(Set<String> names) {
        String p = null;
        for (String n : names) {
            if (p == null) {
                p = n;
                continue;
            }
            int i = 0;
            while (i < p.length() && i < n.length() && p.charAt(i) == n.charAt(i)) i++;
            p = p.substring(0, i);
        }
        return p == null ? "" : p;
    }

    // ---- paths

    public static String firstPathSegment(String file) {
        if (file == null || file.isBlank()) return null;
        String path = file.replace('\\', '/');
        int slash = path.indexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    public static String parentDir(String file) {
        int slash = file.lastIndexOf('/');
        return slash < 0 ? "" : file.substring(0, slash);
    }

    /** {@code config/shared/account-service.yml} → {@code account-service}; null when no name. */
    public static String fileStem(String file) {
        if (file == null || file.isBlank()) return null;
        String base = file.substring(file.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        String stem = (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT);
        return stem.isEmpty() ? null : stem;
    }

    public static boolean isConfigFile(String file) {
        String f = file == null ? "" : file.toLowerCase(Locale.ROOT);
        return f.endsWith(".yml") || f.endsWith(".yaml") || f.endsWith(".properties");
    }

    /** Whether a file is a Compose document, by the spec's and the legacy names. */
    public static boolean isComposeFile(String file) {
        String base = file == null ? "" : file.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return (base.startsWith("docker-compose") || base.startsWith("compose.")) && (base.endsWith(".yml") || base.endsWith(".yaml"));
    }

    /** {@code owner/repo.git} → {@code repo}. */
    public static String repoShortName(String repoName) {
        if (repoName == null || repoName.isBlank()) return null;
        String s = repoName.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.replaceAll("\\.git$", "");
    }
}
