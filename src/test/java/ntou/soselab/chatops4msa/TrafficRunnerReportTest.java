package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The execution report is fed back into the coverage-refinement round, so a failed
 * step must carry the WHY (the response body snippet), not just the status code.
 * Pure render test — no network.
 */
public class TrafficRunnerReportTest {

    @Test
    void failureResponseSnippetIsRenderedForTheRefinementRound() {
        TrafficRunner.RunReport report = new TrafficRunner.RunReport();

        TrafficRunner.StepResult ok = new TrafficRunner.StepResult();
        ok.name = "home"; ok.method = "GET"; ok.url = "http://x/home"; ok.status = 200;
        report.steps.add(ok);

        TrafficRunner.StepResult bad = new TrafficRunner.StepResult();
        bad.name = "deposit"; bad.method = "POST"; bad.url = "http://x/deposit"; bad.status = 400;
        bad.exercises = "frontend -> ledgerwriter";
        bad.responseSnippet = "amount must be a positive number";
        report.steps.add(bad);
        report.executed = 2;

        String rendered = report.render("http://x", 2);

        assertTrue(rendered.contains("response: amount must be a positive number"),
                "the 4xx body snippet must be in the report so the next round can fix the payload");
        // A 200 carries no snippet line.
        assertFalse(rendered.contains("response: ") && rendered.indexOf("response: ")
                        < rendered.indexOf("frontend -> ledgerwriter"),
                "only the failing step gets a response snippet");
    }
}
