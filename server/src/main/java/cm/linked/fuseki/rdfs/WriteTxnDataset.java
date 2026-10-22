package cm.linked.fuseki.rdfs;

import java.util.Iterator;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphWrapper;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * What a write transaction reads and takes graphs from before it has resolved its view: the
 * tracker, except that quad and triple matching ({@code find}, {@code contains} and the triple
 * {@code stream} of a graph) go to the inferred view, resolved only then. Graph handles write to the tracker's
 * graphs, so a GSP POST, PUT or DELETE (which takes a graph, tests it for emptiness, clears it and
 * adds to it) never resolves the view inside its write transaction.
 * <p>
 * Emptiness, sizes, graph names and {@code containsGraph} are answered by the stored data, as the
 * view answers them too (Jena's RDFS graph takes them from its base graph): the view infers from
 * stored triples only and adds no axioms, and holds the same graphs. Quad streams of the dataset
 * go where the view sends them (Jena's view does not infer on them; queries read with
 * {@code find}).
 */
final class WriteTxnDataset extends DatasetGraphWrapper {
    private final Supplier<DatasetGraph> view;
    private final DatasetGraph live;

    /**
     * @param tracker the tracker over the stored dataset (writes, emptiness, graph names)
     * @param live    the live dataset (transactions)
     * @param view    this thread's view, resolved when called
     */
    WriteTxnDataset(DatasetGraph tracker, DatasetGraph live, Supplier<DatasetGraph> view) {
        super(tracker, live.getContext());
        this.live = live;
        this.view = view;
    }

    // Transactions stay with the live dataset, which keeps the hierarchy counters.
    @Override
    protected DatasetGraph getT() {
        return live;
    }

    @Override
    public Graph getDefaultGraph() {
        return new LazyGraph(super.getDefaultGraph(), () -> view.get().getDefaultGraph());
    }

    @Override
    public Graph getUnionGraph() {
        return new LazyGraph(super.getUnionGraph(), () -> view.get().getUnionGraph());
    }

    @Override
    public Graph getGraph(Node graphNode) {
        Graph g = super.getGraph(graphNode);
        if ( g == null )
            return null;
        return new LazyGraph(g, () -> view.get().getGraph(graphNode));
    }

    @Override
    public Iterator<Quad> find() {
        return view.get().find();
    }

    @Override
    public Iterator<Quad> find(Quad quad) {
        return view.get().find(quad);
    }

    @Override
    public Iterator<Quad> find(Node g, Node s, Node p, Node o) {
        return view.get().find(g, s, p, o);
    }

    @Override
    public Iterator<Quad> findNG(Node g, Node s, Node p, Node o) {
        return view.get().findNG(g, s, p, o);
    }

    @Override
    public boolean contains(Quad quad) {
        return view.get().contains(quad);
    }

    @Override
    public boolean contains(Node g, Node s, Node p, Node o) {
        return view.get().contains(g, s, p, o);
    }

    /** Writes, prefixes, emptiness and size on the tracker's graph; matching on the view's, resolved per call. */
    private static final class LazyGraph extends GraphWrapper {
        private final Supplier<Graph> view;

        LazyGraph(Graph tracked, Supplier<Graph> view) {
            super(tracked);
            this.view = view;
        }

        @Override
        public ExtendedIterator<Triple> find(Triple triple) {
            return view.get().find(triple);
        }

        @Override
        public ExtendedIterator<Triple> find(Node s, Node p, Node o) {
            return view.get().find(s, p, o);
        }

        @Override
        public Stream<Triple> stream(Node s, Node p, Node o) {
            return view.get().stream(s, p, o);
        }

        @Override
        public boolean contains(Node s, Node p, Node o) {
            return view.get().contains(s, p, o);
        }

        @Override
        public boolean contains(Triple t) {
            return view.get().contains(t);
        }
    }
}
