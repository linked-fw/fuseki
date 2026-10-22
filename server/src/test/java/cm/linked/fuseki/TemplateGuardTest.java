package cm.linked.fuseki;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cm.linked.fuseki.fuseki.TemplateGuard;
import cm.linked.fuseki.fuseki.TemplateGuard.Result;

class TemplateGuardTest {
    @Test
    void writesWhenMissing(@TempDir Path base) throws Exception {
        assertEquals(Result.WRITTEN, TemplateGuard.ensure(base));
        assertArrayEquals(TemplateGuard.bundled(), Files.readAllBytes(base.resolve("templates/config-tdb2")));
    }

    @Test
    void replacesWhenDifferent(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve("templates"));
        Files.writeString(base.resolve("templates/config-tdb2"), "# stock");
        assertEquals(Result.REPLACED, TemplateGuard.ensure(base));
        assertArrayEquals(TemplateGuard.bundled(), Files.readAllBytes(base.resolve("templates/config-tdb2")));
    }

    @Test
    void leavesItWhenEqual(@TempDir Path base) {
        TemplateGuard.ensure(base);
        assertEquals(Result.UNCHANGED, TemplateGuard.ensure(base));
    }

    @Test
    void bundledTemplateInfers() {
        String t = new String(TemplateGuard.bundled());
        assertTrue(t.contains("lf:DatasetRDFSLive"));
        assertTrue(t.contains("lf:stored"));
        assertTrue(!t.contains("unionDefaultGraph true"));
    }
}
