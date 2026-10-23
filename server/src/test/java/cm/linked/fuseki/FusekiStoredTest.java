package cm.linked.fuseki;

import static cm.linked.fuseki.Http.ask;
import static cm.linked.fuseki.Http.enc;
import static cm.linked.fuseki.Http.lines;
import static cm.linked.fuseki.Http.send;
import static cm.linked.fuseki.Http.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cm.linked.fuseki.Http.Resp;
import cm.linked.fuseki.fuseki.TemplateGuard;
import cm.linked.fuseki.rdfs.DatasetGraphRDFSLive;
import cm.linked.fuseki.rdfs.LiveDatasets;

/** An embedded server configured from the bundled template, as the admin form would create it. */
class FusekiStoredTest {
    static FusekiServer server;
    static String base;
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("lf-stored");
        String template = new String(TemplateGuard.bundled())
            .replace("{NAME}", "ds")
            .replace("{FUSEKI_BASE}", dir.toString());
        Graph config = RDFParser.fromString(template, Lang.TTL).base("http://base/").toGraph();
        server = FusekiServer.create().port(0).parseConfig(config).build().start();
        base = "http://localhost:" + server.getHttpPort() + "/ds";
    }

    @AfterAll
    static void stop() {
        if ( server != null )
            server.stop();
    }

    @BeforeEach
    void clean() {
        assertEquals(200, update(base + "/update", "DROP ALL").status() / 100 * 100);
    }

    private void data() {
        Resp r = update(base + "/update", "INSERT DATA { :a rdfs:subClassOf :b . :x a :a . GRAPH :g { :y a :a } }");
        assertEquals(2, r.status() / 100);
    }

    @Test
    void sparqlInfers() {
        data();
        assertTrue(ask(base + "/sparql", "ASK { :x a :b }"));
        assertTrue(ask(base + "/query", "ASK { GRAPH :g { :y a :b } }"));
        assertTrue(ask(base, "ASK { :x a :b }"));
    }

    @Test
    void storedQueryHasNoInferredTriple() {
        data();
        assertFalse(ask(base + "/stored", "ASK { :x a :b }"));
        assertTrue(ask(base + "/stored", "ASK { :x a :a }"));
        // POST application/sparql-query
        Resp r = send("POST", base + "/stored", "application/sparql-query", "application/sparql-results+json",
                      TestLib.PREFIXES + "ASK { GRAPH :g { :y a :b } }");
        assertEquals(200, r.status());
        assertTrue(r.body().contains("false"));
        // POST form query=
        r = send("POST", base + "/stored", "application/x-www-form-urlencoded", "application/sparql-results+json",
                 "query=" + enc(TestLib.PREFIXES + "ASK { :x a :a }"));
        assertEquals(200, r.status());
        assertTrue(r.body().contains("true"));
    }

    @Test
    void storedGspRead() {
        data();
        Resp all = send("GET", base + "/stored", null, "application/n-quads", null);
        assertEquals(200, all.status());
        assertEquals(3, lines(all.body()), all.body());
        Resp dft = send("GET", base + "/stored?default", null, "application/n-triples", null);
        assertEquals(200, dft.status());
        assertEquals(2, lines(dft.body()), dft.body());
        Resp g = send("GET", base + "/stored?graph=" + enc("http://example.org/g"), null, "application/n-triples", null);
        assertEquals(200, g.status());
        assertEquals(1, lines(g.body()), g.body());
    }

    @Test
    void storedRefusesWrites() {
        assertEquals(405, send("POST", base + "/stored?default", "application/n-triples", null,
                               "<http://e/s> <http://e/p> <http://e/o> .").status());
        assertEquals(405, send("PUT", base + "/stored?default", "application/n-triples", null,
                               "<http://e/s> <http://e/p> <http://e/o> .").status());
        assertEquals(405, send("DELETE", base + "/stored?default", null, null, null).status());
    }

    @Test
    void datasetUrlIncludesInferredQuads() {
        data();
        Resp all = send("GET", base, null, "application/n-quads", null);
        assertEquals(200, all.status());
        assertTrue(lines(all.body()) > 3, "GET /<ds> includes inferred quads: " + all.body());
    }

    @Test
    void serviceIsRefused() {
        Resp r = send("GET", base + "/sparql?query=" + enc("SELECT * { SERVICE <http://example.org/sparql> { ?s ?p ?o } }"),
                      null, "application/sparql-results+json", null);
        assertTrue(r.status() >= 400 && r.status() < 500, "SERVICE answered " + r.status());
        System.out.println("SERVICE status: " + r.status());
    }

    // ---- HTTP write paths that change the hierarchy.

    @Test
    void gspPutPostDeleteOnTheDefaultGraph() {
        update(base + "/update", "INSERT DATA { GRAPH :g { :x a :a } }");
        String h = "<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .";
        assertEquals(2, send("PUT", base + "/data?default", "application/n-triples", null, h).status() / 100);
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        assertEquals(2, send("DELETE", base + "/data?default", null, null, null).status() / 100);
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        assertEquals(2, send("POST", base + "/data?default", "application/n-triples", null, h).status() / 100);
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        // PUT replaces: without the axiom the hierarchy is gone.
        assertEquals(2, send("PUT", base + "/data?default", "application/n-triples", null,
                             "<http://example.org/z> <http://example.org/p> \"1\" .").status() / 100);
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
    }

    // ---- GSP writes never rebuild the hierarchy inside their own write transaction.

    static final String AB = "<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .";
    static final String BC = "<http://example.org/b> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/c> .";

    private static DatasetGraphRDFSLive live() {
        return LiveDatasets.find(server.getDataAccessPointRegistry().get("/ds").getDataService().getDataset())
            .orElseThrow(() -> new AssertionError("the service dataset is not live"));
    }

    private static int gsp(String method, String query, String body) {
        return send(method, base + "/data?" + query, body == null ? null : "application/n-triples", null, body).status();
    }

    /** Instances in a named graph and a settled hierarchy; returns the rebuild count. */
    private long settled() {
        update(base + "/update", "INSERT DATA { GRAPH :g { :x a :a . :w a :b } }");
        ask(base + "/sparql", "ASK { ?s ?p ?o }");
        return live().rebuilds();
    }

    @Test
    void gspPostRebuildsAtTheNextReadOnly() {
        long rebuilds = settled();
        long gen = live().generation();
        assertEquals(2, gsp("POST", "default", AB) / 100);
        assertEquals(2, gsp("POST", "default", BC) / 100);
        assertEquals(2, gsp("POST", "graph=" + enc("http://example.org/h"), AB) / 100);
        assertEquals(gen + 2, live().generation());
        assertEquals(rebuilds, live().rebuilds(), "no rebuild inside a GSP POST");
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :c } }"));
        assertEquals(rebuilds + 1, live().rebuilds(), "one rebuild, at the next read");
    }

    @Test
    void gspPutRebuildsAtTheNextReadOnly() {
        long rebuilds = settled();
        assertEquals(2, gsp("PUT", "default", AB) / 100);
        assertEquals(2, gsp("PUT", "default", BC) / 100);
        assertEquals(2, gsp("PUT", "graph=" + enc("http://example.org/h"), AB) / 100);
        assertEquals(rebuilds, live().rebuilds(), "no rebuild inside a GSP PUT");
        // The second PUT replaced the first: b < c only.
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :w a :c } }"));
        assertEquals(rebuilds + 1, live().rebuilds(), "one rebuild, at the next read");
    }

    @Test
    void gspDeleteRebuildsAtTheNextReadOnly() {
        long rebuilds = settled();
        assertEquals(2, gsp("POST", "default", AB) / 100);
        assertEquals(2, gsp("POST", "graph=" + enc("http://example.org/h"), AB) / 100);
        assertEquals(2, gsp("DELETE", "default", null) / 100);
        assertEquals(2, gsp("DELETE", "graph=" + enc("http://example.org/h"), null) / 100);
        assertEquals(404, gsp("DELETE", "graph=" + enc("http://example.org/h"), null));
        assertEquals(rebuilds, live().rebuilds(), "no rebuild inside a GSP DELETE");
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :a } }"));
        assertEquals(rebuilds + 1, live().rebuilds(), "one rebuild, at the next read");
    }

    // ---- Graphs the registry lists.

    static final String REGISTRY = "https://linked.cm/pkg/core/graph/registry";
    static final String ONTO = "http://example.org/onto";
    static final String RECORD = "<" + ONTO + "> <http://www.w3.org/ns/sparql-service-description#name> <" + ONTO + "> .\n"
        + "<" + ONTO + "> <https://linked.cm/ont/core/feedsHierarchy> \"true\"^^<http://www.w3.org/2001/XMLSchema#boolean> .";

    @Test
    void gspOnAListedGraphRebuildsAtTheNextReadOnly() {
        long rebuilds = settled();
        assertEquals(2, gsp("PUT", "graph=" + enc(REGISTRY), RECORD) / 100);
        assertEquals(2, gsp("PUT", "graph=" + enc(ONTO), AB) / 100);
        assertEquals(2, gsp("POST", "graph=" + enc(ONTO), BC) / 100);
        assertEquals(rebuilds, live().rebuilds(), "no rebuild inside a GSP write");
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :c } }"));
        assertEquals(rebuilds + 1, live().rebuilds(), "one rebuild, at the next read");
        assertEquals(1, live().listedGraphs().size());
        // PUT replaces the listed graph: a < b only.
        assertEquals(2, gsp("PUT", "graph=" + enc(ONTO), AB) / 100);
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :w a :c } }"));
        assertTrue(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
        assertEquals(2, gsp("DELETE", "graph=" + enc(ONTO), null) / 100);
        assertFalse(ask(base + "/sparql", "ASK { GRAPH :g { :x a :b } }"));
    }

    @Test
    void aListedGraphFeedsAnUnlistedOneDoesNot() {
        update(base + "/update", "INSERT DATA { :x a :a . GRAPH :draft { :a rdfs:subClassOf :c } }");
        assertEquals(2, gsp("POST", "graph=" + enc(ONTO), AB) / 100);
        assertFalse(ask(base + "/sparql", "ASK { :x a :b }"), "not listed yet");
        assertEquals(2, gsp("POST", "graph=" + enc(REGISTRY), RECORD) / 100);
        assertTrue(ask(base + "/sparql", "ASK { :x a :b }"), "listed by the record");
        assertFalse(ask(base + "/sparql", "ASK { :x a :c }"), "the unlisted graph does not feed");
        // Dropping the record by GSP DELETE of the whole registry graph.
        assertEquals(2, gsp("DELETE", "graph=" + enc(REGISTRY), null) / 100);
        assertFalse(ask(base + "/sparql", "ASK { :x a :b }"));
    }

    @Test
    void quadsPostedToTheDatasetUrl() {
        update(base + "/update", "INSERT DATA { :x a :a }");
        String q = "<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .";
        assertEquals(2, send("POST", base, "application/n-quads", null, q).status() / 100);
        assertTrue(ask(base + "/sparql", "ASK { :x a :b }"));
    }
    // Upload is not tested: Fuseki 6.2 serves no upload handler without its UI module (report 001).
}
