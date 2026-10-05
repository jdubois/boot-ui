package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.DevToolsException;
import io.github.jdubois.bootui.core.dto.DevToolsActionResult;
import io.github.jdubois.bootui.core.dto.DevToolsStatus;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

/**
 * Behavioural tests for the DevTools bridge, which drives the two BootUI actions that change a running
 * application: scheduling a DevTools restart and pushing a LiveReload command to connected browsers.
 *
 * <p>{@code spring-boot-devtools} is deliberately not a dependency of this module — the bridge reaches it
 * reflectively by class name and must degrade to a clear "unavailable" state when it is absent. To exercise
 * the present-and-working half of that contract without adding the dependency (which would change
 * classpath-conditional behaviour for every other test in this module), the fixtures below are compiled into
 * a temporary directory under the real DevTools class names and handed to the bridge through the application
 * context's class loader.</p>
 */
class DefaultDevToolsBridgeTests {

    private static final String RESTARTER_SOURCE = """
            package org.springframework.boot.devtools.restart;

            public class Restarter {
                private static final Restarter INSTANCE = new Restarter();
                public static volatile boolean failOnGetInstance;
                public static volatile boolean failOnRestart;
                public static volatile int restartCount;

                public static Restarter getInstance() {
                    if (failOnGetInstance) {
                        throw new IllegalStateException("Restarter has not been initialized");
                    }
                    return INSTANCE;
                }

                public void restart() {
                    if (failOnRestart) {
                        throw new IllegalStateException("restart blew up");
                    }
                    restartCount++;
                }
            }
            """;

    private static final String LIVE_RELOAD_SERVER_SOURCE = """
            package org.springframework.boot.devtools.livereload;

            import java.util.ArrayList;
            import java.util.List;

            public class LiveReloadServer {
                public final List<Object> connections = new ArrayList<>();
                public static volatile int triggerCount;
                public static volatile boolean failOnTrigger;

                public int getPort() {
                    return 35729;
                }

                public void triggerReload() {
                    if (failOnTrigger) {
                        throw new IllegalStateException("livereload blew up");
                    }
                    triggerCount++;
                }
            }
            """;

    private static final String OPTIONAL_SERVER_SOURCE = """
            package org.springframework.boot.devtools.autoconfigure;

            import org.springframework.boot.devtools.livereload.LiveReloadServer;

            public class OptionalLiveReloadServer {
                private final LiveReloadServer server;

                public OptionalLiveReloadServer(LiveReloadServer server) {
                    this.server = server;
                }

                public void triggerReload() {
                    if (server != null) {
                        server.triggerReload();
                    }
                }
            }
            """;

    @TempDir
    static Path compiledClasses;

    private static ClassLoader devToolsClassLoader;

    private final List<DefaultDevToolsBridge> bridges = new ArrayList<>();

