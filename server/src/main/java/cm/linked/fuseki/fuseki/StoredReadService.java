package cm.linked.fuseki.fuseki;

import org.apache.jena.fuseki.servlets.ActionService;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.riot.WebContent;
import org.apache.jena.riot.web.HttpNames;

/**
 * The {@code lf:stored} operation: one endpoint for SPARQL query and GSP read over the stored
 * triples only. A request with a {@code query} parameter, a {@code application/sparql-query}
 * body or the HTTP QUERY method is a query; anything else is a GSP read (no query string: the
 * whole dataset as quads; {@code ?default}; {@code ?graph=<iri>}).
 */
public class StoredReadService extends ActionService {
    private final StoredQuery query = new StoredQuery();
    private final StoredGSP gsp = new StoredGSP();

    static boolean isQuery(HttpAction action) {
        if ( "QUERY".equalsIgnoreCase(action.getRequestMethod()) )
            return true;
        if ( action.getRequestParameter(HttpNames.paramQuery) != null )
            return true;
        String ct = action.getRequestContentType();
        return ct != null && ct.toLowerCase().startsWith(WebContent.contentTypeSPARQLQuery);
    }

    private ActionService choose(HttpAction action) {
        return isQuery(action) ? query : gsp;
    }

    @Override
    public void process(HttpAction action) {
        choose(action).process(action);
    }

    @Override
    public void validate(HttpAction action) {
        choose(action).validate(action);
    }

    @Override
    public void execute(HttpAction action) {
        choose(action).execute(action);
    }
}
