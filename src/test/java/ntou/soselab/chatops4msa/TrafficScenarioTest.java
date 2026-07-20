package ntou.soselab.chatops4msa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Traffic.TrafficScenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for parsing a Postman Collection v2.1 into the internal traffic
 * model — no Spring context and no network, so they always run.
 *
 * They pin the migration's contract: the wire format is a standard Postman
 * collection, and {@link TrafficScenario#parse} maps its collection variables,
 * request items, URL objects, raw bodies, {@code pm.collectionVariables.set(...)}
 * captures (into Jayway JsonPath) and {@code UNREACHABLE:} markers onto the model
 * the runner already understands.
 */
public class TrafficScenarioTest {

    private static final String SOCK_SHOP = "{\n"
            + "  \"info\": { \"name\": \"t\", \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\" },\n"
            + "  \"variable\": [ { \"key\": \"username\", \"value\": \"user\" }, { \"key\": \"password\", \"value\": \"pass\" } ],\n"
            + "  \"item\": [\n"
            + "    {\n"
            + "      \"name\": \"log in\",\n"
            + "      \"request\": {\n"
            + "        \"method\": \"post\",\n"
            + "        \"header\": [ { \"key\": \"Content-Type\", \"value\": \"application/json\" } ],\n"
            + "        \"url\": { \"raw\": \"{{baseUrl}}/login\", \"host\": [\"{{baseUrl}}\"], \"path\": [\"login\"] },\n"
            + "        \"body\": { \"mode\": \"raw\", \"raw\": \"{\\\"username\\\":\\\"{{username}}\\\"}\", \"options\": { \"raw\": { \"language\": \"json\" } } },\n"
            + "        \"description\": \"front-end -> user\"\n"
            + "      },\n"
            + "      \"event\": [ { \"listen\": \"test\", \"script\": { \"exec\": [\n"
            + "        \"pm.collectionVariables.set(\\\"token\\\", pm.response.json().token)\",\n"
            + "        \"pm.collectionVariables.set(\\\"cid\\\", pm.response.json().data.id)\",\n"
            + "        \"pm.environment.set(\\\"first\\\", pm.response.json()[0].id)\"\n"
            + "      ] } } ]\n"
            + "    },\n"
            + "    {\n"
            + "      \"name\": \"add item to cart\",\n"
            + "      \"request\": {\n"
            + "        \"method\": \"POST\",\n"
            + "        \"header\": [ { \"key\": \"Authorization\", \"value\": \"Bearer {{token}}\" } ],\n"
            + "        \"url\": \"{{baseUrl}}/cart\",\n"
            + "        \"description\": \"front-end -> carts\"\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    @Test
    void parsesVariablesItemsUrlBodyAndDescription() {
        TrafficScenario scenario = TrafficScenario.parse(SOCK_SHOP);

        assertEquals("user", scenario.variables.get("username"));
        assertEquals("pass", scenario.variables.get("password"));
        assertEquals(2, scenario.steps.size());

        TrafficScenario.Step login = scenario.steps.get(0);
        assertEquals("log in", login.name);
        assertEquals("POST", login.method); // lower-case method normalised
        assertEquals("{{baseUrl}}/login", login.path);
        assertEquals("application/json", login.headers.get("Content-Type"));
        assertEquals("{\"username\":\"{{username}}\"}", login.body);
        assertEquals("front-end -> user", login.exercises);
        assertTrue(login.send);

        // A URL given as a plain string is accepted too.
        assertEquals("{{baseUrl}}/cart", scenario.steps.get(1).path);
        assertEquals("Bearer {{token}}", scenario.steps.get(1).headers.get("Authorization"));
    }

    @Test
    void convertsPmCaptureAccessChainsToJsonPath() {
        TrafficScenario.Step login = TrafficScenario.parse(SOCK_SHOP).steps.get(0);

        assertEquals("$.token", login.capture.get("token"));     // .name
        assertEquals("$.data.id", login.capture.get("cid"));     // .a.b
        assertEquals("$[0].id", login.capture.get("first"));     // [int] via pm.environment.set
    }

    @Test
    void marksUnreachableItemAsNotSent() {
        String collection = "{ \"item\": [\n"
                + "  { \"name\": \"UNREACHABLE: front-end -> payment\", \"request\": {\n"
                + "      \"method\": \"GET\", \"url\": { \"raw\": \"{{baseUrl}}/\" },\n"
                + "      \"description\": \"CANNOT REACH: needs a real card token\" } }\n"
                + "] }";
        TrafficScenario scenario = TrafficScenario.parse(collection);

        assertEquals(1, scenario.steps.size());
        TrafficScenario.Step gap = scenario.steps.get(0);
        assertFalse(gap.send);
        assertEquals("CANNOT REACH: needs a real card token", gap.exercises);
    }

    @Test
    void skipsMalformedItemButKeepsTheRest() {
        String collection = "{ \"item\": [\n"
                + "  { \"name\": \"folder\", \"item\": [] },\n"                       // a folder, no request
                + "  \"not-an-object\",\n"                                             // wrong type
                + "  { \"name\": \"ok\", \"request\": { \"method\": \"GET\", \"url\": \"/health\" } }\n"
                + "] }";
        TrafficScenario scenario = TrafficScenario.parse(collection);

        assertEquals(1, scenario.steps.size());
        assertEquals("/health", scenario.steps.get(0).path);
        assertFalse(scenario.warnings.isEmpty(), "the skipped items should be reported as warnings");
    }

    @Test
    void flagsCaptureWrittenInAnUnsupportedForm() {
        String collection = "{ \"item\": [\n"
                + "  { \"name\": \"x\", \"request\": { \"method\": \"GET\", \"url\": \"/\" },\n"
                + "    \"event\": [ { \"listen\": \"test\", \"script\": { \"exec\": [\n"
                + "      \"pm.collectionVariables.set(\\\"t\\\", pm.response.headers.get(\\\"X-Token\\\"))\"\n"
                + "    ] } } ] }\n"
                + "] }";
        TrafficScenario scenario = TrafficScenario.parse(collection);

        assertTrue(scenario.steps.get(0).capture.isEmpty(), "the header capture is not the supported subset");
        assertTrue(scenario.warnings.stream().anyMatch(w -> w.contains("unsupported form")),
                "an unsupported capture should be surfaced as a warning, not silently dropped");
    }

    @Test
    void ignoresAssertionsWhileStillReadingTheCapture() {
        String collection = "{ \"item\": [\n"
                + "  { \"name\": \"x\", \"request\": { \"method\": \"GET\", \"url\": \"/\" },\n"
                + "    \"event\": [ { \"listen\": \"test\", \"script\": { \"exec\": [\n"
                + "      \"pm.test(\\\"status ok\\\", function () { pm.response.to.have.status(200); });\",\n"
                + "      \"pm.collectionVariables.set(\\\"id\\\", pm.response.json().id)\"\n"
                + "    ] } } ] }\n"
                + "] }";
        TrafficScenario scenario = TrafficScenario.parse(collection);

        assertEquals("$.id", scenario.steps.get(0).capture.get("id"));
        // pm.test is not a .set(...), so it must NOT be flagged as an unsupported capture.
        assertTrue(scenario.warnings.isEmpty(), "assertions should be ignored silently");
    }

    @Test
    void rejectsCollectionWithNoItems() {
        assertThrows(IllegalArgumentException.class, () -> TrafficScenario.parse("{ \"item\": [] }"));
        assertThrows(IllegalArgumentException.class, () -> TrafficScenario.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> TrafficScenario.parse(""));
    }
}
