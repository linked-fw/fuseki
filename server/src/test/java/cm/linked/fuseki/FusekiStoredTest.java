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

    @Test
    void quadsPostedToTheDatasetUrl() {
        update(base + "/update", "INSERT DATA { :x a :a }");
        String q = "<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .";
        assertEquals(2, send("POST", base, "application/n-quads", null, q).status() / 100);
        assertTrue(ask(base + "/sparql", "ASK { :x a :b }"));
    }
    // Upload (an image-only endpoint: the UI module registers it) is covered by scripts/smoke.sh.
}
