package ntou.soselab.chatops4msa.Service.DependencyAnalysis.CodeExtraction.predicate;

import java.util.List;

/**
 * Resolves a capture name to the texts captured under it in the current match.
 *
 * A capture can bind more than one node in a single match (a quantified capture),
 * which is why this returns a list rather than one string. An empty list means the
 * capture did not bind here — predicates treat that as "cannot be satisfied".
 */
@FunctionalInterface
public interface Bindings {
    List<String> valuesOf(String captureName);
}
