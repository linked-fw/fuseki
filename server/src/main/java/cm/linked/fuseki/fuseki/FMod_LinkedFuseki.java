package cm.linked.fuseki.fuseki;

import java.util.Set;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiAutoModule;
import org.apache.jena.query.ARQ;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cm.linked.fuseki.LF;

/**
 * The Fuseki side of the module: SERVICE off, the {@code lf:stored} handler, the inferring
 * template kept in place, and the delete of a live dataset's files completed.
 */
public class FMod_LinkedFuseki implements FusekiAutoModule {
    private static final Logger LOG = LoggerFactory.getLogger("cm.linked.fuseki");

    /** Set to {@code false} (system property) to leave Fuseki's own delete behaviour unchanged. */
    public static final String PROP_DROP_FILTER = "lf.dropFilter";

    @Override
    public String name() {
        return "LinkedFuseki";
    }

    @Override
    public void prepare(FusekiServer.Builder builder, Set<String> datasetNames, Model configModel) {
        ARQ.getContext().set(ARQ.httpServiceAllowed, false);
        LF.init();
        // The registry is frozen right after prepare: the handler is registered here.
        builder.registerOperation(LF.stored, new StoredReadService());
        TemplateGuard.Result template = TemplateGuard.ensure();
        boolean dropFilter = !"false".equalsIgnoreCase(System.getProperty(PROP_DROP_FILTER, "true"));
        if ( dropFilter )
            builder.addFilter("/$/datasets/*", new DropDatabaseFilter());
        LOG.info("[linked-fuseki] module {}: lf:DatasetRDFSLive, /stored, SERVICE off, template {}, drop filter {}",
                 LF.version(), template, dropFilter ? "on" : "off");
    }
}
