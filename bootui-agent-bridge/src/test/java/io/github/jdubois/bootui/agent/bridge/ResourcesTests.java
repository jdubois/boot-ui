package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.resources.ReportReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resources sensor through the files sensor's record path (PLAN-v2 §5.16, M5-5g), driven as the advice would drive
 * it: an open recorded with its stream, a close hook's exit, a request's end through the one engine call that feeds
 * thread-activity and resources, and the drain thread's sweep.
 */
class ResourcesTests {

    private static final String REQUEST = "00000000000000ab";
    private static final long REQUEST_BITS = 0xabL;

    @TempDir
    Path directory;

    private final List<Object> keep = new ArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private final AtomicReference<Object[]> context = new AtomicReference<>();

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
    void reset() throws Exception {
        for (AutoCloseable resource : opened) {
            resource.close();
        }
        AgentBridge.reset();
    }

    @Test
    void aStreamLeftOpenPastTheResponseIsReportedWithThreadActivityOff() throws Exception {
        long token = enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        assertThat((SideEffects.mask & SideEffects.MASK_THREADS))
                .as("thread-activity is off")
                .isZero();
        context.set(owner(REQUEST));

        FileInputStream stream = ReportReader.read(() -> open(file("report-1.csv")));
        opened.add(stream);
        // The engine's one request-end call, which the adapters make whichever of the two sensors is on.
        ThreadActivity.requestEnded(generation(), REQUEST_BITS);
        Thread.sleep(Resources.GRACE_NANOS / 1_000_000L + 50L);

        List<long[]> resources = resources(drain(token));
        assertThat(resources).singleElement().satisfies(record -> {
            assertThat(record[SideEffects.R_KIND]).isEqualTo(SideEffects.KIND_RESOURCE_LEFT_OPEN);
            assertThat(record[SideEffects.R_REQUEST]).isEqualTo(REQUEST_BITS);
            int detail = (int) (record[SideEffects.R_FLAGS] >>> 32);
            assertThat(detail & 3).isEqualTo(Resources.ORIGIN_APPLICATION);
            assertThat((detail >>> 4) & 0xF).isEqualTo(Resources.KIND_FILE_INPUT_STREAM);
            assertThat(detail & Resources.DETAIL_FIRST).isNotZero();
            assertThat(string(record[SideEffects.R_TARGET])).endsWith("report-{n}.csv");
        });
    }

    @Test
    void aStreamClosedInFinallyProducesNoRow() throws Exception {
        long token = enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        context.set(owner(REQUEST));

        ReportReader.read(() -> {
            FileInputStream stream = open(file("report-2.csv"));
            try {
                stream.available();
            } finally {
                stream.close();
                // What the close hook's exit does.
                Resources.closed(stream, Resources.HOOK_FILE_INPUT_STREAM_CLOSE);
            }
            return null;
        });
        ThreadActivity.requestEnded(generation(), REQUEST_BITS);
        Thread.sleep(Resources.GRACE_NANOS / 1_000_000L + 50L);

        assertThat(resources(drain(token))).isEmpty();
        assertThat(Resources.TRACKER.size()).isZero();
    }

    @Test
    void aLibraryOpeningForTheApplicationIsTrackedAsTheLibrarys() throws Exception {
        long token = enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        context.set(owner(REQUEST));

        FileInputStream stream = ReportReader.readThroughLibrary(() -> open(file("report-3.csv")));
        opened.add(stream);
        ThreadActivity.requestEnded(generation(), REQUEST_BITS);
        Thread.sleep(Resources.GRACE_NANOS / 1_000_000L + 50L);

        assertThat(resources(drain(token)))
                .singleElement()
                .satisfies(record -> assertThat((int) (record[SideEffects.R_FLAGS] >>> 32) & 3)
                        .isEqualTo(Resources.ORIGIN_LIBRARY));
    }

    @Test
    void anOpenWithoutAnApplicationFrameOrAnOwnerIsNotTracked() throws Exception {
        enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        context.set(owner(REQUEST));
        opened.add(open(file("no-frame.csv")));
        assertThat(Resources.TRACKER.size()).isZero();

        context.set(null);
        opened.add(ReportReader.read(() -> open(file("no-owner.csv"))));
        assertThat(Resources.TRACKER.size()).isZero();
    }

    @Test
    void aKindWhoseCloseHookDidNotPassIsNeverTracked() throws Exception {
        enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        Resources.enableKinds(1 << Resources.KIND_FILE_CHANNEL);
        context.set(owner(REQUEST));

        opened.add(ReportReader.read(() -> open(file("untracked.csv"))));
        assertThat(Resources.TRACKER.size()).isZero();
    }

