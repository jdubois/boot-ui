package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The network sensor's bridge side (PLAN-v2 §5.16, M5-5b), driven as its delegating advice would drive it: {@code
 * networkStarting} at a hook's entry and {@code connected}, {@code connectFinished}, {@code datagramSent}, or {@code
 * lookedUp} with the token at its exit.
 */
class NetworkSideEffectsTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean captureFails =
            new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void aBlockingConnectRecordsItsHostAndPortTimeClientFrameAndThreadFamily() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));

        long started = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        assertThat(started).isNotZero();
        Thread.sleep(2);
        SideEffects.connected(
                started,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 12}), 5432),
                true,
                null);

        List<long[]> records = drain(token);
        assertThat(records).hasSize(1);
        long[] record = records.get(0);
        assertThat(record[SideEffects.R_SENSOR]).isEqualTo(SideEffects.SENSOR_NETWORK);
        assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_CONNECT);
        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("10.0.0.12:5432");
        assertThat(outcome(record)).isEqualTo(SideEffects.OUTCOME_CONNECTED);
        assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(record[SideEffects.R_NANOS]).isGreaterThan(1_000_000L);
        assertThat(string(record[SideEffects.R_FLAGS] >>> 32))
                .as("the first frame outside the socket plumbing, this bridge's own package being plumbing")
                .startsWith("org.junit.");
        assertThat(string((record[SideEffects.R_FLAGS] >>> 16) & 0xFFFF))
                .isEqualTo(SideEffects.threadFamily(Thread.currentThread().getName()));
    }

    @Test
    void aRefusedConnectRecordsAnIoErrorAndAnUnresolvedOneAnError() {
        long token = enabledClaim();

        long refused = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        SideEffects.connected(
                refused,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                InetSocketAddress.createUnresolved("db", 5432),
                true,
                new ConnectException("refused"));
        long unresolved = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_CONNECT);
        SideEffects.connected(
                unresolved,
                SideEffects.HOOK_CHANNEL_CONNECT,
                new Object(),
                InetSocketAddress.createUnresolved("db", 5432),
                false,
                new java.nio.channels.UnresolvedAddressException());

        List<long[]> records = drain(token);
        assertThat(records)
                .extracting(NetworkSideEffectsTests::outcome)
                .containsExactly(SideEffects.OUTCOME_IO_ERROR, SideEffects.OUTCOME_ERROR);
        assertThat(records)
                .allSatisfy(record ->
                        assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("db:5432"));
    }

    @Test
    void aNonBlockingConnectIsPendingThenItsFinishCarriesItsOwnerAndTimeFromAnotherThread() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        Object channel = new Object();

        long started = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_CONNECT);
        SideEffects.connected(
                started,
                SideEffects.HOOK_CHANNEL_CONNECT,
                channel,
                InetSocketAddress.createUnresolved("cache", 6379),
                false,
                null);
        context.set(null);
        Thread selector = new Thread(() -> {
            long still = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_FINISH_CONNECT);
            SideEffects.connectFinished(still, channel, false, null);
            long done = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_FINISH_CONNECT);
            SideEffects.connectFinished(done, channel, true, null);
            long again = SideEffects.networkStarting(SideEffects.HOOK_CHANNEL_FINISH_CONNECT);
            SideEffects.connectFinished(again, channel, true, null);
        });
        selector.start();
        selector.join();

        List<long[]> records = drain(token);
        assertThat(records).hasSize(2);
        assertThat(outcome(records.get(0))).isEqualTo(SideEffects.OUTCOME_PENDING);
        assertThat(records.get(0)[SideEffects.R_NANOS]).isZero();
        long[] finish = records.get(1);
        assertThat(finish[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_CONNECT_FINISH);
        assertThat(outcome(finish)).isEqualTo(SideEffects.OUTCOME_CONNECTED);
        assertThat(finish[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
        assertThat(finish[SideEffects.R_TARGET]).isEqualTo(records.get(0)[SideEffects.R_TARGET]);
        assertThat(finish[SideEffects.R_FIRST_MILLIS]).isEqualTo(records.get(0)[SideEffects.R_FIRST_MILLIS]);
        assertThat(finish[SideEffects.R_NANOS]).isPositive();
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("pendingConnects", 0);
    }

    @Test
    void targetsNeverCarryUserInformationAndAreSanitizedBracketedAndBounded() {
        assertThat(SideEffects.describe(InetSocketAddress.createUnresolved("user:hunter2@db.internal", 5432)))
                .isEqualTo("db.internal:5432");
        assertThat(SideEffects.describe(InetSocketAddress.createUnresolved("fe80::1%en0", 443)))
                .isEqualTo("[fe80::1?en0]:443");
        assertThat(SideEffects.describe(UnixDomainSocketAddress.of("/var/run/docker.sock")))
                .isEqualTo("unix:/var/run/docker.sock");
        assertThat(SideEffects.describe(InetSocketAddress.createUnresolved("a\nb c", 1)))
                .isEqualTo("a?b?c:1");
        assertThat(SideEffects.describe(null)).isEqualTo("(none)");
        assertThat(SideEffects.hostName("x".repeat(500))).hasSize(SideEffects.MAX_TARGET);
        assertThat(SideEffects.threadFamily("http-nio-8080-exec-17")).isEqualTo("http-nio-{n}-exec-{n}");
        assertThat(SideEffects.threadFamily("")).isEmpty();
    }

    @Test
    void distinctTargetsPastTheQuotaShareOneOtherHostsTarget() {
        long token = enabledClaim();
        for (int i = 0; i < SideEffects.MAX_NETWORK_TARGETS + 3; i++) {
            SideEffects.networkTarget("host-" + i + ":80");
        }

        int other = SideEffects.networkTarget("host-past-the-quota:80");

        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("targets", SideEffects.MAX_NETWORK_TARGETS);
        assertThat(string(other)).isEqualTo(SideEffects.OTHER_HOSTS);
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void aDatagramsFirstSendIsPublishedAtOnceAndTheRestAreCountedInTheThreadsTable() throws Exception {
        long token = enabledClaim();
        // A hot hook reads its owner from the thread's slot only: a capture would name it, but is never made.
        context.set(owner("00000000000000cd"));
        SideEffects.handoff(owner(REQUEST), generation());
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 8125);

        for (int i = 0; i < 4; i++) {
            long started = SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_CHANNEL_SEND);
            SideEffects.datagramSent(started, SideEffects.HOOK_DATAGRAM_CHANNEL_SEND, target, null);
        }
        assertThat(drain(token)).as("the first send, at once").singleElement().satisfies(record -> {
            assertThat(record[SideEffects.R_COUNT]).isEqualTo(1L);
            assertThat((int) record[SideEffects.R_FRAMES]).isZero();
            assertThat(string(record[SideEffects.R_FRAMES] >>> 32)).isNotNull();
        });

        SideEffects.handoffDone();

        List<long[]> rest = drain(token);
        assertThat(rest).singleElement().satisfies(record -> {
            assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_DATAGRAM);
            assertThat(record[SideEffects.R_COUNT]).isEqualTo(3L);
            assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
            assertThat(string(record[SideEffects.R_TARGET])).isEqualTo("127.0.0.1:8125");
            assertThat(string(record[SideEffects.R_FLAGS] >>> 32)).isNotNull();
            assertThat(string((record[SideEffects.R_FLAGS] >>> 16) & 0xFFFF)).isNotNull();
        });
    }

    @Test
    void withoutCodePathsAnAdaptersScopeStillFillsTheSlotADatagramReads() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 8125);

        CodePaths.begin();
        long started = SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND);
        SideEffects.datagramSent(started, SideEffects.HOOK_DATAGRAM_SOCKET_SEND, target, null);
        CodePaths.end();

        assertThat(drain(token))
                .singleElement()
                .satisfies(record -> assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS));
    }

    @Test
    void anUnslottedDatagramIsNeverCapturedAndNamesItsThreadFamily() throws Exception {
        long token = enabledClaim();
        context.set(owner(REQUEST));
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 8125);

        long started = SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND);
        SideEffects.datagramSent(started, SideEffects.HOOK_DATAGRAM_SOCKET_SEND, target, null);

        assertThat(drain(token)).singleElement().satisfies(record -> {
            assertThat(record[SideEffects.R_REQUEST]).isZero();
            assertThat(string((record[SideEffects.R_FLAGS] >>> 16) & 0xFFFF))
                    .isEqualTo(SideEffects.threadFamily(Thread.currentThread().getName()));
        });
    }

    @Test
    void openTelemetryInstrumentationAroundAnApplicationsCallNeverHidesItsHttpClient() {
        enabledClaim();
        Claim claim = AgentBridge.current();

        long[] instrumented = io.opentelemetry.instrumentation.fake.FakeInterceptor.intercept(
                () -> sun.net.www.fake.FakeHttpClient.connect(() -> SideEffects.networkFrames(claim)));
        long[] exported = io.opentelemetry.exporter.fake.FakeExporter.export(
                () -> sun.net.www.fake.FakeHttpClient.connect(() -> SideEffects.networkFrames(claim)));

        assertThat(string(instrumented[1])).startsWith("sun.net.www.fake.FakeHttpClient#connect");
        assertThat(string(exported[1])).startsWith("io.opentelemetry.exporter.fake.FakeExporter#export");
    }

    @Test
    void anInfrastructureFrameIsTheClientOfAConnectMadeThroughATransport() {
        assertThat(SideEffects.infrastructure("io.opentelemetry.exporter.sender.okhttp.OkHttpHttpSender"))
                .isTrue();
        assertThat(SideEffects.infrastructure("io.opentelemetry.sdk.trace.export.BatchSpanProcessor"))
                .isTrue();
        for (String instrumentation : List.of(
                "io.opentelemetry.instrumentation.spring.web.v3_1.RestTemplateInterceptor",
                "io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource",
                "io.opentelemetry.javaagent.instrumentation.okhttp.v3_0.TracingInterceptor",
                "io.opentelemetry.context.Context",
                "io.opentelemetry.api.trace.Span")) {
            assertThat(SideEffects.infrastructure(instrumentation))
                    .as(instrumentation)
                    .isFalse();
        }
        assertThat(SideEffects.infrastructure("org.springframework.boot.docker.compose.lifecycle.X"))
                .isTrue();
        assertThat(SideEffects.infrastructure("okhttp3.internal.connection.RealConnection"))
                .isFalse();
        assertThat(SideEffects.threadFamily("OkHttp http://otel:4318/...")).isEqualTo("OkHttp http://otel:{n}/...");
        assertThat(SideEffects.threadFamily("OkHttp https://alice:hunter2@h/?token=abc"))
                .isEqualTo("OkHttp https://h/");
        assertThat(SideEffects.threadFamily("worker\n@secret")).isEqualTo("worker?");
        assertThat(SideEffects.threadFamily("x".repeat(500))).hasSize(SideEffects.MAX_THREAD_FAMILY);
    }

    @Test
    void aSensorPastItsOwnErrorBudgetIsSwitchedOffAloneAndTheOthersKeepRecording() {
        long token = claim(List.of(SideEffects.PROCESSES, SideEffects.NETWORK));
        SideEffects.enable(SideEffects.MASK_PROCESSES | SideEffects.MASK_NETWORK);
        // Every connect's capture fails, an internal error of the network sensor's own recording.
        captureFails.set(true);
        for (int i = 0; i < SideEffects.MAX_ERRORS; i++) {
            long started = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
            SideEffects.connected(
                    started,
                    SideEffects.HOOK_SOCKET_CONNECT,
                    null,
                    InetSocketAddress.createUnresolved("db", 5432),
                    true,
                    null);
        }
        captureFails.set(false);

        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("off", true);
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT)).isZero();
        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("off", false);
        assertThat(SideEffects.processStarting()).isNotZero();
        drain(token);
    }

    @Test
    void aPacketWithAnAddressButNoPortIsNoTargetAndNeverAnInternalError() throws Exception {
        long token = enabledClaim();
        java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[1], 1);
        packet.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));

        long started = SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND);
        SideEffects.datagramSent(
                started, SideEffects.HOOK_DATAGRAM_SOCKET_SEND, packet, new IllegalArgumentException("port"));

        assertThat(drain(token)).singleElement().satisfies(record -> {
            assertThat(string(record[SideEffects.R_TARGET])).isIn("(other)", "127.0.0.1:0");
            assertThat(outcome(record)).isEqualTo(SideEffects.OUTCOME_ERROR);
        });
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("errors", 0L);
    }

    @Test
    void aDatagramSitesFramesAreRememberedAcrossRequestsAndForgottenWhenTheSensorIsDisabled() throws Exception {
        enabledClaim();
        assertThat(SideEffects.SITE_MASK & CodePaths.pack(7L, 3, 11))
                .isEqualTo(SideEffects.SITE_MASK & CodePaths.pack(8L, 3, 11));
        context.set(owner(REQUEST));
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 8125);
        long started = SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_CHANNEL_SEND);
        SideEffects.datagramSent(started, SideEffects.HOOK_DATAGRAM_CHANNEL_SEND, target, null);
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("datagramMemo", 1);

        SideEffects.disable(SideEffects.MASK_NETWORK, null);

        assertThat(SideEffects.status(SideEffects.NETWORK))
                .containsEntry("datagramMemo", 0)
                .containsEntry("pendingConnects", 0);
    }

    @Test
    void lookedUpNamesHaveTheirOwnBoundApartFromTargets() {
        enabledClaim();
        for (int i = 0; i < SideEffects.MAX_NETWORK_TARGETS; i++) {
            SideEffects.lookupTarget("name-" + i);
        }

        assertThat(string(SideEffects.lookupTarget("one-more"))).isEqualTo(SideEffects.OTHER_HOSTS);
        assertThat(string(SideEffects.networkTarget("db:5432"))).isEqualTo("db:5432");
    }

    @Test
    void aLookupRecordsItsOutcomeAndANullAnswerIsAnError() {
        long token = enabledClaim();

        long resolved = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        SideEffects.lookedUp(resolved, "api.example.com", new InetAddress[0], null);
        long unknown = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        SideEffects.lookedUp(unknown, "missing.example", null, new UnknownHostException("missing.example"));
        long mocked = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        SideEffects.lookedUp(mocked, "mocked.example", null, null);

        List<long[]> records = drain(token);
        assertThat(records)
                .extracting(NetworkSideEffectsTests::outcome)
                .containsExactly(
                        SideEffects.OUTCOME_RESOLVED, SideEffects.OUTCOME_UNKNOWN_HOST, SideEffects.OUTCOME_ERROR);
        assertThat(records)
                .extracting(record -> string(record[SideEffects.R_TARGET]))
                .containsExactly("api.example.com", "missing.example", "mocked.example");
        assertThat(records)
                .allSatisfy(record -> assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_LOOKUP));
    }

    @Test
    void onlyTheOutermostHookRecordsAndAStaleDepthNoLongerSilencesTheThread() {
        long token = enabledClaim();

        long outer = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND))
                .as("a datagram the resolver sends inside the lookup")
                .isZero();
        SideEffects.lookedUp(outer, "db", new InetAddress[0], null);
        assertThat(drain(token)).hasSize(1);

        // An exit that never ran leaves the depth set: a later hook records once it is stale.
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_LOOKUP)).isNotZero();
        CodePaths.Frame frame = CodePaths.FRAME.get();
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_LOOKUP)).isZero();
        frame.sideEffectSince -= SideEffects.STALE_DEPTH_NANOS + 1;
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_LOOKUP)).isNotZero();
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("staleDepths", 1L);
    }

    @Test
    void theSelfTestThreadCountsEachNetworkHookAndRecordsNothing() {
        long token = enabledClaim();
        SideEffects.beginSelfTest();
        long token1 = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        long token2 = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        Map<String, Object> hits = SideEffects.endSelfTest();

        assertThat(token1).isZero();
        assertThat(token2).isZero();
        assertThat(hits)
                .containsEntry("Socket.connect", 1L)
                .containsEntry("InetAddress.lookup", 1L)
                .containsEntry("ProcessBuilder.start", 0L);
        assertThat(drain(token)).isEmpty();
    }

    @Test
    void nothingRecordsWhileOnlyProcessesIsEnabled() throws IOException {
        long token = claim(List.of(SideEffects.PROCESSES, SideEffects.NETWORK));
        SideEffects.enable(SideEffects.MASK_PROCESSES);

        assertThat(SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT)).isZero();
        assertThat(drain(token)).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long enabledClaim() {
        long token = claim(List.of(SideEffects.NETWORK));
        SideEffects.enable(SideEffects.MASK_NETWORK);
        return token;
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = () -> {
            if (captureFails.get()) {
                throw new IllegalStateException("capture failed");
            }
            return context.get();
        };
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }

    private static Object[] owner(String request) {
        return new Object[] {request, null, null, null, null, null, null, 1L, 1L};
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
    }

    private static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private static List<String> interned() {
        String[] strings = SideEffects.interned(generation(), 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
