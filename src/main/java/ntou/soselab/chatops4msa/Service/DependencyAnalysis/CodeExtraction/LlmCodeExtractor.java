package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction;

import ntou.soselab.chatops4msa.Service.NLPService.LLMService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tier 3: extraction for stacks we have no tree-sitter grammar for (Go, Node,
 * Ruby, C#, ...). The LLM reads the actual source files — this is deliberately
 * NOT a DeepWiki/documentation question, because documentation omits and lies,
 * whereas the source is the ground truth.
 *
 * Two things keep this affordable and honest:
 *  - a deterministic grep pre-filter picks only files that contain a dependency
 *    signal at all, ranked by signal density, then caps file count and bytes;
 *  - the model is asked for strict JSON and its output is parsed into the same
 *    EdgeLedger the tree-sitter tier produces, so the downstream report cannot
 *    tell which tier ran, and a malformed answer degrades to a warning rather
 *    than to prose leaking into the ledger.
 */
@Component
public class LlmCodeExtractor {

    /** A file with none of these is not worth an LLM call. */
    private static final Pattern SIGNAL = Pattern.compile(
            "(?i)(https?://|\\.get\\(|\\.post\\(|\\.put\\(|\\.delete\\(|fetch\\(|axios|http\\.(Get|Post|Client)|"
                    + "resttemplate|webclient|httpclient|urlopen|"
                    + "kafka|amqp|rabbit|nats|pubsub|sqs|grpc|"
                    + "getenv|process\\.env|ENV\\[|environ|"
                    + "_url|_host|_endpoint|service_name)");

    private static final int MAX_FILES = 40;
    private static final int MAX_TOTAL_BYTES = 240 * 1024;
    private static final int MAX_BATCH_BYTES = 24 * 1024;
    private static final int MAX_FILE_LINES = 400;

    private final LLMService llmService;

    @Autowired
    public LlmCodeExtractor(LLMService llmService) {
        this.llmService = llmService;
    }

    public void extract(Path root, DetectedStack stack, EdgeLedger ledger) {
        List<Candidate> candidates = selectCandidates(root, stack);
        if (candidates.isEmpty()) {
            ledger.addWarning("LLM extraction (" + stack.language
                    + "): no source file contained a dependency signal; nothing to extract.");
            return;
        }

        String systemPrompt = readPrompt();
        int parsed = 0;

        for (List<Candidate> batch : batches(candidates)) {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Language: ").append(stack.language).append("\n\n");
            for (Candidate candidate : batch) {
                userPrompt.append("=== FILE: ").append(candidate.relativePath).append(" ===\n");
                userPrompt.append(candidate.numberedContent).append("\n\n");
            }

            try {
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                messages.put(new JSONObject().put("role", "user").put("content", userPrompt.toString()));

                String response = llmService.callAPIFromOutside(messages);
                parsed += parseInto(response, ledger, stack);

            } catch (Exception e) {
                ledger.addWarning("LLM extraction (" + stack.language + ") failed for a batch of "
                        + batch.size() + " files: " + e.getMessage());
            }
        }

        ledger.addFilesParsed(candidates.size());
        if (parsed == 0) {
            ledger.addWarning("LLM extraction (" + stack.language + "): read "
                    + candidates.size() + " candidate files but returned no usable edges.");
        }
    }

    // ---------- candidate selection (deterministic, no LLM) ----------

    private static class Candidate {
        final String relativePath;
        final String numberedContent;
        final int signalCount;
        final int bytes;

        Candidate(String relativePath, String numberedContent, int signalCount, int bytes) {
            this.relativePath = relativePath;
            this.numberedContent = numberedContent;
            this.signalCount = signalCount;
            this.bytes = bytes;
        }
    }

    private List<Candidate> selectCandidates(Path root, DetectedStack stack) {
        List<Candidate> candidates = new ArrayList<>();

        for (Path file : SourceScanner.filesWithExtensions(root, stack.extensions)) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }

            String[] lines = content.split("\n", -1);
            int signals = 0;
            StringBuilder numbered = new StringBuilder();
            int limit = Math.min(lines.length, MAX_FILE_LINES);
            for (int i = 0; i < limit; i++) {
                if (SIGNAL.matcher(lines[i]).find()) signals++;
                // Line numbers are given to the model so the evidence it reports is checkable.
                numbered.append(i + 1).append(": ").append(lines[i]).append('\n');
            }
            if (signals == 0) continue;

            candidates.add(new Candidate(
                    SourceScanner.relative(root, file), numbered.toString(),
                    signals, numbered.length()));
        }

        // Densest signal first, so the caps drop the least interesting files.
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.signalCount).reversed());

        List<Candidate> selected = new ArrayList<>();
        int totalBytes = 0;
        for (Candidate candidate : candidates) {
            if (selected.size() >= MAX_FILES || totalBytes + candidate.bytes > MAX_TOTAL_BYTES) break;
            selected.add(candidate);
            totalBytes += candidate.bytes;
        }
        return selected;
    }

    private List<List<Candidate>> batches(List<Candidate> candidates) {
        List<List<Candidate>> batches = new ArrayList<>();
        List<Candidate> current = new ArrayList<>();
        int bytes = 0;
        for (Candidate candidate : candidates) {
            if (!current.isEmpty() && bytes + candidate.bytes > MAX_BATCH_BYTES) {
                batches.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(candidate);
            bytes += candidate.bytes;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    // ---------- response parsing ----------

    /** @return how many edges were added. */
    private int parseInto(String response, EdgeLedger ledger, DetectedStack stack) {
        JSONArray edges;
        try {
            edges = new JSONArray(extractJsonArray(response));
        } catch (Exception e) {
            ledger.addWarning("LLM extraction (" + stack.language
                    + "): response was not valid JSON and was discarded.");
            return 0;
        }

        int added = 0;
        for (int i = 0; i < edges.length(); i++) {
            try {
                JSONObject edge = edges.getJSONObject(i);
                String section = edge.optString("section", "").trim();
                String file = edge.optString("file", "").trim();
                if (section.isEmpty() || file.isEmpty()) continue;

                JSONObject rawFields = edge.optJSONObject("fields");
                if (rawFields == null || rawFields.isEmpty()) continue;

                Map<String, String> fields = new LinkedHashMap<>();
                for (String key : rawFields.keySet()) {
                    String value = rawFields.optString(key, "").trim();
                    if (!value.isEmpty()) fields.put(key, value);
                }
                if (fields.isEmpty()) continue;

                int line = edge.optInt("line", -1);
                String confidence = edge.optString("confidence", "Medium").trim();
                // Every LLM-derived edge is labelled as such: it was not proven by a parser.
                ledger.add(section, fields, file, line, confidence + " (LLM-extracted)");
                added++;
            } catch (Exception ignored) {
                // skip a malformed entry rather than lose the whole batch
            }
        }
        return added;
    }

    /** Models like to wrap JSON in prose or a ```json fence; take the outermost array. */
    private String extractJsonArray(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end < start) throw new IllegalArgumentException("no JSON array in response");
        return response.substring(start, end + 1);
    }

    private String readPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/code_extraction_llm.txt");
            try (InputStreamReader reader =
                         new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                return FileCopyUtils.copyToString(reader);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read prompts/code_extraction_llm.txt", e);
        }
    }
}
