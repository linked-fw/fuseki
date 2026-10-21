package cm.linked.fuseki.fuseki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.fuseki.mgt.FusekiServerCtl;
import org.apache.jena.fuseki.server.DataAccessPoint;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.apache.jena.fuseki.server.DataService;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.store.DatasetGraphSwitchable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cm.linked.fuseki.rdfs.DatasetGraphRDFSLive;
import cm.linked.fuseki.rdfs.LiveDatasets;

/**
 * Completes {@code DELETE /$/datasets/<name>} for a live RDFS dataset. Fuseki removes a deleted
 * dataset's database files only when the service's dataset is TDB2 itself, which a wrapped
 * dataset is not; the files would stay and a dataset re-created under the same name would reopen
 * the old data. After a successful delete this removes them, under the same rule as Fuseki's own:
 * only {@code <run area>/databases/<name>}, never a symbolic link.
 */
public class DropDatabaseFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger("cm.linked.fuseki");
    static final String PREFIX = "/$/datasets/";

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        Path target = null;
        if ( req instanceof HttpServletRequest hreq && "DELETE".equalsIgnoreCase(hreq.getMethod()) ) {
            try {
                target = databaseToRemove(hreq);
            } catch (RuntimeException ex) {
                LOG.warn("[linked-fuseki] delete: cannot resolve the dataset: {}", ex.getMessage());
            }
        }
        chain.doFilter(req, resp);
        if ( target == null )
            return;
        int status = (resp instanceof HttpServletResponse hresp) ? hresp.getStatus() : 0;
        if ( status < 200 || status >= 300 )
            return;
        remove(target);
    }

    /** The dataset name in the request path, with a leading slash, or null. */
    static String datasetName(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if ( ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) )
            uri = uri.substring(ctx.length());
        int i = uri.indexOf(PREFIX);
        if ( i != 0 )
            return null;
        String name = uri.substring(PREFIX.length());
        while ( name.endsWith("/") )
            name = name.substring(0, name.length() - 1);
        if ( name.isEmpty() || name.contains("/") )
            return null;
        return "/" + name;
    }

    private static Path databaseToRemove(HttpServletRequest req) {
        String name = datasetName(req);
        if ( name == null )
            return null;
        DataAccessPointRegistry registry = DataAccessPointRegistry.get(req.getServletContext());
        if ( registry == null )
            return null;
        DataAccessPoint dap = registry.get(name);
        if ( dap == null )
            return null;
        DataService service = dap.getDataService();
        DatasetGraph dsg = service == null ? null : service.getDataset();
        if ( dsg == null )
            return null;
        Optional<DatasetGraphRDFSLive> live = LiveDatasets.find(dsg);
        if ( live.isEmpty() )
            return null;
        return candidate(live.get().stored(), FusekiServerCtl.dirDatabases, name.substring(1));
    }

    /**
     * The directory to remove: the stored dataset's TDB2 location when it is exactly
     * {@code <databases>/<name>} and not a symbolic link; otherwise null.
     */
    static Path candidate(DatasetGraph stored, Path databases, String name) {
        if ( databases == null || !(stored instanceof DatasetGraphSwitchable sw) )
            return null;
        Location loc = sw.getLocation();
        if ( loc == null || loc.isMem() || loc.getDirectoryPath() == null )
            return null;
        Path dir = Path.of(loc.getDirectoryPath()).toAbsolutePath().normalize();
        Path expected = databases.resolve(name).toAbsolutePath().normalize();
        if ( dir.getFileName() == null || !dir.getFileName().toString().equals(name) )
            return null;
        if ( !expected.getParent().equals(databases.toAbsolutePath().normalize()) )
            return null;
        // Compare the parents by their real paths (a temporary or mounted directory may be
        // reached through a link); the database directory itself must not be a link.
        try {
            if ( !Files.isDirectory(dir.getParent()) || !Files.isDirectory(expected.getParent()) )
                return null;
            if ( !dir.getParent().toRealPath().equals(expected.getParent().toRealPath()) )
                return null;
        } catch (IOException ex) {
            return null;
        }
        if ( Files.isSymbolicLink(expected) )
            return null;
        return expected;
    }

    static void remove(Path dir) {
        if ( !Files.exists(dir) )
            return;
        try {
            IO.deleteAll(dir);
            LOG.info("[linked-fuseki] deleted database files {}", dir);
        } catch (RuntimeException ex) {
            LOG.error("[linked-fuseki] error deleting database files {}: {}", dir, ex.getMessage());
        }
    }
}
