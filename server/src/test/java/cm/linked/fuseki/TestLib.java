package cm.linked.fuseki;

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSet;
import org.apache.jena.sparql.exec.UpdateExec;
import org.apache.jena.system.Txn;

/** Small helpers shared by the tests. */
public final class TestLib {
    private TestLib() {}

    public static final String PREFIXES = """
        PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX :     <http://example.org/>
        """;

    public static void update(DatasetGraph dsg, String update) {
        Txn.executeWrite(dsg, () -> UpdateExec.dataset(dsg).update(PREFIXES + update).execute());
    }

    public static boolean ask(DatasetGraph dsg, String query) {
        return Txn.calculateRead(dsg, () -> askInTxn(dsg, query));
    }

    public static boolean askInTxn(DatasetGraph dsg, String query) {
        try (QueryExec qe = QueryExec.dataset(dsg).query(PREFIXES + query).build()) {
            return qe.ask();
        }
    }

    /** {@code SELECT (COUNT(*) AS ?n) ...} */
    public static long count(DatasetGraph dsg, String query) {
        return Txn.calculateRead(dsg, () -> {
            try (QueryExec qe = QueryExec.dataset(dsg).query(PREFIXES + query).build()) {
                RowSet rs = qe.select();
                return Long.parseLong(rs.next().get(Var.alloc("n")).getLiteralLexicalForm());
            }
        });
    }

    /** The number of stored quads (every graph). */
    public static long storedSize(DatasetGraph stored) {
        return Txn.calculateRead(stored, () -> {
            long n = 0;
            var it = stored.find();
            while ( it.hasNext() ) { it.next(); n++; }
            return n;
        });
    }
}
