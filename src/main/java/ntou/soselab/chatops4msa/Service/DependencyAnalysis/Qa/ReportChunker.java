package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown document into retrievable chunks along its headings.
 *
 * The report and the evidence notes are all heading-structured Markdown (the report
 * prompt mandates "# N." sections with "### Candidate: a -> b" blocks), so a heading is
 * the natural unit of retrieval: one candidate, one component, one collection-status
 * entry. A chunk carries its heading path as the title so the model — and the user —
 * can see where a passage came from.
 *
 * Deterministic and dependency-free on purpose: the corpus is one report, built once
 * per analysis, and a chunking that changes between runs would make the answers drift
 * for reasons unrelated to the evidence.
 */
public final class ReportChunker {

    /** Chunks longer than this are split on paragraph boundaries. */
    static final int MAX_CHARS = 1800;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");

    private ReportChunker() {
    }

    /**
     * @param source   the document label the chunks carry (e.g. "report")
     * @param markdown the document; blank input yields no chunks
     */
    public static List<TextChunk> chunk(String source, String markdown) {
        List<TextChunk> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return out;

        String section = "";   // the current level-1/2 heading
        String sub = "";       // the current level-3+ heading under it
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\r?\n")) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                flush(out, source, title(section, sub), body);
                if (m.group(1).length() <= 2) {
                    section = m.group(2);
                    sub = "";
                } else {
                    sub = m.group(2);
                }
                continue;
            }
            body.append(line).append('\n');
        }
        flush(out, source, title(section, sub), body);
        return out;
    }

    private static String title(String section, String sub) {
        if (section.isEmpty()) return sub;
        if (sub.isEmpty()) return section;
        return section + " › " + sub;
    }

    private static void flush(List<TextChunk> out, String source, String title, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) return;
        for (String piece : split(text)) {
            out.add(new TextChunk(source + "#" + out.size(), source, title, piece));
        }
    }

    /** Splits an over-long body on blank lines, then on lines, then hard, greedily. */
    static List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= MAX_CHARS) {
            pieces.add(text);
            return pieces;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\\s*\n")) {
            if (paragraph.length() > MAX_CHARS) {
                // A single huge paragraph (a ledger, a code listing): fall back to lines.
                for (String line : paragraph.split("\n")) appendUnit(pieces, current, line);
                continue;
            }
            appendUnit(pieces, current, paragraph);
        }
        if (current.length() > 0) pieces.add(current.toString().strip());
        return pieces;
    }

    private static void appendUnit(List<String> pieces, StringBuilder current, String unit) {
        if (unit.length() > MAX_CHARS) {
            if (current.length() > 0) {
                pieces.add(current.toString().strip());
                current.setLength(0);
            }
            for (int i = 0; i < unit.length(); i += MAX_CHARS) {
                pieces.add(unit.substring(i, Math.min(unit.length(), i + MAX_CHARS)));
            }
            return;
        }
        if (current.length() + unit.length() + 2 > MAX_CHARS && current.length() > 0) {
            pieces.add(current.toString().strip());
            current.setLength(0);
        }
        if (current.length() > 0) current.append("\n\n");
        current.append(unit);
    }
}
