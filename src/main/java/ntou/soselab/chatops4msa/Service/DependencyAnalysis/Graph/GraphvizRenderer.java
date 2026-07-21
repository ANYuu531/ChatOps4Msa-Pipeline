package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Renders Graphviz DOT to a PNG by shelling out to the {@code dot} binary.
 *
 * The binary is installed in the application image (see the Dockerfile). Keeping
 * it a subprocess — rather than a JVM Graphviz port — means the rendering matches
 * exactly what a human gets from {@code dot} and adds no heavyweight dependency.
 *
 * It must NEVER throw: rendering a picture is a convenience on top of the report,
 * so if {@code dot} is missing (e.g. running outside the image) or fails, this
 * returns {@code null} and the caller falls back to attaching the DOT/Mermaid text.
 */
public class GraphvizRenderer {

    private static final long TIMEOUT_SECONDS = 15;

    private GraphvizRenderer() {
    }

    /** @return the PNG bytes, or {@code null} if {@code dot} is unavailable or failed. */
    public static byte[] toPng(String dot) {
        if (dot == null || dot.isBlank()) return null;
        try {
            Process process = new ProcessBuilder("dot", "-Tpng")
                    .redirectErrorStream(false)
                    .start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(dot.getBytes(StandardCharsets.UTF_8));
            }

            // Read stdout fully before waitFor, so a large PNG cannot fill the pipe
            // buffer and deadlock the subprocess.
            byte[] png;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                process.getInputStream().transferTo(out);
                png = out.toByteArray();
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || png.length == 0) return null;
            return png;

        } catch (Exception e) {
            // IOException here usually means the binary is not on PATH.
            System.out.println("[WARNING] graphviz 'dot' unavailable or failed ("
                    + e.getMessage() + "); falling back to text graph.");
            return null;
        }
    }
}
