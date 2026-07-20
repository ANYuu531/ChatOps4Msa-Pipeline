package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An external (outside-the-mesh) host that the source code talks to.
 *
 * Istio cannot see these on its own: istio_requests_total with
 * reporter="destination" is emitted by the CALLEE's sidecar, and an external host
 * has no sidecar. Worse, without a ServiceEntry the caller's sidecar routes the
 * traffic through PassthroughCluster and the real hostname is lost from the
 * metric labels.
 *
 * So the code is the only place the hostname is knowable — and once we know it we
 * can emit a ServiceEntry, which is exactly what lets Istio observe and attribute
 * the edge at runtime. That is the point of this class: it turns a static finding
 * into something the mesh can confirm.
 */
public class ExternalHost {

    public final String host;
    public final int port;
    /** HTTP or HTTPS (as Istio's ServiceEntry spells protocols). */
    public final String protocol;
    /** Where in the source this host was seen. */
    public final Set<String> evidence = new LinkedHashSet<>();

    public ExternalHost(String host, int port, String protocol) {
        this.host = host;
        this.port = port;
        this.protocol = protocol;
    }

    /** Identity: one ServiceEntry per host+port. */
    public String key() {
        return host + ":" + port;
    }

    /** A DNS-1123 name for the ServiceEntry resource. */
    public String resourceName() {
        String name = host.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        name = name.replaceAll("^-+|-+$", "");
        if (name.isEmpty()) name = "external";
        if (port != 80 && port != 443) name = name + "-" + port;
        if (name.length() > 253) name = name.substring(0, 253);
        return name;
    }

    // ---------- checkpoint serialization ----------
    // Stored with the analysis checkpoint so the ServiceEntry manifest can be
    // rendered later (and after a resume) without cloning the repository again.

    public static JSONArray toJson(List<ExternalHost> hosts) {
        JSONArray array = new JSONArray();
        for (ExternalHost host : hosts) {
            array.put(new JSONObject()
                    .put("host", host.host)
                    .put("port", host.port)
                    .put("protocol", host.protocol)
                    .put("evidence", new JSONArray(host.evidence)));
        }
        return array;
    }

    public static List<ExternalHost> fromJson(String json) {
        List<ExternalHost> hosts = new ArrayList<>();
        if (json == null || json.isBlank()) return hosts;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                ExternalHost host = new ExternalHost(
                        object.getString("host"),
                        object.getInt("port"),
                        object.optString("protocol", "HTTP"));
                JSONArray evidence = object.optJSONArray("evidence");
                if (evidence != null) {
                    for (int j = 0; j < evidence.length(); j++) host.evidence.add(evidence.getString(j));
                }
                hosts.add(host);
            }
        } catch (Exception ignored) {
            // a corrupt checkpoint entry simply yields no hosts
        }
        return hosts;
    }
}
