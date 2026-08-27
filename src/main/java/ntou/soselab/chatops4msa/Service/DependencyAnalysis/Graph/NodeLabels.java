package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

/**
 * Formats the deployment metadata shown under a node's name, shared by both emitters
 * so the DOT and Mermaid renderings cannot drift apart.
 *
 * The job here is <b>brevity</b>, and it is not cosmetic: a node label is the width of
 * its longest line, so one verbose label stretches its whole tier and, with it, the
 * page. A real Bank of Anthos run produced
 * {@code frontend:v0.6.10@sha256:076294ce…d65345 · 1/1 · 2026-08-12} on every node and
 * blew the graph out to 5000px wide — the layering was correct but unreadable.
 */
final class NodeLabels {

    private NodeLabels() {
    }

    /**
     * The image reduced to what a reader actually needs, or null when unknown.
     *
     * <ul>
     *   <li>The registry/project prefix goes: {@code …/bank-of-anthos/frontend:v0.6.10}
     *       → {@code frontend:v0.6.10}.</li>
     *   <li><b>The digest goes.</b> {@code @sha256:076294ce…} is 71 characters that
     *       identify the image to a machine and tell a human nothing the tag does not.</li>
     *   <li>When the repository name just repeats the node's own name (the common case —
     *       workload {@code frontend} running image {@code frontend:v0.6.10}), only the
     *       tag is kept: the name is already on the line above. A name that differs
     *       (petclinic's {@code api-gateway} runs {@code spring-petclinic-api-gateway})
     *       is genuine information and is kept in full.</li>
     * </ul>
     */
    static String imageTag(String image, String nodeId) {
        if (image == null || image.isBlank() || "<none>".equals(image)) return null;
        String s = image.trim();

        // Drop the digest first: it may contain no '/' but always follows the tag.
        int at = s.indexOf('@');
        if (at > 0) s = s.substring(0, at);

        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        if (s.isEmpty()) return null;

        int colon = s.lastIndexOf(':');
        if (colon > 0 && nodeId != null) {
            String repo = s.substring(0, colon);
            String tag = s.substring(colon + 1);
            if (!tag.isEmpty() && repo.equalsIgnoreCase(nodeId)) return tag;
        }
        return s;
    }

    /** The date part of an ISO-8601 instant ("2026-07-20T10:16:30Z" -> "2026-07-20"). */
    static String dateOnly(String iso) {
        if (iso == null || iso.isBlank()) return null;
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }
}
