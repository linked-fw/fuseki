package cm.linked.fuseki.fuseki;

import org.apache.jena.fuseki.servlets.GSP_R;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.sparql.core.DatasetGraph;

import cm.linked.fuseki.rdfs.LiveDatasets;

/** Graph Store Protocol read over the stored dataset. Writes answer 405 (GSP_R). */
public class StoredGSP extends GSP_R {
    @Override
    protected DatasetGraph decideDataset(HttpAction action) {
        return LiveDatasets.stored(action.getActiveDSG());
    }
}
