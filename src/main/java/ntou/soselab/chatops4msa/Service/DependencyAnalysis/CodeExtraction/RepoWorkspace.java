package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A shallow clone of the target repository, cloned once and shared by stack
 * detection and every extraction tier, then deleted.
 *
 * Requires the `git` binary. It is NOT present in eclipse-temurin base images —
 * the Dockerfile installs it. Without it every clone fails and code extraction
 * silently degrades to nothing.
 */
public class RepoWorkspace implements AutoCloseable {

    private static final long CLONE_TIMEOUT_SECONDS = 120;

    private final Path root;
    private final boolean cloned;
    private final String failure;

    private RepoWorkspace(Path root, boolean cloned, String failure) {
        this.root = root;
        this.cloned = cloned;
        this.failure = failure;
    }

    public Path getRoot() {
        return root;
    }

    public boolean isCloned() {
        return cloned;
    }

    /** Null when the clone succeeded. */
    public String getFailure() {
        return failure;
    }

    /**
     * @param repo "owner/repo", or a full clone URL.
     */
    public static RepoWorkspace clone(String repo) {
        String url = toCloneUrl(repo.trim());
        Path directory;
        try {
            directory = Files.createTempDirectory("code-extract-");
        } catch (Exception e) {
            return new RepoWorkspace(null, false, "cannot create temp directory: " + e.getMessage());
        }

        String command = "git clone --depth 1 " + url;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "git", "clone", "--depth", "1", url, directory.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }

            if (!process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                deleteRecursively(directory);
                return new RepoWorkspace(null, false,
                        command + " timed out after " + CLONE_TIMEOUT_SECONDS + "s");
            }
            if (process.exitValue() != 0) {
                deleteRecursively(directory);
                return new RepoWorkspace(null, false,
                        command + " failed (exit " + process.exitValue() + "): " + output.toString().trim());
            }
            return new RepoWorkspace(directory, true, null);

        } catch (Exception e) {
            deleteRecursively(directory);
            String hint = (e instanceof java.io.IOException)
                    ? " (is the `git` binary installed in this image?)" : "";
            return new RepoWorkspace(null, false,
                    command + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + hint);
        }
    }

    private static String toCloneUrl(String repo) {
        if (repo.startsWith("http://") || repo.startsWith("https://") || repo.startsWith("git@")) {
            return repo;
        }
        return "https://github.com/" + repo + ".git";
    }

    @Override
    public void close() {
        deleteRecursively(root);
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) return;
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
            // best-effort cleanup of a temp directory
        }
    }
}
