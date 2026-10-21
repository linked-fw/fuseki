package cm.linked.fuseki.rdfs;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.Transactional.Promote;
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
 * next read. A transaction never reads with a hierarchy older than its snapshot (one that waited
 * for the writer lock, or began during a commit, builds its own view); it keeps the view it first
 * read with until it ends.
 */
public class DatasetGraphRDFSLive extends DatasetGraphWrapper implements DatasetGraphWrapperView {
    private static final Logger LOG = LoggerFactory.getLogger("cm.linked.fuseki");

    /** A view and the generation of the stored hierarchy it reflects (at least). */
    private record View(DatasetGraphRDFS dsg, long generation, long subClassOf) {}

    /**
     * Per-thread transaction state. {@code completed}: the generation read before begin (every
     * commit it counts finished before this transaction began, so the transaction sees it).
     * {@code started}: the commits that had begun committing when begin returned (a commit still
     * in flight may or may not be in this transaction's snapshot). The view once resolved.
     */
    private static final class TxnState {
        final long completed;
        long started;
        View view;
        TxnState(long completed, long started) { this.completed = completed; this.started = started; }
    }

    private final DatasetGraph stored;
    private final SubClassOfTracker tracker;
    private final String label;
    /** Hierarchy commits finished (bumped after TDB2's commit returns). */
    private final AtomicLong generation = new AtomicLong(0);
    /** Hierarchy commits started (bumped before TDB2's commit). Never behind {@code generation}. */
    private final AtomicLong pending = new AtomicLong(0);
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
            state.view = resolve(state);
        return state.view;
    }

    /**
     * The view for this transaction. The shared view serves when it reflects every hierarchy
     * commit that had started when the transaction began ({@code started}); a transaction that
     * waited for TDB2's writer lock, or began while a commit was in flight, otherwise builds from
     * its own snapshot. A view built here is published, labelled {@code completed} (commits this
     * transaction certainly sees), unless this transaction wrote to the hierarchy itself: its
     * triples are uncommitted, so that view is used by it alone.
     */
    private View resolve(TxnState state) {
        View v = current;
        if ( v.generation() >= state.started )
            return v;
        if ( tracker.isTouched() )
            return build(v.generation());
        rebuildLock.lock();
        try {
            v = current;
            if ( v.generation() >= state.started )
                return v;
            View built = build(state.completed);
            if ( built.generation() > v.generation() ) {
                current = built;
                rebuilds.incrementAndGet();
                LOG.info("[linked-fuseki] {}: hierarchy rebuilt ({} subClassOf, {} ms, generation {})",
                         label, built.subClassOf(), lastRebuildMillis, built.generation());
            }
            return built;
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
        long completed = generation.get();
        stored.begin();
        txn.set(new TxnState(completed, pending.get()));
    }

    @Override
    public void begin(TxnType type) {
        long completed = generation.get();
        stored.begin(type);
        txn.set(new TxnState(completed, pending.get()));
    }

    @Override
    public void begin(ReadWrite readWrite) {
        long completed = generation.get();
        stored.begin(readWrite);
        txn.set(new TxnState(completed, pending.get()));
    }

    @Override
    public boolean promote() {
        return afterPromote(stored.promote());
    }

    @Override
    public boolean promote(Promote type) {
        return afterPromote(stored.promote(type));
    }

    // A promotion may move the transaction to newer data (read-committed promotion): resolve again.
    private boolean afterPromote(boolean promoted) {
        TxnState state = txn.get();
        if ( promoted && state != null ) {
            long started = pending.get();
            if ( started != state.started ) {
                state.started = started;
                state.view = null;
            }
        }
        return promoted;
    }

    @Override
    public void commit() {
        boolean touched = tracker.takeTouched();
        if ( touched )
            pending.incrementAndGet();
        try {
            stored.commit();
        } finally {
            // A failed commit only costs one rebuild at the next read.
            if ( touched )
                generation.incrementAndGet();
        }
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
