package cm.linked.fuseki.rdfs;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDFS;

/**
 * Passes every call to the stored dataset and notes whether a write may have changed an
 * {@code rdfs:subClassOf} triple of the default graph. Graphs handed out are wrapped too: a graph
 * obtained from TDB2 writes to TDB2 directly and would bypass the dataset-level overrides.
 * <p>
 * The mark is kept per thread (a TDB2 transaction belongs to one thread), so a writer that begins
 * while the previous one is still ending cannot have its mark reset by it. The live dataset reads
 * and resets it at commit, and resets it at abort and end.
 */
public class SubClassOfTracker extends DatasetGraphWrapper {
    private final ThreadLocal<Boolean> touched = new ThreadLocal<>();

    public SubClassOfTracker(DatasetGraph stored) {
        super(stored);
    }

    /** Whether a write since the last call may have changed the hierarchy; resets the mark. */
    public boolean takeTouched() {
        boolean t = Boolean.TRUE.equals(touched.get());
        touched.remove();
        return t;
    }

    /** Whether this thread's write may have changed the hierarchy (the mark is kept). */
    public boolean isTouched() {
        return Boolean.TRUE.equals(touched.get());
    }

    /** Marks the hierarchy as possibly changed. */
    void touch() {
        touched.set(Boolean.TRUE);
    }

    static boolean isDefaultOrAny(Node g) {
        return g == null || Node.ANY.equals(g) || Quad.isDefaultGraph(g) || Quad.isUnionGraph(g);
    }

    static boolean isSubClassOfOrAny(Node p) {
        return p == null || Node.ANY.equals(p) || p.isVariable() || RDFS.Nodes.subClassOf.equals(p);
    }

    private void check(Node g, Node p) {
        if ( isDefaultOrAny(g) && isSubClassOfOrAny(p) )
            touch();
    }

    @Override
    public void add(Quad quad) {
        check(quad.getGraph(), quad.getPredicate());
        super.add(quad);
    }

    @Override
    public void add(Node g, Node s, Node p, Node o) {
        check(g, p);
        super.add(g, s, p, o);
    }

    @Override
    public void delete(Quad quad) {
        check(quad.getGraph(), quad.getPredicate());
        super.delete(quad);
    }

    @Override
    public void delete(Node g, Node s, Node p, Node o) {
        check(g, p);
        super.delete(g, s, p, o);
    }

    @Override
    public void deleteAny(Node g, Node s, Node p, Node o) {
        check(g, p);
        super.deleteAny(g, s, p, o);
    }

    @Override
    public void clear() {
        touch();
        super.clear();
    }

    @Override
    public void addGraph(Node graphName, Graph graph) {
        if ( isDefaultOrAny(graphName) )
            touch();
        super.addGraph(graphName, graph);
    }

    @Override
    public void removeGraph(Node graphName) {
        if ( isDefaultOrAny(graphName) )
            touch();
        super.removeGraph(graphName);
    }

    @Override
    public Graph getDefaultGraph() {
        return new TrackingGraph(super.getDefaultGraph(), this);
    }

    @Override
    public Graph getUnionGraph() {
        // Writes through the union graph are refused by TDB2; tracked conservatively all the same.
        return new TrackingGraph(super.getUnionGraph(), this);
    }

    @Override
    public Graph getGraph(Node graphNode) {
        Graph g = super.getGraph(graphNode);
        if ( g == null )
            return null;
        if ( isDefaultOrAny(graphNode) )
            return new TrackingGraph(g, this);
        return g;
    }
}
