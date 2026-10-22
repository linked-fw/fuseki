package cm.linked.fuseki;

import static cm.linked.fuseki.TestLib.ask;
import static cm.linked.fuseki.TestLib.askInTxn;
import static cm.linked.fuseki.TestLib.count;
import static cm.linked.fuseki.TestLib.storedSize;
import static cm.linked.fuseki.TestLib.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cm.linked.fuseki.rdfs.DatasetGraphRDFSLive;

class DatasetGraphRDFSLiveTest {
    static final String EX = "http://example.org/";

    static Node ex(String local) {
        return NodeFactory.createURI(EX + local);
    }

    DatasetGraph stored;
    DatasetGraphRDFSLive live;

    @BeforeEach
    void setUp() {
        stored = DatabaseMgr.createDatasetGraph();
        live = new DatasetGraphRDFSLive(stored, "test");
    }

    private void hierarchyABC() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b . :b rdfs:subClassOf :c . }");
    }

    @Test
    void infersInDefaultNamedAndUnionGraphs() {
        hierarchyABC();
        update(live, "INSERT DATA { :x a :a . GRAPH :g1 { :y a :a } }");
        assertTrue(ask(live, "ASK { :x a :b }"));
        assertTrue(ask(live, "ASK { :x a :c }"));
        assertTrue(ask(live, "ASK { GRAPH :g1 { :y a :b } }"));
        assertTrue(ask(live, "ASK { GRAPH :g1 { :y a :c } }"));
        assertTrue(ask(live, "ASK { GRAPH <urn:x-arq:UnionGraph> { :y a :c } }"));
        // Inference stays in its graph.
        assertFalse(ask(live, "ASK { :y a :c }"));
    }

    @Test
    void storedHoldsNoInferredTriple() {
        hierarchyABC();
        update(live, "INSERT DATA { :x a :a . GRAPH :g1 { :y a :a } }");
        assertEquals(4, storedSize(stored));
        assertFalse(Txn.calculateRead(stored, () -> stored.contains(Quad.defaultGraphIRI, ex("x"), RDF.Nodes.type, ex("b"))));
        assertFalse(Txn.calculateRead(stored, () -> stored.contains(ex("g1"), ex("y"), RDF.Nodes.type, ex("b"))));
    }

    // ---- Write paths: each changes the hierarchy and the next transaction sees it.

    @Test
    void insertDataAndDeleteData() {
        update(live, "INSERT DATA { :x a :a }");
        assertFalse(ask(live, "ASK { :x a :b }"));
        update(live, "INSERT DATA { :a rdfs:subClassOf :b }");
        assertTrue(ask(live, "ASK { :x a :b }"));
        update(live, "DELETE DATA { :a rdfs:subClassOf :b }");
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void deleteWhereAndInsertWhere() {
        update(live, "INSERT DATA { :x a :a . :a :parent :b }");
        update(live, "INSERT { ?s rdfs:subClassOf ?o } WHERE { ?s :parent ?o }");
        assertTrue(ask(live, "ASK { :x a :b }"));
        update(live, "DELETE WHERE { ?s rdfs:subClassOf ?o }");
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void clearDefault() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b . GRAPH :g { :x a :a } }");
        assertTrue(ask(live, "ASK { GRAPH :g { :x a :b } }"));
        update(live, "CLEAR DEFAULT");
        assertFalse(ask(live, "ASK { GRAPH :g { :x a :b } }"));
        assertTrue(ask(live, "ASK { GRAPH :g { :x a :a } }"));
    }

    @Test
    void clearAll() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b . :x a :a }");
        update(live, "CLEAR ALL");
        update(live, "INSERT DATA { :x a :a }");
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void dropAll() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b }");
        update(live, "DROP ALL");
        update(live, "INSERT DATA { :x a :a }");
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void load(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("h.ttl");
        Files.writeString(file, "<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .\n");
        update(live, "INSERT DATA { :x a :a }");
        update(live, "LOAD <" + file.toUri() + ">");
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void graphLevelWrites() {
        update(live, "INSERT DATA { :x a :a }");
        // As GSP PUT/POST/DELETE on ?default do: through the dataset's default graph.
        Txn.executeWrite(live, () -> live.getDefaultGraph().add(Triple.create(ex("a"), RDFS.Nodes.subClassOf, ex("b"))));
        assertTrue(ask(live, "ASK { :x a :b }"));
        Txn.executeWrite(live, () -> live.getDefaultGraph().delete(Triple.create(ex("a"), RDFS.Nodes.subClassOf, ex("b"))));
        assertFalse(ask(live, "ASK { :x a :b }"));
        Txn.executeWrite(live, () -> live.getDefaultGraph().add(Triple.create(ex("a"), RDFS.Nodes.subClassOf, ex("b"))));
        assertTrue(ask(live, "ASK { :x a :b }"));
        Txn.executeWrite(live, () -> live.getDefaultGraph().clear());
        Txn.executeWrite(live, () -> live.getDefaultGraph().add(Triple.create(ex("x"), RDF.Nodes.type, ex("a"))));
        assertFalse(ask(live, "ASK { :x a :b }"));
        Txn.executeWrite(live, () -> live.getDefaultGraph().add(Triple.create(ex("a"), RDFS.Nodes.subClassOf, ex("b"))));
        Txn.executeWrite(live, () -> live.getDefaultGraph().remove(Node.ANY, RDFS.Nodes.subClassOf, Node.ANY));
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void quadsLoadedIntoTheDataset() {
        update(live, "INSERT DATA { :x a :a }");
        Txn.executeWrite(live, () -> RDFDataMgr.read(live,
            new java.io.StringReader("<http://example.org/a> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/b> .\n"),
            null, org.apache.jena.riot.Lang.NQUADS));
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void addGraphOnTheDefaultGraph() {
        update(live, "INSERT DATA { :x a :a }");
        Graph g = org.apache.jena.graph.GraphMemFactory.createDefaultGraph();
        g.add(Triple.create(ex("a"), RDFS.Nodes.subClassOf, ex("b")));
        Txn.executeWrite(live, () -> live.addGraph(Quad.defaultGraphIRI, g));
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    // ---- No rebuild for commits that leave the hierarchy alone.

    @Test
    void otherCommitsDoNotRebuild() {
        hierarchyABC();
        ask(live, "ASK { ?s ?p ?o }");
        long rebuilds = live.rebuilds();
        long gen = live.generation();
        update(live, "INSERT DATA { :x a :a . :x :label 'x' }");
        update(live, "INSERT DATA { GRAPH :g { :d rdfs:subClassOf :e } }");
        update(live, "DELETE DATA { :x :label 'x' }");
        update(live, "CLEAR GRAPH :g");
        ask(live, "ASK { :x a :c }");
        assertEquals(gen, live.generation());
        assertEquals(rebuilds, live.rebuilds());
        // A subClassOf triple in a named graph is not part of the hierarchy.
        update(live, "INSERT DATA { GRAPH :g { :d rdfs:subClassOf :e . :z a :d } }");
        assertFalse(ask(live, "ASK { GRAPH :g { :z a :e } }"));
    }

    @Test
    void abortedWriteLeavesTheHierarchy() {
        update(live, "INSERT DATA { :x a :a }");
        long gen = live.generation();
        live.begin(ReadWrite.WRITE);
        live.add(Quad.defaultGraphIRI, ex("a"), RDFS.Nodes.subClassOf, ex("b"));
        live.abort();
        live.end();
        assertEquals(gen, live.generation());
        assertFalse(ask(live, "ASK { :x a :b }"));
        // end() without commit is an abort too.
        live.begin(ReadWrite.WRITE);
        live.add(Quad.defaultGraphIRI, ex("a"), RDFS.Nodes.subClassOf, ex("b"));
        try { live.end(); } catch (RuntimeException expected) { /* TDB2 may complain about end without commit */ }
        assertEquals(gen, live.generation());
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void aBurstOfCommitsCostsOneRebuild() {
        long rebuilds = live.rebuilds();
        for ( int i = 1; i <= 800; i++ )
            update(live, "INSERT DATA { :C" + i + " rdfs:subClassOf :Root . :i" + i + " a :C" + i + " }");
        assertEquals(rebuilds, live.rebuilds(), "no rebuild while only writes run");
        assertEquals(800, count(live, "SELECT (COUNT(*) AS ?n) { ?i a :Root }"));
        assertEquals(rebuilds + 1, live.rebuilds(), "exactly one rebuild for the burst");
    }

    @Test
    void concurrentBeginnersCauseOneRebuild() throws Exception {
        update(live, "INSERT DATA { :x a :a }");
        ask(live, "ASK { ?s ?p ?o }");
        update(live, "INSERT DATA { :a rdfs:subClassOf :b }");
        long rebuilds = live.rebuilds();
        int n = 8;
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean allSaw = new AtomicBoolean(true);
        for ( int i = 0; i < n; i++ ) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    if ( !ask(live, "ASK { :x a :b }") )
                        allSaw.set(false);
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            threads.add(t);
            t.start();
        }
        for ( Thread t : threads )
            t.join(20_000);
        if ( failure.get() != null )
            throw new AssertionError(failure.get());
        assertTrue(allSaw.get());
        assertEquals(rebuilds + 1, live.rebuilds());
    }

    @Test
    void aReaderKeepsItsHierarchyUntilItEnds() throws Exception {
        update(live, "INSERT DATA { :x a :a }");
        CountDownLatch begun = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        AtomicReference<Boolean> during = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                live.begin(TxnType.READ);
                try {
                    begun.countDown();
                    committed.await(10, TimeUnit.SECONDS);
                    during.set(askInTxn(live, "ASK { :x a :b }"));
                } finally {
                    live.end();
                }
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        reader.start();
        begun.await(10, TimeUnit.SECONDS);
        update(live, "INSERT DATA { :a rdfs:subClassOf :b }");
        committed.countDown();
        reader.join(20_000);
        if ( failure.get() != null )
            throw new AssertionError(failure.get());
        assertFalse(during.get(), "a reader that began before the commit keeps the old hierarchy");
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void aWriterSeesItsChangeFromItsNextTransaction() {
        update(live, "INSERT DATA { :x a :a }");
        Txn.executeWrite(live, () -> {
            live.add(Quad.defaultGraphIRI, ex("a"), RDFS.Nodes.subClassOf, ex("b"));
            assertFalse(askInTxn(live, "ASK { :x a :b }"), "same transaction: the hierarchy it began with");
        });
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void aCycleTerminates() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b . :b rdfs:subClassOf :a . :x a :a }");
        assertTrue(ask(live, "ASK { :x a :b }"));
        assertEquals(2, count(live, "SELECT (COUNT(DISTINCT ?c) AS ?n) { :x a ?c }"));
    }

    @Test
    void classesOnly() {
        update(live, """
            INSERT DATA {
              :p rdfs:subPropertyOf :q .
              :p rdfs:domain :D .
              :p rdfs:range :R .
              :x :p :y .
            }""");
        assertFalse(ask(live, "ASK { :x :q :y }"));
        assertFalse(ask(live, "ASK { :x a :D }"));
        assertFalse(ask(live, "ASK { :y a :R }"));
    }

    @Test
    void deletingAnInferredTripleIsANoOp() {
        hierarchyABC();
        update(live, "INSERT DATA { :x a :a }");
        long size = storedSize(stored);
        update(live, "DELETE DATA { :x a :b }");
        assertEquals(size, storedSize(stored));
        assertTrue(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void aWriterThatWaitedForTheWriterLockSeesTheCommittedHierarchy() throws Exception {
        update(live, "INSERT DATA { :x a :a }");
        ask(live, "ASK { ?s ?p ?o }");
        live.begin(ReadWrite.WRITE);
        live.add(Quad.defaultGraphIRI, ex("a"), RDFS.Nodes.subClassOf, ex("b"));
        AtomicReference<Boolean> seen = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread w2 = new Thread(() -> {
            try {
                started.countDown();
                Txn.executeWrite(live, () -> seen.set(askInTxn(live, "ASK { :x a :b }")));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        w2.start();
        started.await(10, TimeUnit.SECONDS);
        Thread.sleep(200);   // w2 is blocked in begin(WRITE)
        live.commit();
        live.end();
        w2.join(20_000);
        if ( failure.get() != null )
            throw new AssertionError(failure.get());
        assertTrue(seen.get(), "a writer that began after the commit reads with the new hierarchy");
        // A delete that relies on the new axiom.
        update(live, "INSERT DATA { :x :tag 't' }");
        live.begin(ReadWrite.WRITE);
        live.add(Quad.defaultGraphIRI, ex("a"), RDFS.Nodes.subClassOf, ex("c"));
        Thread w3 = new Thread(() -> update(live, "DELETE { ?s :tag ?t } WHERE { ?s a :c ; :tag ?t }"));
        w3.start();
        Thread.sleep(200);
        live.commit();
        live.end();
        w3.join(20_000);
        assertFalse(ask(live, "ASK { :x :tag ?t }"), "the blocked update matched through the new axiom");
    }

    @Test
    void promotionSeesTheHierarchyOfItsSnapshot() {
        update(live, "INSERT DATA { :x a :a }");
        ask(live, "ASK { ?s ?p ?o }");
        live.begin(TxnType.READ_COMMITTED_PROMOTE);
        try {
            assertFalse(askInTxn(live, "ASK { :x a :b }"));
            Thread t = new Thread(() -> update(live, "INSERT DATA { :a rdfs:subClassOf :b }"));
            t.start();
            try { t.join(20_000); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
            assertTrue(live.promote());
            assertTrue(askInTxn(live, "ASK { :x a :b }"), "read-committed promotion moved to the new data and hierarchy");
            live.commit();
        } finally {
            live.end();
        }
    }

    @Test
    void wholeDatasetClearCopyMoveAdd() {
        update(live, "INSERT DATA { :x a :a . :a rdfs:subClassOf :b }");
        Txn.executeWrite(live, () -> live.clear());   // GSP PUT of the whole dataset clears first
        update(live, "INSERT DATA { :x a :a }");
        assertFalse(ask(live, "ASK { :x a :b }"));
        update(live, "INSERT DATA { GRAPH :h { :a rdfs:subClassOf :b } }");
        update(live, "ADD :h TO DEFAULT");
        assertTrue(ask(live, "ASK { :x a :b }"));
        update(live, "DELETE DATA { :a rdfs:subClassOf :b }");
        update(live, "COPY :h TO DEFAULT");
        update(live, "INSERT DATA { :x a :a }");
        assertTrue(ask(live, "ASK { :x a :b }"));
        update(live, "INSERT DATA { GRAPH :h2 { :y :p :z } }");
        update(live, "MOVE :h2 TO DEFAULT");   // replaces the default graph, axiom included
        update(live, "INSERT DATA { :x a :a }");
        assertFalse(ask(live, "ASK { :x a :b }"));
    }

    @Test
    void addCopyMoveReadTheSourceThroughTheView() {
        update(live, "INSERT DATA { :a rdfs:subClassOf :b . GRAPH :h { :y a :a } }");
        update(live, "ADD :h TO DEFAULT");
        assertTrue(ask(stored, "ASK { :y a :b }"), "ADD stores the source's inferred triples");
        update(live, "DELETE DATA { :y a :b }");
        update(live, "COPY :h TO :h2");
        assertTrue(ask(stored, "ASK { GRAPH :h2 { :y a :b } }"), "COPY stores them too");
    }
}
