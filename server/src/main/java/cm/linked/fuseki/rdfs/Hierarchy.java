package cm.linked.fuseki.rdfs;

import java.util.Iterator;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.rdfs.RDFSFactory;
import org.apache.jena.rdfs.SetupRDFS;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDFS;

/**
 * The class hierarchy the view infers with: the stored {@code rdfs:subClassOf} triples of the
 * default graph, and nothing else. {@code rdfs:subPropertyOf}, {@code rdfs:domain} and
 * {@code rdfs:range} are left out on purpose, so the view infers classes only.
 */
public final class Hierarchy {
    private Hierarchy() {}

    /** Reads the hierarchy. The caller is in a transaction on {@code stored}. */
    public static Graph read(DatasetGraph stored) {
        Graph vocab = GraphMemFactory.createDefaultGraph();
        Iterator<Quad> iter = stored.find(Quad.defaultGraphIRI, Node.ANY, RDFS.Nodes.subClassOf, Node.ANY);
        try {
            while ( iter.hasNext() ) {
                Quad q = iter.next();
                Node o = q.getObject();
                if ( o.isURI() || o.isBlank() )
                    vocab.add(Triple.create(q.getSubject(), q.getPredicate(), o));
            }
        } finally {
            org.apache.jena.atlas.iterator.Iter.close(iter);
        }
        return vocab;
    }

    public static SetupRDFS setup(Graph vocab) {
        return RDFSFactory.setupRDFS(vocab);
    }
}
