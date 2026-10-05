package io.github.jdubois.bootui.sample.sideeffects;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The agent overhead benchmark's I/O ({@code docs/PLAN-v2.md} §5.16, M5-12): one outbound connect to a local stub
 * server and one file read per request, so the side-effect sensors that hook connects and files are measured on a route
 * that exercises them. The stub's port is {@code sample.benchmark.stub-port}, else the application's own port; the file
 * is a small temporary one, created once.
 */
@Component
public class BenchmarkIo {

    private final Environment environment;
    private volatile Path file;

    public BenchmarkIo(Environment environment) {
        this.environment = environment;
    }

    /** Connects to the stub and closes the connection, then reads the file: the bytes it read. */
    public int touch() {
        String port = environment.getProperty(
                "sample.benchmark.stub-port",
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 2_000);
        } catch (IOException ex) {
            throw new UncheckedIOException("the benchmark stub at port " + port + " refused the connection", ex);
        }
        try (InputStream in = Files.newInputStream(file())) {
            return in.readAllBytes().length;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path file() throws IOException {
        Path current = file;
        if (current == null) {
            synchronized (this) {
                current = file;
                if (current == null) {
                    current = Files.createTempFile("bootui-benchmark-", ".txt");
                    Files.writeString(current, "bootui benchmark\n", StandardCharsets.UTF_8);
                    current.toFile().deleteOnExit();
                    file = current;
                }
            }
        }
        return current;
    }
}
