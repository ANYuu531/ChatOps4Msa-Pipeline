package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.AskItem;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficRunner;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 3 — "ask the human" — end to end through the pure-Java layer: how an ask is
 * declared in the collection, how a request that needs it is held back instead of
 * sent half-filled, and how an answer is bound on the next round.
 *
 * No Spring context and no network: the held-back requests are refused before any
 * HTTP is attempted, which is exactly the property being pinned.
 */
public class AskFlowTest {

    /** A deposit journey whose account number the generator could not derive. */
    private static final String WITH_ASK = "{\n"
            + "  \"info\": { \"name\": \"t\", \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\" },\n"
            + "  \"variable\": [\n"
            + "    { \"key\": \"username\", \"value\": \"testuser\" },\n"
            + "    { \"key\": \"account_num\", \"value\": \"\",\n"
            + "      \"description\": \"ASK: an existing account number to deposit into | e.g. 1011226111\" },\n"
            + "    { \"key\": \"api_token\", \"value\": \"\", \"description\": \"ASK: the API token for this deployment\" }\n"
            + "  ],\n"
            + "  \"item\": [\n"
            + "    { \"name\": \"home\", \"request\": { \"method\": \"GET\",\n"
            + "        \"url\": { \"raw\": \"{{baseUrl}}/home\" }, \"description\": \"frontend -> balancereader\" } },\n"
            + "    { \"name\": \"deposit\", \"request\": { \"method\": \"POST\",\n"
            + "        \"url\": { \"raw\": \"{{baseUrl}}/deposit\" },\n"
            + "        \"body\": { \"mode\": \"urlencoded\", \"urlencoded\": [\n"
            + "          { \"key\": \"account_num\", \"value\": \"{{account_num}}\" },\n"
            + "          { \"key\": \"amount\", \"value\": \"100\" } ] },\n"
            + "        \"description\": \"frontend -> ledgerwriter\" } },\n"
            + "    { \"name\": \"account detail\", \"request\": { \"method\": \"GET\",\n"
            + "        \"url\": { \"raw\": \"{{baseUrl}}/accounts/{{account_num}}\" },\n"
            + "        \"description\": \"frontend -> userservice\" } }\n"
            + "  ]\n"
            + "}";

    @Test
    void anAskIsParsedOutOfTheCollectionAndKeptOutOfTheBoundVariables() {
        TrafficScenario scenario = TrafficScenario.parse(WITH_ASK);

        // A normal variable still binds.
        assertEquals("testuser", scenario.variables.get("username"));

        // An ASK: variable must NOT bind — an empty binding would substitute "" into
        // the request and turn a held-back step into a misleading 4xx.
        assertFalse(scenario.variables.containsKey("account_num"));
        assertEquals(2, scenario.asks.size());

        AskItem account = scenario.asks.get(0);
        assertEquals("account_num", account.key);
        assertEquals("an existing account number to deposit into", account.question);
        assertEquals("1011226111", account.example);
        assertFalse(account.secret);

        // A credential-shaped key is flagged so it is never echoed to the channel.
        AskItem token = scenario.asks.get(1);
        assertEquals("api_token", token.key);
        assertTrue(token.secret);
        assertEquals("the API token for this deployment", token.question);
        assertEquals("", token.example);
    }

    @Test
    void anAskWhoseKeyCannotBeAPlaceholderIsIgnoredWithAWarning() {
        // A key with a space can neither substitute as {{...}} nor be a form input id;
        // honouring it would take the whole "Provide values" button down.
        String json = "{ \"info\": {}, \"variable\": ["
                + "{ \"key\": \"account num\", \"value\": \"\", \"description\": \"ASK: the account\" } ],"
                + " \"item\": [ { \"name\": \"a\", \"request\": { \"method\": \"GET\", \"url\": \"/\" } } ] }";

        TrafficScenario scenario = TrafficScenario.parse(json);

        assertTrue(scenario.asks.isEmpty());
        assertTrue(scenario.warnings.stream().anyMatch(w -> w.contains("account num")),
                "an ignored ask must be visible, not silently dropped");
    }

    @Test
    void anEmptyVariableWithoutAnAskDescriptionIsStillAPlainVariable() {
        String json = "{ \"info\": {}, \"variable\": ["
                + "{ \"key\": \"note\", \"value\": \"\", \"description\": \"filled in later\" } ],"
                + " \"item\": [ { \"name\": \"a\", \"request\": { \"method\": \"GET\", \"url\": \"/\" } } ] }";

        TrafficScenario scenario = TrafficScenario.parse(json);

        assertTrue(scenario.asks.isEmpty());
        assertTrue(scenario.variables.containsKey("note"));
    }

