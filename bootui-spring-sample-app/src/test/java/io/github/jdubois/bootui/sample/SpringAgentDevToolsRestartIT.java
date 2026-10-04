package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.agent.HeapWalk;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The agent across real DevTools restarts ({@code docs/PLAN-v2.md} M5-1, §5.13's leak risk): the sample runs in a JVM
 * of its own with the BootUI agent and DevTools, every restart claims the agent again in the same slot, and after ten
 * restarts a heap walk rooted at the agent (its classes' statics, its instances, and its threads) must reach no earlier
 * restart's class loader but the first, which a mutation keeps through a thread named as the agent's, to prove the
 * walk finds such a hold. Earlier restarts stay in the heap without the agent too (Spring Data's static type caches and
 * the first run's logging shutdown handler hold them), so only the agent's own paths are asserted; the engine's drain
 * thread, which reaches the run still going, is the walk's control.
 */
class SpringAgentDevToolsRestartIT {

    private static final int RESTARTS = 10;
    private static final String AGENT = "io/github/jdubois/bootui/agent/";
    private static final String SENTINEL = "io/github/jdubois/bootui/sample/RestartSentinel";

    private Process process;
    private Path log;
    private Path triggers;

    @AfterEach
    void stop() throws InterruptedException, IOException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        if (triggers != null) {
            Files.deleteIfExists(triggers.resolve("restart.txt"));
            Files.deleteIfExists(triggers);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void afterTenDevToolsRestartsTheAgentReachesNoEarlierRestart() throws Exception {
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).as("the agent jar, from the dependency plugin").isNotBlank();
        Path directory = Path.of("target", "agent-devtools").toAbsolutePath();
        Files.createDirectories(directory);
        // A watched directory of its own, outside every class path entry: each change to its file restarts the sample.
        triggers = Files.createTempDirectory("bootui-devtools-restart-");
        Path trigger = triggers.resolve("restart.txt");
        Files.writeString(trigger, "0");
        log = directory.resolve("sample.log");
        int port = port();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agent,
                "-Dspring.devtools.restart.enabled=true",
                "-Dspring.devtools.restart.additional-paths=" + triggers,
                "-Dspring.devtools.restart.poll-interval=300ms",
                "-Dspring.devtools.restart.quiet-period=100ms",
                "-Dspring.devtools.livereload.enabled=false",
                "-D" + RestartSentinel.MUTATION_PROPERTY + "=true",
                "-cp",
                System.getProperty("java.class.path"),
                DevToolsRestartApplication.class.getName(),
                "--server.port=" + port,
                "--spring.profiles.active=dev",
                "--bootui.show-banner=false",
                "--bootui.overrides-file=" + directory.resolve("overrides.properties"),
                "--management.tracing.export.enabled=false"));
        process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        BootUiHttpProbe probe = new BootUiHttpProbe("http://localhost:" + port);

        List<Long> generations = new ArrayList<>();
        generations.add(awaitArmed(probe, -1, null));
        for (int restart = 1; restart <= RESTARTS; restart++) {
            generations.add(awaitArmed(probe, generations.get(generations.size() - 1), trigger));
        }

        settle(probe);
        JsonNode report = probe.get("/bootui/api/java-agent").json();
        JsonNode counters = report.path("counters");
        assertThat(counters.path("takeovers").asLong()).as(report.toString()).isZero();
        assertThat(counters.path("holds").asLong()).as(report.toString()).isZero();
        assertThat(counters.path("errors").asLong()).as(report.toString()).isZero();
        assertThat(report.path("retransformation").path("failed").asInt())
                .as(report.toString())
                .isZero();
        int current = lastRun();
        assertThat(current)
                .as("each restart loaded its own sentinel: %s", tail())
                .isGreaterThan(RESTARTS);

        Path dump = directory.resolve("devtools-restarts.hprof");
        Files.deleteIfExists(dump);
        jcmd("GC.run");
        jcmd("GC.heap_dump", dump.toString());
        HeapWalk walk = HeapWalk.read(dump);
        SortedSet<Integer> present = walk.runsPresent(SENTINEL);
        Map<String, Integer> roots = walk.agentRoots(AGENT);
        Map<Integer, String> reached = walk.runsReachedByAgent(AGENT, SENTINEL);
        // The control: the engine's drain thread works for the run still going, so the walk reaches that run from it.
        Map<Integer, String> fromDrain = walk.runsReachedFromThreads("bootui-agent-drain", AGENT, SENTINEL);
        int drainThreads = walk.liveThreads("bootui-agent-drain");

        System.out.println("CLAIM_GENERATIONS=" + generations);
        System.out.println("AGENT_ROOTS=" + roots);
        System.out.println("RUNS_IN_HEAP=" + present);
        System.out.println("RUNS_REACHED_BY_AGENT=" + reached.keySet());
        System.out.println("RUNS_REACHED_FROM_THE_ENGINE_DRAIN=" + fromDrain.keySet());
        assertThat(present).as("the run still going is in the heap").contains(current);
        assertThat(fromDrain)
                .as("the walk reaches the run still going from its drain thread")
                .containsKey(current);
        assertThat(drainThreads)
                .as("the engine drains the agent for the run still going only")
                .isEqualTo(1);
        assertThat(roots)
                .as("the walk starts from the agent's classes, instances, and threads")
                .allSatisfy((kind, count) -> assertThat(count).as(kind).isPositive());
        // The mutation: the first run's thread named as an agent thread keeps that run, and the walk must find it.
        assertThat(reached).as("the first run, which the mutation thread keeps").containsKey(1);
        assertThat(reached.get(1)).contains("ROOT agent thread " + RestartSentinel.MUTATION_THREAD);
        assertThat(reached.keySet())
                .as("earlier restarts the agent strongly reaches: %s", reached)
                .allMatch(run -> run == 1 || run == current);
        // Kept when an assertion fails, to find what else holds an earlier restart; best effort, since on Windows the
        // walk's mapped buffers keep the dump open until they are collected.
        try {
            Files.deleteIfExists(dump);
        } catch (IOException ex) {
            dump.toFile().deleteOnExit();
        }
    }

    /**
     * Waits until a claim newer than {@code previous} is armed and its run is ready, and returns its generation. With a {@code trigger},
     * changes it first, and again every few seconds until DevTools starts a restart: its file watcher takes its first
     * snapshot only once the restarted context is ready, which can be after the claim is already armed.
     */
    private long awaitArmed(BootUiHttpProbe probe, long previous, Path trigger) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        long touched = 0;
        int readyRuns = lastRun();
        while (true) {
            // Changed again only while no restart is under way, so a slow restart is never restarted, but a restart
            // that failed to start, as under a heavy load, is followed by another.
            if (trigger != null && !restartUnderWay() && System.nanoTime() - touched > TimeUnit.SECONDS.toNanos(5)) {
                Files.writeString(trigger, String.valueOf(System.nanoTime()));
                touched = System.nanoTime();
            }
            assertThat(process.isAlive())
                    .as("the sample is running: %s", tail())
                    .isTrue();
            try {
                BootUiHttpProbe.Response response = probe.get("/bootui/api/java-agent");
                if (response.status() == 200) {
                    JsonNode report = response.json();
                    long generation = report.path("claim").path("generation").asLong(-1);
                    // Ready too: a restart triggered while a run still starts would fail that run.
                    if ("ARMED".equals(report.path("state").asText())
                            && generation > previous
                            && lastRun() > readyRuns) {
                        return generation;
                    }
                }
            } catch (RuntimeException notYet) {
                // restarting
            }
            assertThat(System.nanoTime())
                    .as("a claim newer than %d: %s", previous, tail())
                    .isLessThan(deadline);
            Thread.sleep(250);
        }
    }

    /** Waits until no restart is under way: the last run and its armed claim stay the same for three seconds. */
    private void settle(BootUiHttpProbe probe) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        String before = null;
        while (System.nanoTime() < deadline) {
            String now = lastRun() + "/" + armedGeneration(probe);
            if (now.equals(before) && !now.endsWith("/-1")) {
                return;
            }
            before = now;
            Thread.sleep(3_000);
        }
        throw new AssertionError("the sample never settled: " + tail());
    }

    private static long armedGeneration(BootUiHttpProbe probe) {
        try {
            JsonNode report = probe.get("/bootui/api/java-agent").json();
            return "ARMED".equals(report.path("state").asText())
                    ? report.path("claim").path("generation").asLong(-1)
                    : -1;
        } catch (RuntimeException restarting) {
            return -1;
        }
    }

    /** The sample's log so far, read leniently while the sample writes it. */
    private List<String> logLines() throws IOException {
        return new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)
                .lines()
                .toList();
    }

    /** Whether DevTools started a restart since the last run was ready, and that restart has not failed. */
    private boolean restartUnderWay() throws IOException {
        int restarting = -1;
        int ready = -1;
        int failed = -1;
        List<String> lines = logLines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("Restarting due to")) {
                restarting = i;
            } else if (line.startsWith("RESTART_RUN=")) {
                ready = i;
            } else if (line.contains("APPLICATION FAILED TO START") || line.contains("Application run failed")) {
                failed = i;
            }
        }
        return restarting > ready && restarting > failed;
    }

    /** The number of the last run that loaded its sentinel. */
    private int lastRun() throws IOException {
        int run = -1;
        for (String line : logLines()) {
            if (line.startsWith("RESTART_RUN=")) {
                run = Integer.parseInt(line.substring("RESTART_RUN=".length()).trim());
            }
        }
        return run;
    }

    private void jcmd(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString());
        command.add(String.valueOf(process.pid()));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(log.getParent(), "jcmd-", ".log");
        Process jcmd = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean done = jcmd.waitFor(180, TimeUnit.SECONDS);
        if (!done) {
            jcmd.destroyForcibly();
        }
        assertThat(done)
                .as("jcmd %s ended: %s", arguments[0], Files.readString(output))
                .isTrue();
        assertThat(jcmd.exitValue()).as(Files.readString(output)).isZero();
    }

    private static int port() throws IOException {
        String configured = System.getProperty("bootui.it.port");
        if (configured != null && !configured.isBlank()) {
            return Integer.parseInt(configured.trim());
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String tail() {
        try {
            String text = Files.readString(log);
            return text.length() <= 6_000 ? text : text.substring(text.length() - 6_000);
        } catch (IOException ex) {
            return ex.toString();
        }
    }
}
