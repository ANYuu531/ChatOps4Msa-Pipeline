package ntou.soselab.chatops4msa.Service.DependencyAnalysis.Qa;

import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.DependencyGraph;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.GraphEdit;
import ntou.soselab.chatops4msa.Service.DependencyAnalysis.Graph.MermaidEmitter;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The thread's graph edits: "add an edge from X to Y" asks for what is missing, searches
 * the repository for the pair, and writes a human-provenance edge. Offline: a small
 * checkout in a temp directory, no model, no Discord.
 */
public class GraphEditDialogueTest {

    @TempDir
    Path repo;

    private DependencyGraph graph;

    @BeforeEach
    void checkout() throws Exception {
        write("docker-compose.yml", """
                services:
                  orders:
                    build: ./orders
                    environment:
                      - DB_HOST=ordersdb
                  connector:
                    image: debezium/connect
                  ordersdb:
                    image: postgres
                """);
        // The format the static layer does not read: a Debezium connector definition.
        write("connector/config/orders-source.json", """
                {
                  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
                  "database.hostname": "ordersdb",
                  "database.port": "5432"
                }
                """);
        write("orders/src/main/java/Orders.java", """
                class Orders {
                  String label = "Payment status";
                  // String old = "http://ordersdb:5432";
                  @FeignClient(name = "payment") interface PaymentClient {}
                }
                """);
        write("ingress.yaml", """
                rules:
                  - host: orders.example.com
                """);
        graph = new DependencyGraph("");
        graph.addNode("orders", DependencyGraph.KIND_SERVICE);
        graph.addNode("connector", DependencyGraph.KIND_SERVICE);
        graph.addNode("ordersdb", DependencyGraph.KIND_DB);
        graph.addNode("payment", DependencyGraph.KIND_SERVICE);
        graph.addEdge("orders", "ordersdb", "db", DependencyGraph.PROV_CODE, DependencyGraph.CONF_DOCUMENTED, false, 0, "code: docker-compose.yml:5");
    }

    private void write(String rel, String text) throws Exception {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private EvidenceFinder.Context context() {
        return new EvidenceFinder.Context(graph, f -> f.startsWith("orders/") ? "orders" : null,
                Set.of("docker-compose.yml"), List.of());
    }

    private GraphEditDialogue.Env env() {
        return new GraphEditDialogue.Env() {
            public DependencyGraph graph() {
                return graph;
            }

            public List<EvidenceFinder.Evidence> search(String source, String target) {
                return EvidenceFinder.find(repo, context(), source, target);
            }

            public String readLine(String file, int line) {
                return EvidenceFinder.lineOf(repo.resolve(file), line);
            }

            public String user() {
                return "tester";
            }

            public String now() {
                return "2026-11-06";
            }
        };
    }

    // ---------- the search ----------

    @Test
    void findsTheAddressInAFormatTheStaticLayerDoesNotRead() {
        List<EvidenceFinder.Evidence> found = EvidenceFinder.find(repo, context(), "connector", "ordersdb");
        assertFalse(found.isEmpty());
        EvidenceFinder.Evidence top = found.get(0);
        assertEquals("connector/config/orders-source.json:3", top.ref());
        assertEquals(EvidenceFinder.Shape.ADDRESS_KEY, top.shape);
        assertFalse(top.toolRead, "the static layer produced no row from the connector JSON");
        assertEquals("db", top.suggestedType);
    }

    @Test
    void anIngressDomainACommentAndProseAreNotTraces() {
        List<String> refs = new ArrayList<>();
        for (EvidenceFinder.Evidence e : EvidenceFinder.find(repo, context(), "payment", "orders")) refs.add(e.ref());
        assertFalse(refs.contains("ingress.yaml:2"), "orders.example.com is a domain, not the service: " + refs);
        for (EvidenceFinder.Evidence e : EvidenceFinder.find(repo, context(), "orders", "ordersdb")) {
            assertFalse(e.text.startsWith("//"), "commented-out line offered: " + e.ref());
        }
        List<String> payment = new ArrayList<>();
        for (EvidenceFinder.Evidence e : EvidenceFinder.find(repo, context(), "orders", "payment")) payment.add(e.ref());
        assertEquals(List.of("orders/src/main/java/Orders.java:4"), payment, "only the client declaration names payment");
    }

    @Test
    void hostMatchFollowsClusterDns() {
        Set<String> keys = EvidenceFinder.nameKeys("postgres");
        assertEquals(2, EvidenceFinder.hostMatch("postgres", keys));
        assertEquals(2, EvidenceFinder.hostMatch("postgres.default.svc.cluster.local", keys));
        assertEquals(1, EvidenceFinder.hostMatch("postgres.data", keys), "service.namespace, or a domain");
        assertEquals(0, EvidenceFinder.hostMatch("postgres.yas.local.com", keys));
        assertEquals(0, EvidenceFinder.hostMatch("localhost", keys));
        assertEquals(2, EvidenceFinder.hostMatch("order-service", EvidenceFinder.nameKeys("order")));
        assertEquals(2, EvidenceFinder.hostMatch("api.stripe.com", EvidenceFinder.nameKeys("api.stripe.com")), "an external host is named by its domain");
    }

    // ---------- the dialogue ----------

    @Test
    void edgeFoundInTheRepositoryIsAddedAsDocumentedHumanEdge() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"),
                "圖上少了 connector 連到 ordersdb 的線", env());
        assertNotNull(t.next);
        assertTrue(t.reply.contains("connector/config/orders-source.json:3"), t.reply);
        assertTrue(t.reply.contains("靜態層沒有讀這個檔"), t.reply);

