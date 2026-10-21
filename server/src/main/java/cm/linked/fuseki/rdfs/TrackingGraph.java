package cm.linked.fuseki.rdfs;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.GraphWrapper;

/** A default (or union) graph whose writes mark the tracker when they may touch {@code rdfs:subClassOf}. */
class TrackingGraph extends GraphWrapper {
    private final SubClassOfTracker tracker;

    TrackingGraph(Graph graph, SubClassOfTracker tracker) {
        super(graph);
        this.tracker = tracker;
    }

    private void check(Node p) {
        if ( SubClassOfTracker.isSubClassOfOrAny(p) )
            tracker.touch();
    }

    @Override
    public void add(Triple t) {
        check(t.getPredicate());
        super.add(t);
    }

    @Override
    public void delete(Triple t) {
        check(t.getPredicate());
        super.delete(t);
    }

    @Override
    public void remove(Node s, Node p, Node o) {
        check(p);
        super.remove(s, p, o);
    }

    @Override
    public void clear() {
        tracker.touch();
        super.clear();
    }
}
