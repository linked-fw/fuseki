package cm.linked.fuseki;

import static cm.linked.fuseki.TestLib.ask;
import static cm.linked.fuseki.TestLib.storedSize;
import static cm.linked.fuseki.TestLib.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.apache.jena.tdb2.store.DatasetGraphSwitchable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cm.linked.fuseki.rdfs.DatasetGraphRDFSLive;
import cm.linked.fuseki.rdfs.LiveDatasets;

class AssemblerTest {
    static final String PREFIXES = """
        PREFIX :     <#>
        PREFIX ja:   <http://jena.hpl.hp.com/2005/11/Assembler#>
        PREFIX tdb2: <http://jena.apache.org/2016/tdb#>
        PREFIX text: <http://jena.apache.org/text#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX lf:   <https://linked.cm/ont/fuseki#>
        """;

    @BeforeAll
    static void init() {
        JenaSystem.init();
    }

    static DatasetGraph assemble(String ttl, String root) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString(PREFIXES + ttl, Lang.TTL).base("http://test/").parse(m);
        Resource r = m.createResource("http://test/#" + root);
        Dataset ds = (Dataset) Assembler.general().open(r);
        return ds.asDatasetGraph();
    }

    static String live(Path dir) {
        return """
            :ds  a lf:DatasetRDFSLive ; ja:dataset :tdb .
            :tdb a tdb2:DatasetTDB2 ; tdb2:location "%s" .
            """.formatted(dir.toString());
    }

    @Test
    void assemblesAndPersists(@TempDir Path dir) {
        DatasetGraph dsg = assemble(live(dir), "ds");
        assertTrue(dsg instanceof DatasetGraphRDFSLive);
        update(dsg, "INSERT DATA { :a rdfs:subClassOf :b . GRAPH :g { :x a :a } }");
        assertTrue(ask(dsg, "ASK { GRAPH :g { :x a :b } }"));
        DatasetGraph stored = ((DatasetGraphRDFSLive) dsg).stored();
        dsg.close();
        TDBInternal.expel(stored);

        DatasetGraph again = assemble(live(dir), "ds");
        assertEquals(2, storedSize(((DatasetGraphRDFSLive) again).stored()));
        assertTrue(ask(again, "ASK { GRAPH :g { :x a :a } }"), "the named graph's data persisted");
        assertTrue(ask(again, "ASK { GRAPH :g { :x a :b } }"), "and its inference");
        again.close();
    }

    @Test
    void missingBaseDataset() {
        assertThrows(AssemblerException.class, () -> assemble(":ds a lf:DatasetRDFSLive .", "ds"));
    }

    @Test
    void textDatasetOverTheLiveType(@TempDir Path dir) {
        String ttl = live(dir.resolve("tdb")) + """
            :text a text:TextDataset ; text:dataset :ds ; text:index :lucene .
            :lucene a text:TextIndexLucene ;
                text:directory <%s> ;
                text:entityMap :map .
            :map a text:EntityMap ;
                text:entityField "uri" ;
                text:defaultField "text" ;
                text:map ( [ text:field "text" ; text:predicate rdfs:label ] ) .
            """.formatted(dir.resolve("lucene").toUri());
        DatasetGraph dsg = assemble(ttl, "text");
        assertTrue(LiveDatasets.find(dsg).isPresent());
        DatasetGraph stored = LiveDatasets.stored(dsg);
        assertTrue(stored instanceof DatasetGraphSwitchable);
        assertSame(LiveDatasets.find(dsg).get().stored(), stored);
        update(dsg, "INSERT DATA { :a rdfs:subClassOf :b . :x a :a ; rdfs:label 'findable thing' }");
        assertTrue(ask(dsg, "ASK { :x a :b }"));
        assertTrue(ask(dsg, """
            PREFIX text: <http://jena.apache.org/text#>
            ASK { ?s text:query 'findable' . ?s a :b }"""));
        assertFalse(ask(stored, "ASK { :x a :b }"));
        dsg.close();
    }
}