    @BeforeAll
    static void compileDevToolsStubs() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertThat(compiler)
                .as("a JDK (not a JRE) is required to compile the DevTools stubs")
                .isNotNull();
        Path sources = Files.createDirectories(compiledClasses.resolve("sources"));
        Path classes = Files.createDirectories(compiledClasses.resolve("classes"));
        List<String> files = new ArrayList<>();
        files.add(write(sources, "org/springframework/boot/devtools/restart/Restarter.java", RESTARTER_SOURCE));
        files.add(write(
                sources,
                "org/springframework/boot/devtools/livereload/LiveReloadServer.java",
                LIVE_RELOAD_SERVER_SOURCE));
        files.add(write(
                sources,
                "org/springframework/boot/devtools/autoconfigure/OptionalLiveReloadServer.java",
                OPTIONAL_SERVER_SOURCE));
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        arguments.addAll(files);
        int result = compiler.run(null, null, null, arguments.toArray(String[]::new));
        Assertions.assertThat(result).as("DevTools stub compilation").isZero();
        devToolsClassLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, DefaultDevToolsBridgeTests.class.getClassLoader());
    }

    private static String write(Path root, String relativePath, String source) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toString();
    }

    @AfterEach
    void resetFixtures() throws Exception {
        for (DefaultDevToolsBridge bridge : bridges) {
            bridge.stop();
        }
        bridges.clear();
        setStatic("org.springframework.boot.devtools.restart.Restarter", "failOnGetInstance", false);
        setStatic("org.springframework.boot.devtools.restart.Restarter", "failOnRestart", false);
        setStatic("org.springframework.boot.devtools.restart.Restarter", "restartCount", 0);
        setStatic("org.springframework.boot.devtools.livereload.LiveReloadServer", "triggerCount", 0);
        setStatic("org.springframework.boot.devtools.livereload.LiveReloadServer", "failOnTrigger", false);
    }

    private static void setStatic(String className, String field, Object value) throws Exception {
        Class<?> type = Class.forName(className, true, devToolsClassLoader);
        type.getField(field).set(null, value);
    }

    private static Object staticValue(String className, String field) throws Exception {
        Class<?> type = Class.forName(className, true, devToolsClassLoader);
        return type.getField(field).get(null);
    }

    /** The restart runs on a delayed background thread, so assertions on its effect have to wait for it. */
    private static void awaitUpTo(long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        Assertions.assertThat(condition.getAsBoolean())
                .as("condition was not met within %d ms", millis)
                .isTrue();
    }

    /** A bridge whose class loader can see the compiled DevTools stubs, with the given LiveReload beans. */
    private DefaultDevToolsBridge bridgeWithDevTools(Object optionalServer, Object liveReloadServer) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(devToolsClassLoader);
        when(context.getBeansOfType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Class<?> type = invocation.getArgument(0);
                    if (optionalServer != null && type.isInstance(optionalServer)) {
                        return Map.of("optionalLiveReloadServer", optionalServer);
                    }
                    if (liveReloadServer != null && type.isInstance(liveReloadServer)) {
                        return Map.of("liveReloadServer", liveReloadServer);
                    }
                    return Map.of();
                });
        return register(new DefaultDevToolsBridge(context));
    }

    /** A bridge that cannot see DevTools at all, mirroring a plain application. */
    private DefaultDevToolsBridge bridgeWithoutDevTools() {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(new URLClassLoader(new URL[0], null));
        return register(new DefaultDevToolsBridge(context));
    }

    private DefaultDevToolsBridge register(DefaultDevToolsBridge bridge) {
        bridges.add(bridge);
        return bridge;
    }

    private Object newLiveReloadServer(int connections) throws Exception {
        Class<?> type = Class.forName(
                "org.springframework.boot.devtools.livereload.LiveReloadServer", true, devToolsClassLoader);
        Object server = type.getDeclaredConstructor().newInstance();
        @SuppressWarnings("unchecked")
        List<Object> sockets = (List<Object>) type.getField("connections").get(server);
        for (int i = 0; i < connections; i++) {
            sockets.add(new Object());
        }
        return server;
    }

    private Object newOptionalServer(Object liveReloadServer) throws Exception {
        Class<?> optionalType = Class.forName(
                "org.springframework.boot.devtools.autoconfigure.OptionalLiveReloadServer", true, devToolsClassLoader);
        Class<?> serverType = Class.forName(
                "org.springframework.boot.devtools.livereload.LiveReloadServer", true, devToolsClassLoader);
        return optionalType.getDeclaredConstructor(serverType).newInstance(serverType.cast(liveReloadServer));
    }

    // --- unavailable (no DevTools on the classpath) ---------------------------------------------------

    @Test
    void statusExplainsThatDevToolsIsAbsentInsteadOfFailing() {
        DevToolsStatus status = bridgeWithoutDevTools().status();

        assertThat(status.restartAvailable()).isFalse();
        assertThat(status.restartUnavailableReason()).isEqualTo("spring-boot-devtools is not on the classpath.");
        assertThat(status.restartPending()).isFalse();
        assertThat(status.liveReloadAvailable()).isFalse();
        assertThat(status.liveReloadPort()).isNull();
        assertThat(status.liveReloadConnections()).isZero();
        assertThat(status.liveReloadUnavailableReason())
                .isEqualTo("Spring Boot DevTools LiveReload is not on the classpath.");
    }

    @Test
    void bothActionsRefuseToRunWhenDevToolsIsAbsent() {
        DefaultDevToolsBridge bridge = bridgeWithoutDevTools();

        DevToolsActionResult restart = bridge.scheduleRestart();
        assertThat(restart.action()).isEqualTo("restart");
        assertThat(restart.status()).isEqualTo("unavailable");
        assertThat(restart.message()).isEqualTo("spring-boot-devtools is not on the classpath.");

        DevToolsActionResult liveReload = bridge.triggerLiveReload();
        assertThat(liveReload.action()).isEqualTo("livereload");
        assertThat(liveReload.status()).isEqualTo("unavailable");
        assertThat(liveReload.message()).isEqualTo("Spring Boot DevTools LiveReload is not on the classpath.");
    }

    /**
     * DevTools on the classpath without an initialised Restarter is a normal setup (a packaged jar, for
     * instance). The status read must stay fail-closed and report an honest reason rather than propagating the
     * failure and taking the whole DevTools panel down with it.
     */
    @Test
    void anUninitializedRestarterDegradesToAnUnavailableStatusInsteadOfFailingTheRead() throws Exception {
        setStatic("org.springframework.boot.devtools.restart.Restarter", "failOnGetInstance", true);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, null);

        DevToolsStatus status = bridge.status();
        assertThat(status.restartAvailable()).isFalse();
        assertThat(status.restartUnavailableReason()).isEqualTo("Restarter has not been initialized");

        DevToolsActionResult restart = bridge.scheduleRestart();
        assertThat(restart.action()).isEqualTo("restart");
        assertThat(restart.status()).isEqualTo("unavailable");
        assertThat(restart.message()).isEqualTo("Restarter has not been initialized");
        assertThat(staticValue("org.springframework.boot.devtools.restart.Restarter", "restartCount"))
                .isEqualTo(0);
    }

    /**
     * The other wrapping branch: the Restarter class is visible but its {@code getInstance()} method is not, so
     * the reflective lookup fails. That must also read as an unavailable restart with a usable reason.
     */
    @Test
    void aRestarterWithoutAGetInstanceMethodIsReportedAsUnavailable() throws Exception {
        Path classes = Files.createDirectories(compiledClasses.resolve("broken-classes"));
        Path sources = Files.createDirectories(compiledClasses.resolve("broken-sources"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String source = """
                package org.springframework.boot.devtools.restart;

                public class Restarter {
                }
                """;
        int result = compiler.run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                write(sources, "org/springframework/boot/devtools/restart/Restarter.java", source));
        Assertions.assertThat(result).as("broken Restarter stub compilation").isZero();
        ClassLoader brokenLoader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null);
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(brokenLoader);
        when(context.getBeansOfType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of());

        DevToolsStatus status = register(new DefaultDevToolsBridge(context)).status();

        assertThat(status.restartAvailable()).isFalse();
        assertThat(status.restartUnavailableReason()).isEqualTo("Spring Boot DevTools Restarter is not available.");
    }

    @Test
    void liveReloadIsUnavailableWhenNoServerBeanIsRegistered() {
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, null);

        DevToolsStatus status = bridge.status();

        assertThat(status.liveReloadAvailable()).isFalse();
        assertThat(status.liveReloadUnavailableReason())
                .isEqualTo("Spring Boot DevTools LiveReload server is not available.");
        assertThat(bridge.triggerLiveReload().status()).isEqualTo("unavailable");
    }

    // --- restart --------------------------------------------------------------------------------------

    @Test
    void restartIsScheduledOnceAndActuallyReachesTheRestarter() throws Exception {
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, null);

        DevToolsActionResult scheduled = bridge.scheduleRestart();

        assertThat(scheduled.status()).isEqualTo("scheduled");
        assertThat(scheduled.message()).startsWith("Restart scheduled.");
        assertThat(bridge.status().restartPending()).isTrue();

        DevToolsActionResult second = bridge.scheduleRestart();
        assertThat(second.status()).isEqualTo("already_pending");
        assertThat(second.message()).isEqualTo("A DevTools restart is already pending.");

        awaitUpTo(5_000, () -> {
            try {
                return staticValue("org.springframework.boot.devtools.restart.Restarter", "restartCount")
                        .equals(1);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    @Test
    void aFailedRestartClearsThePendingFlagSoTheUserCanRetry() throws Exception {
        setStatic("org.springframework.boot.devtools.restart.Restarter", "failOnRestart", true);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, null);

        assertThat(bridge.scheduleRestart().status()).isEqualTo("scheduled");

        awaitUpTo(5_000, () -> !bridge.status().restartPending());
        assertThat(bridge.scheduleRestart().status()).isEqualTo("scheduled");
    }

    // --- live reload ----------------------------------------------------------------------------------

    @Test
    void triggeringLiveReloadWithConnectedBrowsersReportsHowManyWereNotified() throws Exception {
        Object server = newLiveReloadServer(2);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(newOptionalServer(server), server);

        DevToolsStatus status = bridge.status();
        assertThat(status.liveReloadAvailable()).isTrue();
        assertThat(status.liveReloadPort()).isEqualTo(35729);
        assertThat(status.liveReloadConnections()).isEqualTo(2);
        assertThat(status.liveReloadUnavailableReason()).isNull();

        DevToolsActionResult result = bridge.triggerLiveReload();

        assertThat(result.status()).isEqualTo("triggered");
        assertThat(result.message()).isEqualTo("LiveReload command sent to 2 connected clients.");
        assertThat(staticValue("org.springframework.boot.devtools.livereload.LiveReloadServer", "triggerCount"))
                .isEqualTo(1);
    }

    @Test
    void oneConnectedClientIsDescribedInTheSingular() throws Exception {
        Object server = newLiveReloadServer(1);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(newOptionalServer(server), server);

        assertThat(bridge.triggerLiveReload().message()).isEqualTo("LiveReload command sent to 1 connected client.");
    }

    @Test
    void triggeringWithNoConnectedBrowsersExplainsWhyNothingReloaded() throws Exception {
        Object server = newLiveReloadServer(0);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(newOptionalServer(server), server);

        DevToolsActionResult result = bridge.triggerLiveReload();

        assertThat(result.status()).isEqualTo("no_clients");
        assertThat(result.message()).contains("no browsers are connected on port 35729");
        assertThat(staticValue("org.springframework.boot.devtools.livereload.LiveReloadServer", "triggerCount"))
                .isEqualTo(1);
    }

    @Test
    void aBareLiveReloadServerBeanIsUsedWhenTheOptionalWrapperIsAbsent() throws Exception {
        Object server = newLiveReloadServer(3);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, server);

        DevToolsStatus status = bridge.status();

        assertThat(status.liveReloadAvailable()).isTrue();
        assertThat(status.liveReloadConnections()).isEqualTo(3);
        assertThat(bridge.triggerLiveReload().status()).isEqualTo("triggered");
    }

    @Test
    void aFailingLiveReloadServerSurfacesAsADevToolsException() throws Exception {
        setStatic("org.springframework.boot.devtools.livereload.LiveReloadServer", "failOnTrigger", true);
        Object server = newLiveReloadServer(1);
        DefaultDevToolsBridge bridge = bridgeWithDevTools(newOptionalServer(server), server);

        Assertions.assertThatThrownBy(bridge::triggerLiveReload)
                .isInstanceOf(DevToolsException.class)
                .hasMessage("Could not trigger Spring Boot DevTools LiveReload")
                .hasRootCauseMessage("livereload blew up");
    }

    @Test
    void stoppingTheBridgeIsIdempotent() {
        DefaultDevToolsBridge bridge = bridgeWithDevTools(null, null);
        AtomicBoolean failed = new AtomicBoolean();

        try {
            bridge.stop();
            bridge.stop();
        } catch (RuntimeException ex) {
            failed.set(true);
        }

        assertThat(failed).isFalse();
    }
}
