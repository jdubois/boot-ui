package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The resources sensor's seeds ({@code docs/PLAN-v2.md} §5.16, M5-5g): with the BootUI agent's {@code files},
 * {@code network}, and {@code resources} sensors, {@link #leakStream()} leaves a {@code FileInputStream} open past its
 * request, then reclaimed by the collector without {@code close()}; its counterexample {@link #closeStream()} closes it
 * in try-with-resources; and {@link #pooledCall(int)} leaves the JDK {@code HttpClient}'s pooled connection open past
 * its request, a library's hand-off, never reclaimed without {@code close()}.
 */
@org.springframework.stereotype.Component
public class ResourceSeeds {

    /** The JDK's HTTP client, kept for the application's life: its connections are pooled past their request. */
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private volatile Path seed;

    /**
     * Opens the seed file, reads a byte, and drops the stream without closing it: open after its request, then
     * reclaimed by the collector without {@code close()}.
     */
    public int leakStream() throws IOException {
        FileInputStream stream = new FileInputStream(seed().toFile());
        return stream.read();
    }

    /** The counterexample: the same read in try-with-resources, never reported. */
    public int closeStream() throws IOException {
        try (FileInputStream stream = new FileInputStream(seed().toFile())) {
            return stream.read();
        }
    }

    /**
     * Calls this application through the JDK's {@code HttpClient}, whose pool keeps the connection open past the
     * request: a library's hand-off, reported open after its request, never reclaimed without {@code close()}.
     */
    public int pooledCall(int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/side-effects/runtime-version"))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Asks the collector to run, so a stream dropped without {@code close()} is reclaimed now (the e2e suites). */
    public void collect() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
    }

    private Path seed() throws IOException {
        Path current = seed;
        if (current == null) {
            current = Files.createTempFile("bootui-resource-seed", ".txt");
            Files.writeString(current, "seed");
            current.toFile().deleteOnExit();
            seed = current;
        }
        return current;
    }
}
