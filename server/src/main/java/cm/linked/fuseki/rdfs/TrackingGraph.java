package cm.linked.fuseki.rdfs;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphWrapper;

/**
 * A graph of the stored dataset whose writes mark the tracker when they may change the
 * hierarchy: any write when it is the registry (or the union graph), a {@code subClassOf} write
 * (or a wildcard remove, a clear) when it feeds the hierarchy. Decided per write, by name.
 */
class TrackingGraph extends GraphWrapper {
    private final Node name;
    private final SubClassOfTracker tracker;

    TrackingGraph(Graph graph, Node name, SubClassOfTracker tracker) {
        super(graph);
        this.name = name;
        this.tracker = tracker;
    }

    @Override
    public void add(Triple t) {
        tracker.check(name, t.getPredicate());
        super.add(t);
    }

    @Override
    public void delete(Triple t) {
        tracker.check(name, t.getPredicate());
        super.delete(t);
    }

    @Override
    public void remove(Node s, Node p, Node o) {
        tracker.check(name, p);
        super.remove(s, p, o);
    }

    @Override
    public void clear() {
        tracker.checkGraph(name);
        super.clear();
    }
}
