package io.github.jdubois.bootui.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The workshop's query change in one JVM, without modifying the delivered inefficient sample fixture. */
class SpringStartupSqlComparisonIT {

    private static final String ORIGINAL = "/api/insights/eager-orders";
    private static final String COMPARISON = "/bootui/api/runtime-insights/comparison";
    private static final String SERVICE = "io/github/jdubois/bootui/sample/insights/EagerDemoOrderService";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path directory = Path.of("target", "startup-sql-comparison").toAbsolutePath();
    private Process process;
    private Path log;
    private BootUiHttpProbe probe;
    private int readyRuns = 1;

    @AfterEach
    void stop() throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void startupAndOneDevToolsRestartKeepEnoughSqlEvidenceToCompareTheActualFix() throws Exception {
        Path classes = copyApplicationClasses();
        Path trigger = classes.resolve(".sql-comparison-reload");
        Files.writeString(trigger, "before");
        start(classes);
        JsonNode first = await("the first run is ready", this::ready);
        String firstRun = first.path("runId").asText();
        long pid = process.pid();
        assertSensors();
        String before = workload();
        assertRequestSql(17);
        save("before-journal.json", journal());
        // The watcher starts during application startup. Verify a stable ready run before changing its trigger.
        Thread.sleep(1500);
        assertThat(journal().path("runId").asText()).isEqualTo(firstRun);

        replaceFinder(classes.resolve(SERVICE + ".class"));
        readyRuns = 2;
        Files.writeString(trigger, "after");
        await("one new ready run", () -> {
            JsonNode now = ready();
            return now != null && !firstRun.equals(now.path("runId").asText()) ? now : null;
        });
        assertThat(process.pid()).isEqualTo(pid);
        await("the one changed method is not executed", () -> changedMethod("NEVER_EXECUTED"));
        Files.copy(
                directory.resolve("baseline.bin"),
                directory.resolve("before-baseline.bin"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThat(workload()).isEqualTo(before);
        assertRequestSql(1);
        await("the changed method executed", () -> changedMethod("EXECUTED"));
        JsonNode comparison = await("SQL behavior is compared", () -> {
            JsonNode result = probe.get(COMPARISON).json();
            return hasSqlReduction(result) ? result : null;
        });
        assertComparison(comparison);
        assertThat(comparison.path("previous").path("runId").asText()).isEqualTo(firstRun);
        save("comparison.json", comparison);
        save("after-journal.json", journal());
        JsonNode mcp = probe.request("POST", "/bootui/api/mcp", Map.of("Content-Type", "application/json"), """
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
                          "name":"get_runtime_run_comparison","arguments":{"id":"previous"}}}
                        """)
                .json();
        assertThat(mcp.path("result").path("isError").asBoolean())
                .as(mcp.toString())
                .isFalse();
        JsonNode agent = MAPPER.readTree(
                mcp.path("result").path("content").get(0).path("text").asText());
        assertComparison(agent);
        assertThat(agent.path("previousRunId").asText()).isEqualTo(firstRun);
        save("mcp-comparison.json", agent);
        assertThat(Files.readAllLines(log).stream()
                        .filter(line -> line.startsWith("RESTART_RUN="))
                        .count())
                .as("exactly the baseline and one restart")
                .isEqualTo(2);
    }

    private Path copyApplicationClasses() throws IOException {
        Path source = Path.of("target", "classes").toAbsolutePath();
        Path classes = directory.resolve("classes");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = classes.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return classes;
    }

    private void start(Path classes) throws IOException {
        String classPath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> Path.of(entry)
                                .toAbsolutePath()
                                .normalize()
                                .equals(Path.of("target", "classes")
                                        .toAbsolutePath()
                                        .normalize())
                        ? classes.toString()
                        : entry)
                .collect(Collectors.joining(File.pathSeparator));
        Path args = directory.resolve("classpath.args");
        Files.writeString(args, "-cp \"" + classPath.replace("\\", "\\\\") + "\"");
        String agent = System.getProperty("bootui.agent.jar");
        assertThat(agent).isNotBlank();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        log = directory.resolve("sample.log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agent,
                "-Dspring.devtools.restart.enabled=true",
                "-Dspring.devtools.restart.trigger-file=.sql-comparison-reload",
                "-Dspring.devtools.restart.poll-interval=300ms",
                "-Dspring.devtools.restart.quiet-period=100ms",
                "-Dspring.devtools.livereload.enabled=false",
                "@" + args,
                DevToolsRestartApplication.class.getName(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.profiles.active=dev",
                "--bootui.show-banner=false",
                "--bootui.mcp.enabled=ON",
                "--bootui.runtime-journal.baseline-file=" + directory.resolve("baseline.bin"),
                "--bootui.overrides-file=" + directory.resolve("overrides.properties"),
                "--management.tracing.export.enabled=false"));
        Files.deleteIfExists(directory.resolve("baseline.bin"));
        process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        Process started = process;
        Runtime.getRuntime().addShutdownHook(new Thread(started::destroyForcibly, "stop-sql-comparison-sample"));
        probe = new BootUiHttpProbe("http://127.0.0.1:" + port);
    }

    private JsonNode ready() {
        try {
            if (Files.readString(log)
                            .lines()
                            .filter(line -> line.startsWith("RESTART_RUN="))
                            .count()
                    < readyRuns) {
                return null;
            }
            JsonNode status = journal();
            JsonNode inventory = probe.get("/bootui/api/code-inventory").json();
            return !status.path("runId").asText().isBlank()
                            && "COMPLETE"
                                    .equals(inventory
                                            .path("scan")
                                            .path("status")
                                            .asText())
                            && "ARMED"
                                    .equals(probe.get("/bootui/api/java-agent")
                                            .json()
                                            .path("state")
                                            .asText())
                    ? status
                    : null;
        } catch (IOException | IllegalStateException starting) {
            return null;
        }
    }

    private void assertSensors() {
        JsonNode report = probe.get("/bootui/api/java-agent").json();
        for (String required : List.of("executors", "inventory", "code-paths")) {
            assertThat(report.path("sensors")).anySatisfy(sensor -> {
                assertThat(sensor.path("id").asText()).isEqualTo(required);
                assertThat(sensor.path("state").asText()).isEqualTo("installed");
                assertThat(sensor.path("selfTestPassed").asBoolean()).isTrue();
            });
        }
    }

    private String workload() throws Exception {
        String original = null;
        for (int i = 0; i < 6; i++) {
            var response = probe.get(ORIGINAL);
            var control = probe.get(ORIGINAL + "/joined");
            assertThat(response.status()).isEqualTo(200);
            assertThat(control.status()).isEqualTo(200);
            assertThat(response.json()).hasSize(16);
            assertThat(response.body()).isEqualTo(control.body());
            original = response.body();
        }
        String response = original;
        await("the request queue is drained", () -> journal().path("queueDepth").asInt() == 0 ? journal() : null);
        Files.writeString(
                directory.resolve("response-" + journal().path("runId").asText() + ".json"), response);
        return original;
    }

    private void assertRequestSql(int expected) throws Exception {
        JsonNode profile = await("a retained original request profile", () -> {
            JsonNode feed = probe.get("/bootui/api/activity?source=journal&type=REQUEST&limit=100")
                    .json();
            for (JsonNode entry : feed.path("entries")) {
                if (ORIGINAL.equals(entry.path("path").asText())) {
                    String id = entry.path("id").asText();
                    if (!id.isBlank()) {
                        JsonNode result = probe.get("/bootui/api/activity/request/" + id + "/journal")
                                .json();
                        if (result.path("available").asBoolean()) {
                            return result;
                        }
                    }
                }
            }
            return null;
        });
        long statements = 0;
        for (JsonNode item : profile.path("timeline")) {
            if ("sql".equals(item.path("source").asText())) {
                statements++;
            }
        }
        assertThat(statements).as(profile.toString()).isEqualTo(expected);
        save("profile-" + expected + ".json", profile);
    }

    private JsonNode changedMethod(String status) {
        JsonNode changes = probe.get(COMPARISON).json().path("codeChanges");
        for (JsonNode method : changes.path("methods")) {
            if ("listWithSecondarySelects".equals(method.path("name").asText())
                    && status.equals(method.path("status").asText())) {
                assertThat(changes.path("counts").path("changed").asInt()).isEqualTo(1);
                return method;
            }
        }
        return null;
    }

    private static boolean hasSqlReduction(JsonNode comparison) {
        for (JsonNode row : comparison.path("behavior")) {
            if ("statements-per-request".equals(row.path("kind").asText())
                    && ("GET " + ORIGINAL).equals(row.path("subject").asText())
                    && row.path("before").asDouble() == 17
                    && row.path("after").asDouble() == 1) {
                return true;
            }
        }
        return false;
    }

    private static void assertComparison(JsonNode comparison) {
        assertThat(comparison.path("status").asText()).isEqualTo("COMPARED");
        assertThat(hasSqlReduction(comparison)).as(comparison.toString()).isTrue();
        assertThat(comparison.path("behavior")).anySatisfy(row -> {
            assertThat(row.path("kind").asText()).isEqualTo("gone-statement");
            assertThat(row.path("subject").asText()).isEqualTo("GET " + ORIGINAL);
            assertThat(row.path("detail").asText()).contains("insight_eager_customers");
        });
        assertThat(comparison.path("limitations"))
                .noneMatch(limit -> limit.asText().contains("sql source missed"));
    }

    private JsonNode journal() {
        var response = probe.get("/bootui/api/activity/journal");
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }

    private JsonNode await(String description, Supplier<JsonNode> observation) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        do {
            assertThat(process.isAlive()).as(tail()).isTrue();
            JsonNode result = observation.get();
            if (result != null) {
                return result;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(description + ": " + tail());
    }

    private String tail() throws IOException {
        List<String> lines = Files.readAllLines(log);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 50), lines.size()));
    }

    private void save(String name, JsonNode json) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve(name).toFile(), json);
    }

    private static void replaceFinder(Path service) throws IOException {
        AtomicInteger replacements = new AtomicInteger();
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(Files.readAllBytes(service))
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                MethodVisitor delegate =
                                        super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!"listWithSecondarySelects".equals(name)) {
                                    return delegate;
                                }
                                return new MethodVisitor(Opcodes.ASM9, delegate) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        if ((SERVICE.substring(0, SERVICE.lastIndexOf('/') + 1)
                                                                + "EagerDemoOrderRepository")
                                                        .equals(owner)
                                                && "findWithSecondarySelects".equals(name)) {
                                            name = "findWithCustomerJoin";
                                            replacements.incrementAndGet();
                                        }
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                    }
                                };
                            }
                        },
                        0);
        assertThat(replacements.get()).isEqualTo(1);
        Files.write(service, writer.toByteArray());
    }
}
