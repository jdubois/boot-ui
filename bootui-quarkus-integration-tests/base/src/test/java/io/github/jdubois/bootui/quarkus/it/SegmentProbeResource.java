package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.engine.resources.SegmentMeter;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;

/**
 * Endpoints used only by {@link BootUiQuarkusWorkerSegmentReleaseTest}: each reports what its own worker thread is
 * metered for while it runs the resource method, and again once Quarkus has completed the request on that very
 * thread, which is the moment BootUI releases a worker whose response body outlives its chain.
 *
 * <p>The probe registers its completion callback from the resource method, so it runs <em>after</em> BootUI's, which
 * a request filter registered first. A reading of {@code none} therefore means BootUI had already stopped metering
 * the worker; the request's own id means the worker went back to its pool still charged to the finished request.</p>
 */
@Path("/it/segments")
public class SegmentProbeResource {

    /** The last reading of each probe, by name, so the test can read it back over HTTP. */
    private static final Map<String, String> OBSERVED = new ConcurrentHashMap<>();

    private static final String NONE = "none";

    @GET
    @Path("/entity")
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String entity() {
        return observe("entity");
    }

    @GET
    @Path("/file")
    @Blocking
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public File file() {
        observe("file");
        return payload();
    }

    @GET
    @Path("/observed/{probe}")
    @Blocking
    @Produces(MediaType.TEXT_PLAIN)
    public String observed(@PathParam("probe") String probe) {
        return OBSERVED.getOrDefault(probe, "missing");
    }

    /**
     * Records what this worker is metered for now, and what it is metered for once Quarkus completes the request on
     * it, as {@code <inMethod>|<atCompletion>|<completedOnTheSameThread>}.
     */
    private static String observe(String probe) {
        Thread worker = Thread.currentThread();
        String inMethod = metered();
        CurrentRequestManager.get()
                .registerCompletionCallback(throwable ->
                        OBSERVED.put(probe, inMethod + "|" + metered() + "|" + (Thread.currentThread() == worker)));
        return inMethod;
    }

    private static String metered() {
        String requestId = SegmentMeter.shared().currentRequestId();
        return requestId == null ? NONE : requestId;
    }

    /** A payload large enough that Quarkus streams it with {@code sendFile} rather than ending it with the chain. */
    private static File payload() {
        try {
            File file = Files.createTempFile("bootui-it-segment", ".bin").toFile();
            file.deleteOnExit();
            byte[] block = new byte[64 * 1024];
            Arrays.fill(block, (byte) 'b');
            try (OutputStream out = Files.newOutputStream(file.toPath())) {
                for (int i = 0; i < 16; i++) {
                    out.write(block);
                }
            }
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