        t = GraphEditDialogue.answer(t.next, "1", env());
        assertTrue(t.reply.contains("建議「db」"), t.reply);
        t = GraphEditDialogue.answer(t.next, "好", env());
        assertTrue(t.reply.contains("確認"), t.reply);
        assertTrue(t.reply.contains("documented"), t.reply);
        t = GraphEditDialogue.answer(t.next, "確定", env());

        assertNull(t.next);
        GraphEdit edit = t.applied;
        assertNotNull(edit);
        assertEquals("connector", edit.source);
        assertEquals("ordersdb", edit.target);
        assertEquals("db", edit.type);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, edit.confidence);
        assertEquals(List.of("connector/config/orders-source.json:3"), edit.refs);

        edit.apply(graph);
        DependencyGraph.Edge e = edgeOf(graph, "connector", "ordersdb");
        assertNotNull(e);
        assertTrue(e.provenance.contains(DependencyGraph.PROV_HUMAN));
        assertTrue(MermaidEmitter.emit(graph).contains("human"), "the picture says the edge is a human's");
    }

    @Test
    void unknownNameIsResolvedByAskingAndTheNewNodeGetsAKind() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("orders", "Stripe API"),
                "add an edge from orders to Stripe API", env());
        assertTrue(t.reply.contains("is not on the graph"), t.reply);
        t = GraphEditDialogue.answer(t.next, "new", env());
        assertTrue(t.reply.contains("What is the new node `stripe-api`?"), t.reply);
        t = GraphEditDialogue.answer(t.next, "4", env());
        assertTrue(t.reply.contains("found no trace"), t.reply);
        t = GraphEditDialogue.answer(t.next, "none", env());
        t = GraphEditDialogue.answer(t.next, "ok", env());
        assertTrue(t.reply.contains("external"), "an external host suggests the external type: " + t.reply);
        t = GraphEditDialogue.answer(t.next, "yes", env());
        assertEquals(DependencyGraph.CONF_INFERRED, t.applied.confidence, "the operator's word alone is the lowest grade");
        assertEquals(DependencyGraph.KIND_EXTERNAL, t.applied.targetKind);
        t.applied.apply(graph);
        assertEquals(DependencyGraph.KIND_EXTERNAL, graph.findNode("stripe-api").kind);
    }

    @Test
    void anAnswerThatIsNotOneLeavesTheQuestionOpen() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "add connector -> ordersdb", env());
        GraphEditDialogue.Turn again = GraphEditDialogue.answer(t.next, "7", env());
        assertFalse(again.understood);
        assertNotNull(again.next, "the edit stays open");
        assertTrue(GraphEditDialogue.answer(again.next, "cancel", env()).next == null);
    }

    @Test
    void aQuestionAtTheEvidenceStepIsNotTakenAsTheOperatorsNote() {
        // Found by GraphEditLiveProbeTest: "postgres 被誰用？" asked mid-edit was recorded as the reference.
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "新增 connector → ordersdb", env());
        GraphEditDialogue.Turn asked = GraphEditDialogue.answer(t.next, "ordersdb 被誰用？", env());
        assertFalse(asked.understood, asked.reply);
        assertTrue(asked.next != null && asked.next.refs.isEmpty(), asked.reply);
        GraphEditDialogue.Turn noted = GraphEditDialogue.answer(t.next, "the connector config is PUT by a start script", env());
        assertTrue(noted.understood && noted.next.refs.get(0).startsWith("operator: "), noted.reply);
    }

    @Test
    void aLineTheOperatorPointsAtIsReadAndChecked() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "新增 connector → ordersdb", env());
        GraphEditDialogue.Turn missing = GraphEditDialogue.answer(t.next, "connector/nope.json:3", env());
        assertTrue(missing.reply.contains("找不到"), missing.reply);
        assertNotNull(missing.next);

        GraphEditDialogue.Turn read = GraphEditDialogue.answer(missing.next, "connector/config/orders-source.json:3", env());
        assertTrue(read.reply.contains("算 repo 出處"), read.reply);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, GraphEditDialogue.toEdit(read.next, env()).confidence);

        GraphEditDialogue.Turn t2 = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "新增 connector → ordersdb", env());
        GraphEditDialogue.Turn other = GraphEditDialogue.answer(t2.next, "connector/config/orders-source.json:4", env());
        assertTrue(other.reply.contains("沒有寫到 `ordersdb`"), other.reply);
        assertEquals(DependencyGraph.CONF_INFERRED, GraphEditDialogue.toEdit(other.next, env()).confidence);
    }

    @Test
    void reverseSwapsTheDirectionAndSearchesAgain() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("ordersdb", "connector"), "add ordersdb -> connector", env());
        t = GraphEditDialogue.answer(t.next, "reverse", env());
        assertTrue(t.reply.contains("Reversed: `connector` → `ordersdb`"), t.reply);
        assertTrue(t.reply.contains("connector/config/orders-source.json:3"), t.reply);
    }

    @Test
    void anEdgeAlreadyOnTheGraphIsReportedNotAddedAgain() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("orders", "ordersdb"), "add orders -> ordersdb", env());
        assertNull(t.next);
        assertTrue(t.reply.contains("already has"), t.reply);
    }

    @Test
    void aMissingCallerIsAskedFor() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("", "ordersdb"), "加一條連到 ordersdb 的線", env());
        assertTrue(t.reply.contains("哪個服務呼叫或連到 `ordersdb`"), t.reply);
        t = GraphEditDialogue.answer(t.next, "connector", env());
        assertTrue(t.reply.contains("orders-source.json:3"), t.reply);
    }

    @Test
    void theRepositoryNotBeingReadableIsNotReportedAsNothingFound() {
        GraphEditDialogue.Env blind = new GraphEditDialogue.Env() {
            public DependencyGraph graph() {
                return graph;
            }

            public List<EvidenceFinder.Evidence> search(String s, String t) {
                return null;
            }

            public String readLine(String f, int l) {
                return null;
            }

            public String user() {
                return "tester";
            }

            public String now() {
                return "";
            }
        };
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "新增 connector → ordersdb", blind);
        assertTrue(t.reply.contains("讀不到 repo"), t.reply);
        assertFalse(t.reply.contains("沒有找到"), t.reply);
    }

    @Test
    void pendingStateSurvivesTheArchive() {
        GraphEditDialogue.Turn t = GraphEditDialogue.start(GraphEdit.ADD_EDGE, List.of("connector", "ordersdb"), "新增 connector → ordersdb", env());
        ReportArchive a = new ReportArchive();
        a.pendingEdit = t.next.toJson();
        ReportArchive back = ReportArchive.fromJson(new JSONObject(a.toJson().toString()));
        GraphEditDialogue.Pending p = GraphEditDialogue.Pending.fromJson(back.pendingEdit);
        GraphEditDialogue.Turn next = GraphEditDialogue.answer(p, "1", env());
        assertTrue(next.reply.contains("建議「db」"), next.reply);
        assertNull(ReportArchive.fromJson(new JSONObject(new ReportArchive().toJson().toString())).pendingEdit);
    }

    // ---------- the request, from the planner ----------

    @Test
    void plannerEditOpsKeepTheOperatorsSpelling() {
        List<GraphQuery> q = GraphQuery.parse("[{\"op\":\"add-edge\",\"args\":[\"kafka-connect\",\"Postgres DB\"]}]", graph);
        assertEquals(1, q.size());
        assertTrue(q.get(0).isEdit());
        assertEquals(List.of("kafka-connect", "Postgres DB"), q.get(0).args, "names not on the graph are kept for the dialogue");
        List<GraphQuery> one = GraphQuery.parse("[{\"op\":\"add-edge\",\"args\":[\"ordersdb\"]}]", graph);
        assertEquals(List.of("", "ordersdb"), one.get(0).args, "the caller is asked for");
        assertTrue(GraphQuery.parse("[{\"op\":\"add-node\",\"args\":[]}]", graph).isEmpty());
    }

    @Test
    void editPhrasingVetoesConfidentRouting() {
        SemanticRouter.Embedder bag = texts -> {
            List<double[]> out = new ArrayList<>();
            for (String text : texts) {
                double[] v = new double[2048];
                for (String tok : ChunkRetriever.tokenize(text)) v[Math.floorMod(tok.hashCode(), v.length)] += 1;
                out.add(v);
            }
            return out;
        };
        SemanticRouter router = new SemanticRouter(bag, 0.3, 0.0, 0.9);
        String edit = SemanticRouter.EDIT_EXAMPLES.get(0);
        SemanticRouter.Decision d = router.route(bag.embed(List.of(edit)).get(0), edit, List.of());
        assertTrue(d.editLike, d.toString());
        assertFalse(d.confident, "an edit request goes to the planner");
        String question = "X 會不會呼叫 Y？";
        assertFalse(router.route(bag.embed(List.of(question)).get(0), question, List.of()).editLike);
    }

    // ---------- remembered edits ----------

    @Test
    void rememberedEditsReapplyInOrder() {
        GraphEdit edge = new GraphEdit();
        edge.source = "connector";
        edge.target = "ordersdb";
        edge.type = "db";
        edge.confidence = DependencyGraph.CONF_DOCUMENTED;
        edge.refs.add("connector/config/orders-source.json:3");
        GraphEdit node = new GraphEdit();
        node.action = GraphEdit.ADD_NODE;
        node.target = "zipkin";
        node.kind = DependencyGraph.KIND_SERVICE;
        String json = GraphEdit.listToJson(List.of(edge, node));

        DependencyGraph fresh = new DependencyGraph("");
        assertEquals(2, GraphEdit.applyAll(fresh, json));
        assertNotNull(fresh.findNode("zipkin"));
        DependencyGraph.Edge e = edgeOf(fresh, "connector", "ordersdb");
        assertNotNull(e);
        assertEquals(DependencyGraph.CONF_DOCUMENTED, e.confidence);
        assertTrue(e.provenanceRefs.contains("connector/config/orders-source.json:3"));
        assertEquals(0, GraphEdit.applyAll(fresh, "not json"));
    }

    private static DependencyGraph.Edge edgeOf(DependencyGraph g, String s, String t) {
        for (DependencyGraph.Edge e : g.getEdges()) if (e.source.equals(s) && e.target.equals(t)) return e;
        return null;
    }
}
