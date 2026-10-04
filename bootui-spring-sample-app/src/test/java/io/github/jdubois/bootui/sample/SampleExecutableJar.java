package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The sample's repackaged Spring Boot jar ({@code sample.jar}, set by Failsafe) started with {@code java -jar} in a JVM
 * of its own, as an application runs in production: its classes and libraries load from {@code jar:nested:} URLs. Used
 * by the integration tests that must see the agent on a real executable jar ({@code docs/PLAN-v2.md} M5-12).
 */
final class SampleExecutableJar implements AutoCloseable {

    private final Process process;
    private final Path log;
    private final int port;
    private final BootUiHttpProbe probe;

    private SampleExecutableJar(Process process, Path log, int port) {
        this.process = process;
        this.log = log;
        this.port = port;
        this.probe = new BootUiHttpProbe("http://localhost:" + port);
    }

    /**
     * Starts the jar with these JVM options, on a free port, with the Docker-free {@code dev} profile on a private
     * in-memory database, and waits, bounded, until BootUI answers.
     */
    static SampleExecutableJar start(String name, List<String> jvmOptions, List<String> applicationArguments)
            throws Exception {
        String jar = System.getProperty("sample.jar");
        if (jar == null || !Files.isRegularFile(Path.of(jar))) {
            throw new IllegalStateException("sample.jar names no repackaged jar: " + jar);
        }
        Path directory = Path.of("target", "executable-jar", name).toAbsolutePath();
        Files.createDirectories(directory);
        Path log = directory.resolve("sample.log");
        // The free port is chosen before the sample binds it, so another process may take it first: start again, on
        // another port, when the sample says so.
        for (int attempt = 1; ; attempt++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmOptions);
            command.addAll(List.of(
                    "-jar",
                    jar,
                    "--server.port=" + port,
                    // The default profile does not count as active for BootUI's activation, so name it.
                    "--spring.profiles.active=dev",
                    "--spring.datasource.url=jdbc:h2:mem:bootui_" + name.replace('-', '_')
                            + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                    "--bootui.show-banner=false",
                    "--bootui.overrides-file=" + directory.resolve("overrides.properties"),
                    "--management.tracing.export.enabled=false"));
            command.addAll(applicationArguments);
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            SampleExecutableJar sample = new SampleExecutableJar(process, log, port);
            try {
                sample.awaitReady();
                return sample;
            } catch (Exception | AssertionError ex) {
                sample.close();
                if (attempt < 3 && sample.tail().contains("already in use")) {
                    continue;
                }
                throw ex;
            }
        }
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (true) {
            if (!process.isAlive()) {
                throw new IllegalStateException("The sample exited with " + process.exitValue() + ": " + tail());
            }
            try {
                if (probe.get("/bootui/api/java-agent").status() == 200) {
                    return;
                }
            } catch (RuntimeException notYet) {
                // still starting
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The sample did not start in 180 s: " + tail());
            }
            Thread.sleep(250);
        }
    }

    int port() {
        return port;
    }

    BootUiHttpProbe probe() {
        return probe;
    }

    /** The end of the sample's log, for assertion messages. */
    String tail() {
        try {
            String text = Files.readString(log);
            return text.length() <= 4_000 ? text : text.substring(text.length() - 4_000);
        } catch (IOException ex) {
            return ex.toString();
        }
    }

    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
