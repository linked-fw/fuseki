package cm.linked.fuseki;

import static cm.linked.fuseki.Http.ask;
import static cm.linked.fuseki.Http.lines;
import static cm.linked.fuseki.Http.send;
import static cm.linked.fuseki.Http.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiModules;
import org.apache.jena.fuseki.mod.admin.FMod_Admin;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import cm.linked.fuseki.Http.Resp;
import cm.linked.fuseki.fuseki.FMod_LinkedFuseki;
import cm.linked.fuseki.fuseki.TemplateGuard;
import cm.linked.fuseki.rdfs.DatasetGraphRDFSLive;

/**
 * A server with a run area and the admin module, whose stock template is in place before start:
 * the module replaces it, a form-created dataset infers, and a delete removes its files.
 * Runs in its own JVM (Fuseki's run-area paths are static).
 */
class AdminTemplateTest {
    static FusekiServer server;
    static String root;
    static Path base;
    static DatasetGraph staticDsg;

    @BeforeAll
    static void start() throws Exception {
        base = Files.createTempDirectory("lf-admin");
        Files.createDirectories(base.resolve("templates"));
        try (var in = FusekiServer.class.getResourceAsStream("/org/apache/jena/fuseki/server/templates/config-tdb2")) {
            Files.write(base.resolve("templates/config-tdb2"), in.readAllBytes());
        }
        System.setProperty("FUSEKI_BASE", base.toString());
        // An unmanaged dataset (no configuration file of its own) under databases/: kept on delete.
        staticDsg = new DatasetGraphRDFSLive(
            DatabaseMgr.connectDatasetGraph(Location.create(base.resolve("databases/static"))), "static");
        server = FusekiServer.create().port(0)
            .add("/static", staticDsg)
            .fusekiModules(FusekiModules.create(new FMod_LinkedFuseki(), FMod_Admin.create()))
            .build().start();
        root = "http://localhost:" + server.getHttpPort();
    }

    @AfterAll
    static void stop() {
        if ( server != null )
            server.stop();
    }

    static Resp createDataset(String name) {
        return send("POST", root + "/$/datasets", "application/x-www-form-urlencoded", null,
                    "dbName=" + name + "&dbType=tdb2");
    }

    @Test
    void templateIsReplacedAndDatasetsInferAndDeleteRemovesFiles() throws Exception {
        assertTrue(java.util.Arrays.equals(TemplateGuard.bundled(), Files.readAllBytes(base.resolve("templates/config-tdb2"))),
                   "the stock template was replaced by the module's");

        assertEquals(200, createDataset("t1").status());
        String ds = root + "/t1";
        assertEquals(2, update(ds + "/update", "INSERT DATA { :a rdfs:subClassOf :b . :x a :a }").status() / 100);
        assertTrue(ask(ds + "/sparql", "ASK { :x a :b }"));
        assertFalse(ask(ds + "/stored", "ASK { :x a :b }"));
        assertTrue(Files.isDirectory(base.resolve("databases/t1")));

        assertEquals(200, send("DELETE", root + "/$/datasets/t1", null, null, null).status());
        assertFalse(Files.exists(base.resolve("databases/t1")), "the delete removed the database files");

        assertEquals(200, createDataset("t1").status());
        Resp all = send("GET", ds + "/stored", null, "application/n-quads", null);
        assertEquals(200, all.status());
        assertEquals(0, lines(all.body()), "a re-created name starts empty");
    }

    @Test
    void anUnmanagedDatasetKeepsItsFiles() {
        assertEquals(2, update(root + "/static/update", "INSERT DATA { :s :p :o }").status() / 100);
        assertTrue(Files.isDirectory(base.resolve("databases/static")));
        int status = send("DELETE", root + "/$/datasets/static", null, null, null).status();
        assertEquals(200, status);
        assertTrue(Files.isDirectory(base.resolve("databases/static")), "Fuseki keeps an unmanaged dataset's files; so does the module");
    }

    @Test
    void aFailedDeleteKeepsTheFiles() {
        assertEquals(404, send("DELETE", root + "/$/datasets/nosuch", null, null, null).status());
    }
}
