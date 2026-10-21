package cm.linked.fuseki.rdfs;

import java.util.Optional;

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;

/** Finds the live RDFS dataset inside a service's dataset (for example under a text index). */
public final class LiveDatasets {
    private LiveDatasets() {}

    public static Optional<DatasetGraphRDFSLive> find(DatasetGraph dsg) {
        DatasetGraph d = dsg;
        while ( d != null ) {
            if ( d instanceof DatasetGraphRDFSLive live )
                return Optional.of(live);
            if ( d instanceof DatasetGraphWrapper w )
                d = w.getWrapped();
            else
                return Optional.empty();
        }
        return Optional.empty();
    }

    /** The stored dataset under {@code dsg}'s live dataset, or {@code dsg} itself when it has none. */
    public static DatasetGraph stored(DatasetGraph dsg) {
        return find(dsg).map(DatasetGraphRDFSLive::stored).orElse(dsg);
    }
}
