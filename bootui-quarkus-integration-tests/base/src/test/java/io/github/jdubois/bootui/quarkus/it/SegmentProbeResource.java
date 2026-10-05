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
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;

/**
 * Endpoints used only by {@link BootUiQuarkusWorkerSegmentReleaseTest}: each reports what its own worker thread is
 * metered for while it runs the resource method, and again once Quarkus has completed the request on that very
 * thread, which is the moment BootUI releases a worker whose response body outlives its chain.
 *
 * <p>The probe registers its completion callback from the resource method, so it runs <em>after</em> BootUI's, which
 * a request filter registered first. A reading of {@code none} therefore means BootUI had already stopped metering
 * the worker; the request's own id means the worker went back to its pool still charged to the finished request.</p>
 *
 * <p>Quarkus may run that callback only after the client has received the response, so a reading is read back by
 * waiting, bounded, for the one the latest call of its probe records.</p>
 */
@Path("/it/segments")
public class SegmentProbeResource {

    /** The reading of the latest call of each probe, by name, so the test can read it back over HTTP. */
    private static final Map<String, CompletableFuture<String>> OBSERVED = new ConcurrentHashMap<>();

    /** How long a read back waits for the completion callback of the probe's latest call. */
    private static final Duration READING_TIMEOUT = Duration.ofSeconds(10);

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
    public String observed(@PathParam("probe") String probe) throws InterruptedException {
        CompletableFuture<String> reading = OBSERVED.get(probe);
        if (reading == null) {
            return "missing";
        }
        try {
            return reading.get(READING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return "missing";
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    /**
     * Records what this worker is metered for now, and what it is metered for once Quarkus completes the request on
     * it, as {@code <inMethod>|<atCompletion>|<completedOnTheSameThread>}.
     */
    private static String observe(String probe) {
        CompletableFuture<String> reading = new CompletableFuture<>();
        OBSERVED.put(probe, reading);
        Thread worker = Thread.currentThread();
        String inMethod = metered();
        CurrentRequestManager.get()
                .registerCompletionCallback(throwable ->
                        reading.complete(inMethod + "|" + metered() + "|" + (Thread.currentThread() == worker)));
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
