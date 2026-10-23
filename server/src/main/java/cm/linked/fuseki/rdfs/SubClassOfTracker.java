package cm.linked.fuseki.rdfs;

import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDFS;

/**
 * Passes every call to the stored dataset and notes whether a write may have changed the
 * hierarchy: any write to the graph registry ({@link Hierarchy#REGISTRY}), or a write of an
 * {@code rdfs:subClassOf} triple (or a wildcard delete, a clear, a graph added or removed) on the
 * default graph or on a graph the last published hierarchy build listed. Graphs handed out are
 * wrapped too: a graph obtained from TDB2 writes to TDB2 directly and would bypass the
 * dataset-level overrides.
 * <p>
 * The set of listed graphs is the one of the last published build. While a commit that changed
 * the registry is newer than that build, every named graph counts: the record that commit wrote
 * may list a graph the set does not have yet, and a later write-only commit into that graph must
 * still mark (a reader may meanwhile publish a set that lists the graph from a snapshot without
 * that write).
 * <p>
 * The marks are kept per thread (a TDB2 transaction belongs to one thread), so a writer that
 * begins while the previous one is still ending cannot have its marks reset by it. The live
 * dataset reads and resets them at commit, and resets them at abort and end.
 */
public class SubClassOfTracker extends DatasetGraphWrapper {
    /** The hierarchy may have changed. */
    static final int HIERARCHY = 1;
    /** The registry was written (implies {@link #HIERARCHY}). */
    static final int REGISTRY = 2;

    /** The graphs a published build listed, and the generation that build reflects. */
    private record Listed(Set<Node> graphs, long generation) {}

    private final ThreadLocal<Integer> marks = new ThreadLocal<>();
    private volatile Listed listed = new Listed(Set.of(), 0);
    /** The number of the latest commit that changed the registry (from the live dataset's pending counter). */
    private volatile long registryCommit = 0;

    public SubClassOfTracker(DatasetGraph stored) {
        super(stored);
    }

    /** This thread's marks since the last call ({@link #HIERARCHY}, {@link #REGISTRY}); resets them. */
    int takeMarks() {
        Integer m = marks.get();
        marks.remove();
        return m == null ? 0 : m;
    }

    /** Whether a write since the last call may have changed the hierarchy; resets the marks. */
    public boolean takeTouched() {
        return takeMarks() != 0;
    }

    /** Whether this thread's write may have changed the hierarchy (the marks are kept). */
    public boolean isTouched() {
        Integer m = marks.get();
        return m != null && m != 0;
    }

    private void mark(int m) {
        Integer cur = marks.get();
        marks.set((cur == null ? 0 : cur) | m | HIERARCHY);
    }

    /** Marks the hierarchy as possibly changed. */
    void touch() {
        mark(HIERARCHY);
    }

    /** A build was published: its listed graphs, and the generation it reflects. */
    void published(Set<Node> graphs, long generation) {
        listed = new Listed(graphs, generation);
    }

    /** A commit that changed the registry is starting, numbered {@code commit}. Called under TDB2's writer lock. */
    void registryCommitted(long commit) {
        if ( commit > registryCommit )
            registryCommit = commit;
    }

    /** The graphs the last published build listed. */
    Set<Node> listedGraphs() {
        return listed.graphs();
    }

    static boolean isAny(Node g) {
        return g == null || Node.ANY.equals(g) || g.isVariable();
    }

    /** A wildcard, the union graph (it includes the registry) or the registry itself. */
    static boolean isRegistryOrAny(Node g) {
        return isAny(g) || Quad.isUnionGraph(g) || Hierarchy.REGISTRY.equals(g);
    }

    /** Whether a {@code subClassOf} triple in {@code g} may be part of the hierarchy. */
    boolean isHierarchyGraph(Node g) {
        if ( isAny(g) || Quad.isDefaultGraph(g) || Quad.isUnionGraph(g) )
            return true;
        Listed l = listed;
        if ( l.graphs().contains(g) )
            return true;
        // A registry change not yet in the set may list any named graph.
        return registryCommit > l.generation();
    }

    static boolean isSubClassOfOrAny(Node p) {
        return p == null || Node.ANY.equals(p) || p.isVariable() || RDFS.Nodes.subClassOf.equals(p);
    }

    /** Marks when a write of predicate {@code p} (or a wildcard) to graph {@code g} may change the hierarchy. */
    void check(Node g, Node p) {
        if ( isRegistryOrAny(g) )
            mark(REGISTRY);
        else if ( isSubClassOfOrAny(p) && isHierarchyGraph(g) )
            touch();
    }

    /** Marks when clearing, adding or removing graph {@code g} may change the hierarchy. */
    void checkGraph(Node g) {
        check(g, Node.ANY);
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
        mark(REGISTRY);
        super.clear();
    }

    @Override
    public void addGraph(Node graphName, Graph graph) {
        checkGraph(graphName);
        super.addGraph(graphName, graph);
    }

    @Override
    public void removeGraph(Node graphName) {
        checkGraph(graphName);
        super.removeGraph(graphName);
    }

    @Override
    public Graph getDefaultGraph() {
        return new TrackingGraph(super.getDefaultGraph(), Quad.defaultGraphIRI, this);
    }

    @Override
    public Graph getUnionGraph() {
        // Writes through the union graph are refused by TDB2; tracked conservatively all the same.
        return new TrackingGraph(super.getUnionGraph(), Quad.unionGraph, this);
    }

    // Every graph is wrapped: whether it feeds the hierarchy is decided per write, so a graph
    // listed by a record written in the same transaction (or since) is tracked too.
    @Override
    public Graph getGraph(Node graphNode) {
        Graph g = super.getGraph(graphNode);
        if ( g == null )
            return null;
        return new TrackingGraph(g, graphNode, this);
    }
}