    @Test
    void aFileChannelOpenedAndClosedThroughItsHookLeavesNothing() throws Exception {
        long token = enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        context.set(owner(REQUEST));
        Path path = file("channel.csv");

        FileChannel channel = ReportReader.read(() -> {
            long token2 = SideEffects.fileOpening(SideEffects.HOOK_FILE_CHANNEL);
            FileChannel opened = FileChannel.open(path, StandardOpenOption.READ);
            SideEffects.channelOpened(
                    token2, SideEffects.HOOK_FILE_CHANNEL, path, Set.of(StandardOpenOption.READ), opened, null);
            return opened;
        });
        assertThat(Resources.TRACKER.size()).isEqualTo(1);
        channel.close();
        Resources.closed(channel, Resources.HOOK_FILE_CHANNEL_CLOSE);
        ThreadActivity.requestEnded(generation(), REQUEST_BITS);
        Thread.sleep(Resources.GRACE_NANOS / 1_000_000L + 50L);

        assertThat(resources(drain(token))).isEmpty();
        assertThat(Resources.TRACKER.size()).isZero();
        assertThat(String.valueOf(SideEffects.status(SideEffects.RESOURCES).get("recorded")))
                .contains("FileChannelImpl.implCloseChannel=1");
    }

    @Test
    void switchingTheSensorOffForgetsWhatItTracked() throws Exception {
        enabledClaim(List.of(SideEffects.FILES, SideEffects.RESOURCES));
        context.set(owner(REQUEST));
        opened.add(ReportReader.read(() -> open(file("switched.csv"))));
        assertThat(Resources.TRACKER.size()).isEqualTo(1);

        SideEffects.disable(SideEffects.MASK_FILES, null);
        assertThat(Resources.TRACKER.size())
                .as("a files switch keeps what resources tracks")
                .isEqualTo(1);

        SideEffects.disable(SideEffects.MASK_RESOURCES, null);
        assertThat(Resources.TRACKER.size()).isZero();
        assertThat(SideEffects.status(SideEffects.RESOURCES)).containsEntry("dropped", 1L);
    }

    @Test
    void theSelfTestThreadIsCountedPerHookAndNothingElse() {
        Resources.beginSelfTest();
        Object resource = new Object();
        Resources.closed(resource, Resources.HOOK_SOCKET_CLOSE);
        Resources.uninterruptible(resource);
        Map<String, Object> hits = Resources.endSelfTest();
        assertThat(hits).containsEntry("Socket.close", 1L).containsEntry("FileChannelImpl.setUninterruptible", 1L);
    }

    @Test
    void theStatusReportsTheSensorBesideSecuritySinks() {
        assertThat(SideEffects.sensorIds()).contains(SideEffects.RESOURCES, SideEffects.SECURITY_SINKS);
        assertThat(SideEffects.bit(SideEffects.RESOURCES)).isEqualTo(SideEffects.MASK_RESOURCES);
        assertThat(SideEffects.status(SideEffects.RESOURCES)).containsKeys("tracked", "leftOpen", "reclaimed");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Opens a stream as the files sensor's advice on {@code FileInputStream.open} would record it. */
    private static FileInputStream open(Path path) throws IOException {
        long token = SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM);
        FileInputStream stream = new FileInputStream(path.toFile());
        SideEffects.fileOpened(
                token, SideEffects.HOOK_FILE_INPUT_STREAM, SideEffects.KIND_FILE_READ, path.toString(), stream, null);
        return stream;
    }

    private Path file(String name) throws IOException {
        return Files.writeString(directory.resolve(name), "x");
    }

    private long enabledClaim(List<String> sensors) {
        long token = claim(sensors);
        SideEffects.warm();
        int bits = 0;
        for (String sensor : sensors) {
            bits |= SideEffects.bit(sensor);
        }
        SideEffects.enable(bits);
        Resources.enableKinds(Resources.ALL_KINDS);
        return token;
    }

    private long claim(List<String> sensors) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        Supplier<Object> capture = context::get;
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

    private static List<long[]> resources(List<long[]> records) {
        return records.stream()
                .filter(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_RESOURCES)
                .toList();
    }

    private static List<long[]> drain(long token) {
        List<long[]> records = new ArrayList<>();
        SideEffects.drain(token, record -> records.add(record.clone()));
        return records;
    }

    private static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation(), (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }
}
