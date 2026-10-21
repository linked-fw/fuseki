package cm.linked.fuseki.fuseki;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.jena.atlas.lib.Lib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@code $FUSEKI_BASE/templates/config-tdb2} equal to the bundled, inferring template.
 * Fuseki copies its stock template only when the file is missing, so a persisted run area from
 * before this module would otherwise create plain, non-inferring datasets without a word.
 */
public final class TemplateGuard {
    private static final Logger LOG = LoggerFactory.getLogger("cm.linked.fuseki");

    static final String RESOURCE = "/cm/linked/fuseki/config-tdb2";
    static final String TEMPLATE = "templates/config-tdb2";

    public enum Result { NO_BASE, WRITTEN, REPLACED, UNCHANGED }

    private TemplateGuard() {}

    /** Applies the guard to {@code $FUSEKI_BASE} (system property first, then the environment). */
    public static Result ensure() {
        String base = Lib.getenv("FUSEKI_BASE");
        if ( base == null || base.isBlank() )
            return Result.NO_BASE;
        return ensure(Path.of(base));
    }

    public static Result ensure(Path fusekiBase) {
        byte[] bundled = bundled();
        Path file = fusekiBase.resolve(TEMPLATE);
        try {
            if ( Files.exists(file) ) {
                byte[] existing = Files.readAllBytes(file);
                if ( Arrays.equals(existing, bundled) )
                    return Result.UNCHANGED;
                Files.write(file, bundled);
                LOG.warn("[linked-fuseki] {} differed from the module's template and was replaced: datasets created with dbType=tdb2 infer RDFS classes", file);
                return Result.REPLACED;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, bundled);
            LOG.info("[linked-fuseki] wrote {}", file);
            return Result.WRITTEN;
        } catch (IOException ex) {
            throw new IllegalStateException("[linked-fuseki] cannot write " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** The template bundled in the module's jar. */
    public static byte[] bundled() {
        try (InputStream in = TemplateGuard.class.getResourceAsStream(RESOURCE)) {
            if ( in == null )
                throw new IllegalStateException("[linked-fuseki] bundled template missing: " + RESOURCE);
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
