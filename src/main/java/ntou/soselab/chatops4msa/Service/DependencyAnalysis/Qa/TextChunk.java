package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One retrievable passage of a report archive: a heading-delimited piece of the
 * report or of an evidence stage, with an optional embedding vector.
 *
 * The chunk keeps its {@code source} (which document it came from) and {@code title}
 * (the heading path inside that document) so a retrieved passage can be shown to the
 * model — and cited back to the user — as "report › 4. Synchronous Dependency
 * Candidates › Candidate: frontend -> userservice" rather than as anonymous text.
 */
public final class TextChunk {

    public final String id;
    /** Which document: "report", "docs+code notes", "kubernetes notes", ... */
    public final String source;
    /** Heading path inside the document; may be empty for an un-headed preamble. */
    public final String title;
    public final String text;
    /** Embedding of {@code title + text}; {@code null} when embeddings were not computed. */
    public double[] embedding;

    public TextChunk(String id, String source, String title, String text) {
        this.id = id;
        this.source = source == null ? "" : source;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
    }

    /** The text the retriever indexes: heading words count as much as body words. */
    public String indexedText() {
        return title.isEmpty() ? text : title + "\n" + text;
    }

    /** How the passage is shown to the model, with its provenance in front. */
    public String render() {
        String head = title.isEmpty() ? source : source + " › " + title;
        return "[" + head + "]\n" + text;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject()
                .put("id", id)
                .put("source", source)
                .put("title", title)
                .put("text", text);
        if (embedding != null) {
            JSONArray vector = new JSONArray();
            for (double v : embedding) vector.put(v);
            json.put("embedding", vector);
        }
        return json;
    }

    static TextChunk fromJson(JSONObject json) {
        TextChunk chunk = new TextChunk(
                json.optString("id", ""),
                json.optString("source", ""),
                json.optString("title", ""),
                json.optString("text", ""));
        JSONArray vector = json.optJSONArray("embedding");
        if (vector != null && vector.length() > 0) {
            double[] embedding = new double[vector.length()];
            for (int i = 0; i < vector.length(); i++) embedding[i] = vector.optDouble(i, 0);
            chunk.embedding = embedding;
        }
        return chunk;
    }
}
