package cm.linked.fuseki.rdfs;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdfs.DatasetGraphRDFS;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.rdfs.SetupRDFS;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.DatasetGraphWrapperView;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.system.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jena's query-time RDFS view ({@link DatasetGraphRDFS}) over a stored dataset (normally TDB2),
 * whose class hierarchy follows the stored {@code rdfs:subClassOf} triples of the default graph.
 * <p>
 * Reads infer over every graph; writes pass to the stored dataset unchanged (a delete of an
 * inferred triple reaches the store, which does not hold it: a no-op). Nothing is materialized.
 * <p>
 * A commit that wrote or deleted a default-graph {@code rdfs:subClassOf} triple only bumps the
 * generation. The first transaction that reads afterwards rebuilds the view once, inside itself
 * (so the rebuild reads exactly what that transaction sees); others reading meanwhile wait for
 * it. Writes do not need the view, so a burst of write-only commits costs one rebuild, at the
 * next read. A transaction keeps the view it first read with until it ends; a writer sees its own
 * {@code subClassOf} changes from its next transaction on.
 */
public class DatasetGraphRDFSLive extends DatasetGraphWrapper implements DatasetGraphWrapperView {
    private static final Logger LOG = LoggerFactory.getLogger("cm.linked.fuseki");

    /** A view and the generation of the stored hierarchy it reflects (at least). */
    private record View(DatasetGraphRDFS dsg, long generation, long subClassOf) {}

    /** Per-thread transaction state: the generation read before begin, and the view once resolved. */
    private static final class TxnState {
        final long generation;
        View view;
        TxnState(long generation) { this.generation = generation; }
    }

    private final DatasetGraph stored;
    private final SubClassOfTracker tracker;
    private final String label;
    private final AtomicLong generation = new AtomicLong(0);
    private final ReentrantLock rebuildLock = new ReentrantLock();
    private final ThreadLocal<TxnState> txn = new ThreadLocal<>();
    private volatile View current;

    private final AtomicLong rebuilds = new AtomicLong(0);
    private volatile long lastRebuildMillis = 0;

    public DatasetGraphRDFSLive(DatasetGraph stored, String label) {
        this(stored, label, stored.getContext());
    }

    public DatasetGraphRDFSLive(DatasetGraph stored, String label, Context context) {
        super(stored, context);
        this.stored = stored;
        this.tracker = new SubClassOfTracker(stored);
        this.label = label;
        this.current = Txn.calculateRead(stored, () -> build(0));
        LOG.info("[linked-fuseki] {}: hierarchy built ({} subClassOf, {} ms)", label, current.subClassOf(), lastRebuildMillis);
    }

    /** The stored dataset (no inference). */
    public DatasetGraph stored() {
        return stored;
    }

    /** The number of rebuilds since construction (the initial build excluded). */
    public long rebuilds() {
        return rebuilds.get();
    }

    public long lastRebuildMillis() {
        return lastRebuildMillis;
    }

    /** The generation of the stored hierarchy: bumped by each commit that touched it. */
    public long generation() {
        return generation.get();
    }

    /** The generation the latest view was built for. */
    public long viewGeneration() {
        return current.generation();
    }

    public String label() {
        return label;
    }

    // Reads (and graph handles, which may read) go through the view of this thread's transaction.
    @Override
    protected DatasetGraph get() {
        return view().dsg();
    }

    // Writes go to the tracker directly: they need no inference, and must not cause a rebuild.
    @Override
    protected DatasetGraph getW() {
        return tracker;
    }

    // Transactions go to the stored dataset; begin/commit/abort/end are overridden below.
    @Override
    protected DatasetGraph getT() {
        return stored;
    }

    private View view() {
        TxnState state = txn.get();
        if ( state == null )
            // Outside a transaction (configuration, toString): the latest view.
            return current;
        if ( state.view == null )
            state.view = resolve(state.generation);
        return state.view;
    }

    /**
     * The view for a transaction that began after reading generation {@code gen}: every commit
     * counted in {@code gen} finished before the transaction began, so the transaction sees them
     * all and a view built inside it may carry that label.
     */
    private View resolve(long gen) {
        View v = current;
        if ( v.generation() >= gen )
            return v;
        // A write transaction that already changed the hierarchy would build from its own
        // uncommitted triples: such a view is used by it alone and never published.
        if ( tracker.isTouched() )
            return build(v.generation());
        rebuildLock.lock();
        try {
            v = current;
            if ( v.generation() < gen ) {
                v = build(gen);
                current = v;
                rebuilds.incrementAndGet();
                LOG.info("[linked-fuseki] {}: hierarchy rebuilt ({} subClassOf, {} ms, generation {})",
                         label, v.subClassOf(), lastRebuildMillis, gen);
            }
            return v;
        } finally {
            rebuildLock.unlock();
        }
    }

    private View build(long gen) {
        long start = System.nanoTime();
        Graph vocab = Hierarchy.read(stored);
        SetupRDFS setup = Hierarchy.setup(vocab);
        View view = new View(new DatasetGraphRDFS(tracker, setup, getContext()), gen, vocab.size());
        lastRebuildMillis = (System.nanoTime() - start) / 1_000_000;
        return view;
    }

    @Override
    public boolean isInTransaction() {
        return stored.isInTransaction();
    }

    // Prefixes are not inferred.
    @Override
    public PrefixMap prefixes() {
        return stored.prefixes();
    }

    @Override
    public void begin() {
        long gen = generation.get();
        stored.begin();
        txn.set(new TxnState(gen));
    }

    @Override
    public void begin(TxnType type) {
        long gen = generation.get();
        stored.begin(type);
        txn.set(new TxnState(gen));
    }

    @Override
    public void begin(ReadWrite readWrite) {
        long gen = generation.get();
        stored.begin(readWrite);
        txn.set(new TxnState(gen));
    }

    @Override
    public void commit() {
        boolean touched = tracker.takeTouched();
        stored.commit();
        if ( touched )
            generation.incrementAndGet();
    }

    @Override
    public void abort() {
        tracker.takeTouched();
        stored.abort();
    }

    @Override
    public void end() {
        try {
            // end() without commit is an abort: drop any mark.
            tracker.takeTouched();
            stored.end();
        } finally {
            txn.remove();
        }
    }

    @Override
    public String toString() {
        return "DatasetGraphRDFSLive[" + label + "]";
    }
}
