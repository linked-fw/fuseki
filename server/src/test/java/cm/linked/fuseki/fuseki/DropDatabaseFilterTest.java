package cm.linked.fuseki.fuseki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DropDatabaseFilterTest {
    @Test
    void removesOnlyTheDatabaseOfThatName(@TempDir Path base) throws Exception {
        Path databases = Files.createDirectories(base.resolve("databases"));
        DatasetGraph inside = DatabaseMgr.connectDatasetGraph(Location.create(databases.resolve("d1")));
        assertEquals(databases.resolve("d1").toAbsolutePath().normalize(), DropDatabaseFilter.candidate(inside, databases, "d1"));
        // Another name: the location does not match.
        assertNull(DropDatabaseFilter.candidate(inside, databases, "d2"));
        // Outside databases/.
        DatasetGraph outside = DatabaseMgr.connectDatasetGraph(Location.create(base.resolve("elsewhere")));
        assertNull(DropDatabaseFilter.candidate(outside, databases, "elsewhere"));
        // In memory, or not TDB2.
        assertNull(DropDatabaseFilter.candidate(DatabaseMgr.createDatasetGraph(), databases, "d1"));
        assertNull(DropDatabaseFilter.candidate(org.apache.jena.sparql.core.DatasetGraphFactory.createTxnMem(), databases, "d1"));
        // No run area.
        assertNull(DropDatabaseFilter.candidate(inside, null, "d1"));
    }

    @Test
    void neverASymbolicLink(@TempDir Path base) throws Exception {
        Path databases = Files.createDirectories(base.resolve("databases"));
        Path real = Files.createDirectories(base.resolve("real"));
        Files.createSymbolicLink(databases.resolve("d3"), real);
        DatasetGraph linked = DatabaseMgr.connectDatasetGraph(Location.create(databases.resolve("d3")));
        assertNull(DropDatabaseFilter.candidate(linked, databases, "d3"));
    }
}
