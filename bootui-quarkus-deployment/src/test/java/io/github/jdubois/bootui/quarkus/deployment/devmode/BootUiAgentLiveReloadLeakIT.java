package io.github.jdubois.bootui.quarkus.deployment.devmode;

import static org.assertj.core.api.Assertions.assertThat;

import bootuireloadapp.ReloadProbeResource;
import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.jdubois.bootui.agent.HeapWalk;
import io.quarkus.test.QuarkusDevModeTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.SortedSet;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The BootUI agent across real Quarkus live reloads of a minimal application ({@code docs/PLAN-v2.md} M5-1, §5.13's
 * leak risk): run by failsafe, after the agent jar is packaged, in a JVM started with the agent, so BootUI's static-init
 * recorder claims it again at each of ten reloads of the dev-mode application. A heap walk rooted at the agent (its
 * classes' statics, its instances, and its threads) must then reach no earlier run's class loader; the engine's drain
 * thread, which works for the run still going, is the walk's control.
 *
 * <p>Disabled on JDK 27 and later, as {@link BootUiLiveReloadRunIdentityTest} is: the Quarkus LTS platform's dev mode
 * cannot read class files of that version.</p>
 */
@DisabledForJreRange(minVersion = 27)
class BootUiAgentLiveReloadLeakIT {

    private static final int RELOADS = 10;
    private static final String SENTINEL = "bootuireloadapp/ReloadProbeResource";
    private static final String AGENT = "io/github/jdubois/bootui/agent/";

    private static final int PORT = port();

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClass(ReloadProbeResource.class)
                    .addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"), "application.properties"));

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void afterTenLiveReloadsTheAgentReachesNoEarlierRun() throws Exception {
        if (Boolean.getBoolean("bootui.agent.it.required")) {
            assertThat(bridgePresent())
                    .as("failsafe starts this JVM with the agent")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(bridgePresent(), "run by failsafe, whose JVM starts with the agent");
        }
        assertThat(get("/reload-probe")).startsWith("v0 run ");
        for (int reload = 1; reload <= RELOADS; reload++) {
            String from = "\"v" + (reload - 1) + " run \"";
            String to = "\"v" + reload + " run \"";
            TEST.modifySourceFile(ReloadProbeResource.class, source -> source.replace(from, to));
            assertThat(get("/reload-probe")).as("reload %d", reload).startsWith("v" + reload + " run ");
        }
        String answer = get("/reload-probe");
        int current = Integer.parseInt(answer.substring(answer.lastIndexOf(' ') + 1));
        assertThat(current).as("each reload loaded its own copy: %s", answer).isGreaterThan(RELOADS);
        String report = get("/bootui/api/java-agent");
        assertThat(report).as(report).contains("\"state\":\"ARMED\"");
        assertThat(report)
                .as("the bridge's counters: %s", report)
                .containsPattern("\"counters\":\\{[^}]*\"errors\":0[,}]");

        Path dump = Files.createTempDirectory("bootui-live-reload-").resolve("live-reloads.hprof");
        try {
            System.gc();
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
            HeapWalk walk = HeapWalk.read(dump);
            SortedSet<Integer> present = walk.runsPresent(SENTINEL);
            Map<Integer, String> reached = walk.runsReachedByAgent(AGENT, SENTINEL);
            Map<Integer, String> fromDrain = walk.runsReachedFromThreads("bootui-agent-drain", AGENT, SENTINEL);
            int drainThreads = walk.liveThreads("bootui-agent-drain");
            System.out.println("RUNS_IN_HEAP=" + present);
            System.out.println("RUNS_REACHED_BY_AGENT=" + reached.keySet());
            System.out.println("RUNS_REACHED_FROM_THE_ENGINE_DRAIN=" + fromDrain.keySet());

            assertThat(present).as("the run still going is in the heap").contains(current);
            assertThat(drainThreads)
                    .as("the engine drains the agent for the run still going only")
                    .isEqualTo(1);
            assertThat(fromDrain)
                    .as("the walk reaches the run still going from its drain thread")
                    .containsKey(current);
            // The test's own class loader holds an uninitialized copy, numbered -1, which is no run.
            assertThat(reached.keySet())
                    .as("earlier runs the agent strongly reaches: %s", reached)
                    .allMatch(run -> run == current || run < 1);
        } finally {
            delete(dump);
            delete(dump.getParent());
        }
    }

    /** Best effort: on Windows, the walk's mapped buffers keep the dump open until they are collected. */
    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            path.toFile().deleteOnExit();
        }
    }

    private static boolean bridgePresent() {
        try {
            Class.forName("io.github.jdubois.bootui.agent.bridge.AgentBridge", false, null);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + path))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
        return response.body();
    }

    private static int port() {
        String configured = System.getProperty("bootui.it.port");
        if (configured != null && !configured.isBlank()) {
            return Integer.parseInt(configured.trim());
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