    @Test
    void requestsNeedingAnUnansweredValueAreHeldBackNotSent() {
        TrafficScenario scenario = TrafficScenario.parse(WITH_ASK);

        // Nothing is actually sent for the held-back steps, so an unroutable base URL
        // is fine: reaching the network at all would be the bug.
        TrafficRunner.RunReport report = new TrafficRunner()
                .run(scenario, "http://127.0.0.1:1", 1);

        assertEquals(3, report.steps.size());

        // The step that needs the value only in its BODY is held back...
        TrafficRunner.StepResult deposit = report.steps.get(1);
        assertTrue(deposit.awaitingValue, "a pending value in the body must hold the request back");
        assertTrue(deposit.error.contains("account_num"));

        // ...and so is the one that needs it in its URL.
        TrafficRunner.StepResult detail = report.steps.get(2);
        assertTrue(detail.awaitingValue, "a pending value in the path must hold the request back");

        // Held-back steps are not counted as driven traffic or as transport failures:
        // they produced no evidence and nothing went wrong.
        assertEquals(1, report.executed);
        assertEquals(1, report.transportErrors);   // only "home", which really was attempted

        String rendered = report.render("http://127.0.0.1:1", 1);
        assertTrue(rendered.contains("[WAIT]"), "a held-back step reads as WAIT, not ERROR");
        assertTrue(rendered.contains("{{account_num}} — an existing account number to deposit into"),
                "the report tells the next round what is being asked for");
    }

    @Test
    void anUnresolvedVariableThatWasNeverAskedForStillReadsAsAFailedCapture() {
        String json = "{ \"info\": {}, \"item\": [ { \"name\": \"detail\", \"request\": "
                + "{ \"method\": \"GET\", \"url\": { \"raw\": \"{{baseUrl}}/owners/{{ownerId}}\" } } } ] }";

        TrafficRunner.RunReport report = new TrafficRunner()
                .run(TrafficScenario.parse(json), "http://127.0.0.1:1", 1);

        TrafficRunner.StepResult step = report.steps.get(0);
        assertFalse(step.awaitingValue);
        assertTrue(step.error.contains("never captured by an earlier step"));
    }

    @Test
    void suppliedValuesBindOnTheNextRoundAndTheAskDisappears() {
        TrafficScenario scenario = TrafficScenario.parse(WITH_ASK);
        scenario.applySuppliedValues(Map.of("account_num", "1011226111"));

        assertEquals("1011226111", scenario.variables.get("account_num"));
        // Answered: no longer asked for. The unanswered one is still pending.
        assertEquals(1, scenario.asks.size());
        assertEquals("api_token", scenario.asks.get(0).key);
        assertEquals(java.util.Set.of("api_token"), scenario.askKeys());

        TrafficRunner.RunReport report = new TrafficRunner()
                .run(scenario, "http://127.0.0.1:1", 1);
        // The deposit no longer waits on anything: it is attempted (and fails on the
        // unroutable host, which is a transport error, not a held-back step).
        assertFalse(report.steps.get(1).awaitingValue);
        assertFalse(report.steps.get(2).awaitingValue);
    }

    @Test
    void asksAndAnswersSurviveTheCheckpointRoundTrip() {
        List<AskItem> asks = TrafficScenario.parse(WITH_ASK).asks;

        List<AskItem> restored = AskItem.fromJson(AskItem.toJson(asks));
        assertEquals(2, restored.size());
        assertEquals("account_num", restored.get(0).key);
        assertEquals("1011226111", restored.get(0).example);
        assertTrue(restored.get(1).secret);

        Map<String, String> values = AskItem.valuesFromJson(
                AskItem.valuesToJson(Map.of("account_num", "1011226111")));
        assertEquals("1011226111", values.get("account_num"));

        // A missing or corrupt stage means "nothing pending", never a failed run.
        assertTrue(AskItem.fromJson("").isEmpty());
        assertTrue(AskItem.fromJson("not json").isEmpty());
        assertTrue(AskItem.valuesFromJson("not json").isEmpty());
    }

    @Test
    void answersAreCleanedBeforeTheyAreSubstitutedIntoARequest() {
        // A stray {{...}} would be re-expanded by the runner, and a newline would
        // corrupt a header.
        assertEquals("abc", AskItem.sanitize("  {{abc}}  "));
        assertEquals("a b", AskItem.sanitize("a\nb"));
        assertEquals(200, AskItem.sanitize("x".repeat(500)).length());
        assertEquals("", AskItem.sanitize(null));
    }

    @Test
    void aSecretValueIsMaskedWhenEchoedBackToTheChannel() {
        AskItem token = new AskItem("api_token", "the token", "");
        AskItem account = new AskItem("account_num", "the account", "");

        assertFalse(token.maskIfSecret("s3cret-value").contains("s3cret"));
        assertEquals("1011226111", account.maskIfSecret("1011226111"));
    }
}
