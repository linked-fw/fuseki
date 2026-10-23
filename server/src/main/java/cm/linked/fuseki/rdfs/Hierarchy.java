package cm.linked.fuseki.rdfs;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdfs.RDFSFactory;
import org.apache.jena.rdfs.SetupRDFS;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDFS;

/**
 * The class hierarchy the view infers with: the stored {@code rdfs:subClassOf} triples of the
 * default graph and of every graph the graph registry marks as a hierarchy source, and nothing
 * else. {@code rdfs:subPropertyOf}, {@code rdfs:domain} and {@code rdfs:range} are left out on
 * purpose, so the view infers classes only.
 * <p>
 * The registry is the named graph {@link #REGISTRY}; a record lists a graph when it holds
 * {@code ?r core:feedsHierarchy true ; sd:name ?g}. The flag is read as stored (no inference).
 * The IRIs are fixed here, so no dataset configuration names them.
 */
public final class Hierarchy {
    private Hierarchy() {}

    /** The graph registry, the same IRI in every dataset. */
    public static final Node REGISTRY = NodeFactory.createURI("https://linked.cm/pkg/core/graph/registry");
    /** {@code core:feedsHierarchy}: a record whose value is {@code true} lists its graph. */
    public static final Node FEEDS_HIERARCHY = NodeFactory.createURI("https://linked.cm/ont/core/feedsHierarchy");
    /** {@code sd:name}: the graph a record describes. */
    public static final Node SD_NAME = NodeFactory.createURI("http://www.w3.org/ns/sparql-service-description#name");

    /** A hierarchy read: the {@code subClassOf} triples and the listed graphs they came from (the default graph not included). */
    public record Read(Graph vocab, Set<Node> graphs) {}

    /** Reads the hierarchy. The caller is in a transaction on {@code stored}. */
    public static Read read(DatasetGraph stored) {
        Set<Node> graphs = listedGraphs(stored);
        Graph vocab = GraphMemFactory.createDefaultGraph();
        addSubClassOf(stored, Quad.defaultGraphIRI, vocab);
        for ( Node g : graphs )
            addSubClassOf(stored, g, vocab);
        return new Read(vocab, Set.copyOf(graphs));
    }

    /** The graphs the registry marks as hierarchy sources. */
    static Set<Node> listedGraphs(DatasetGraph stored) {
        Set<Node> graphs = new LinkedHashSet<>();
        Iterator<Quad> records = stored.find(REGISTRY, Node.ANY, FEEDS_HIERARCHY, Node.ANY);
        try {
            while ( records.hasNext() ) {
                Quad q = records.next();
                if ( !isTrue(q.getObject()) )
                    continue;
                Iterator<Quad> names = stored.find(REGISTRY, q.getSubject(), SD_NAME, Node.ANY);
                try {
                    while ( names.hasNext() ) {
                        Node g = names.next().getObject();
                        // The default graph always feeds; the union graph would pull in every graph.
                        if ( g.isURI() && !Quad.isDefaultGraph(g) && !Quad.isUnionGraph(g) )
                            graphs.add(g);
                    }
                } finally {
                    Iter.close(names);
                }
            }
        } finally {
            Iter.close(records);
        }
        return graphs;
    }

    // TDB2 inlines xsd:boolean, so the value is compared, not the lexical form ("true" or "1").
    static boolean isTrue(Node o) {
        if ( !o.isLiteral() || !XSDDatatype.XSDboolean.equals(o.getLiteralDatatype()) )
            return false;
        String lex = o.getLiteralLexicalForm().strip();
        return lex.equals("true") || lex.equals("1");
    }

    private static void addSubClassOf(DatasetGraph stored, Node g, Graph vocab) {
        Iterator<Quad> iter = stored.find(g, Node.ANY, RDFS.Nodes.subClassOf, Node.ANY);
        try {
            while ( iter.hasNext() ) {
                Quad q = iter.next();
                Node o = q.getObject();
                if ( o.isURI() || o.isBlank() )
                    vocab.add(Triple.create(q.getSubject(), q.getPredicate(), o));
            }
        } finally {
            Iter.close(iter);
        }
    }

    public static SetupRDFS setup(Graph vocab) {
        return RDFSFactory.setupRDFS(vocab);
    }
}
