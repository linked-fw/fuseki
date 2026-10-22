package cm.linked.fuseki.fuseki;

import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.fuseki.servlets.SPARQL_QueryDataset;
import org.apache.jena.sparql.core.DatasetGraph;

import cm.linked.fuseki.rdfs.LiveDatasets;

/**
 * SPARQL query over the stored dataset: the raw TDB2 dataset, run by TDB2's own query engine,
 * in the transaction the action began on the service's dataset.
 */
public class StoredQuery extends SPARQL_QueryDataset {
    @Override
    protected DatasetGraph getDataset(HttpAction action) {
        return LiveDatasets.stored(action.getActiveDSG());
    }
}
