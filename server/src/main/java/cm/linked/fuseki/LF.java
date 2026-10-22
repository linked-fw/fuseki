package cm.linked.fuseki;

import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;

import cm.linked.fuseki.rdfs.DatasetRDFSLiveAssembler;

/** The module's vocabulary: the dataset type and the stored-only operation. */
public final class LF {
    private LF() {}

    public static final String NS = "https://linked.cm/ont/fuseki#";

    /** Dataset type: Jena's RDFS view over a base dataset, whose class hierarchy follows the stored data. */
    public static final Resource DatasetRDFSLive = ResourceFactory.createResource(NS + "DatasetRDFSLive");

    /** Operation: SPARQL query and GSP read over the stored triples only. */
    public static final Operation stored = Operation.alloc(NS + "stored", "stored", "Stored triples only (no inference)");

    private static volatile boolean initialized = false;

    /**
     * Registers the dataset type with the assembler system. Idempotent. Runs from the Jena
     * subsystem, before any configuration file or template is parsed.
     */
    public static synchronized void init() {
        if ( initialized )
            return;
        initialized = true;
        AssemblerUtils.registerDataset(DatasetRDFSLive, new DatasetRDFSLiveAssembler());
    }

    /** The module's version, from the jar manifest (the image tag). */
    public static String version() {
        String v = LF.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
