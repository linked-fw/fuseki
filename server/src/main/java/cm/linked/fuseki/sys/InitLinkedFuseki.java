package cm.linked.fuseki.sys;

import org.apache.jena.sys.JenaSubsystemLifecycle;

import cm.linked.fuseki.LF;

/**
 * Makes {@code lf:DatasetRDFSLive} and {@code lf:stored} known when Jena starts, so that
 * configuration files and the admin template can name them.
 */
public class InitLinkedFuseki implements JenaSubsystemLifecycle {
    @Override
    public void start() {
        LF.init();
    }

    @Override
    public void stop() {}

    /** After ARQ, TDB2 and Jena's RDFS subsystem (level 60). */
    @Override
    public int level() {
        return 500;
    }
}
