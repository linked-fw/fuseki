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

    public enum Result { NO_BASE, WRITTEN, REPLACED, UNCHANGED, FAILED }

    private TemplateGuard() {}

    /**
     * Applies the guard to the run area Fuseki uses: {@code FUSEKI_BASE} read as Fuseki reads it,
     * with {@code Lib.getenv} (the system property first, then the environment variable), else an
     * existing {@code run} directory in the working directory (the image always sets
     * {@code FUSEKI_BASE}). Without either there is no run area yet: nothing to
     * guard, which is logged.
     */
    public static Result ensure() {
        String base = Lib.getenv("FUSEKI_BASE");
        if ( base == null || base.isBlank() ) {
            Path run = Path.of("run");
            if ( Files.isDirectory(run) )
                return ensure(run);
            LOG.info("[linked-fuseki] FUSEKI_BASE is not set and there is no run/ area: the config-tdb2 template is not guarded");
            return Result.NO_BASE;
        }
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
            // A read-only template (a mounted file) does not stop the server; say so loudly.
            LOG.error("[linked-fuseki] cannot write {} ({}): datasets created with dbType=tdb2 may not infer RDFS classes", file, ex.getMessage());
            return Result.FAILED;
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
