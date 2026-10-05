package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the side-effect sensors share on a thread and in a generation: which hooks a hook open on the same thread
 * silences, the string table's rooms, and the error budget a scope's capture is charged to.
 */
class SideEffectsSharingTests {

    private static final int ALL = SideEffects.MASK_PROCESSES
            | SideEffects.MASK_NETWORK
            | SideEffects.MASK_FILES
            | SideEffects.MASK_ENVIRONMENT;

    private final List<Object> keep = new ArrayList<>();
    private final AtomicBoolean captureFails = new AtomicBoolean();

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

    // ---- hooks open on the same thread ---------------------------------------------------------------------------

    @Test
    void aConnectInsideAFileOperationStillRecords() {
        long token = claimAll();

        long file = SideEffects.fileOpening(SideEffects.HOOK_COPY);
        assertThat(file).isNotZero();
        // A file system provider's connect, as an S3, GCS, or SFTP Path's, or Files.copy(InputStream, Path)'s stream.
        long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        assertThat(connect).as("network records inside a files hook").isNotZero();
        SideEffects.connected(
                connect,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                InetSocketAddress.createUnresolved("bucket.example", 443),
                true,
                null);
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_NEW_INPUT_STREAM))
                .as("still inside the files hook: a nested file operation is the outer one's")
                .isZero();
        long process = SideEffects.processStarting();
        assertThat(process)
                .as("a process started inside a file operation records")
                .isNotZero();
        SideEffects.processStarted(process, List.of("sh"), null, new java.io.IOException("not started"));
        SideEffects.pathsUsed(
                file,
                SideEffects.HOOK_COPY,
                SideEffects.KIND_FILE_COPY_FROM,
                null,
                SideEffects.KIND_FILE_COPY_TO,
                null,
                null);

        assertThat(drain(token))
                .extracting(record -> (int) record[SideEffects.R_SENSOR])
                .contains(SideEffects.SENSOR_NETWORK, SideEffects.SENSOR_PROCESSES);
        CodePaths.Frame frame = CodePaths.FRAME.get();
        assertThat(frame.sideEffectOpen).as("every hook closed its own bit").isZero();
    }

    @Test
    void aFileOrPropertyReadInsideANetworkHookIsTheJdksAndNeverRecords() {
        long token = claimAll();

        long lookup = SideEffects.networkStarting(SideEffects.HOOK_LOOKUP);
        assertThat(lookup).isNotZero();
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM))
                .as("the hosts file the resolver reads")
                .isZero();
        SideEffects.environmentRead(
                SideEffects.HOOK_GET_PROPERTY, SideEffects.KIND_SYSTEM_PROPERTY, "sun.net.inetaddr.ttl");
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_DATAGRAM_SOCKET_SEND))
                .as("a datagram the resolver sends")
                .isZero();
        assertThat(SideEffects.processStarting())
                .as("a process inside a network hook")
                .isZero();
        SideEffects.lookedUp(lookup, "db", new java.net.InetAddress[0], null);

        assertThat(recorded(SideEffects.ENVIRONMENT)).isZero();
        assertThat(drain(token))
                .extracting(record -> (int) record[SideEffects.R_SENSOR])
                .containsOnly(SideEffects.SENSOR_NETWORK);
        assertThat(CodePaths.FRAME.get().sideEffectOpen).isZero();
    }

    @Test
    void aConnectInsideAProcessStartNeverRecordsAndAPropertyReadInsideAFileOperationNeither() {
        long token = claimAll();

        long process = SideEffects.processStarting();
        assertThat(process).isNotZero();
        assertThat(SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT)).isZero();
        SideEffects.processStarted(process, List.of("sh"), null, new java.io.IOException("not started"));

        long file = SideEffects.fileOpening(SideEffects.HOOK_DELETE);
        SideEffects.environmentRead(SideEffects.HOOK_GET_PROPERTY, SideEffects.KIND_SYSTEM_PROPERTY, "file.encoding");
        SideEffects.pathUsed(file, SideEffects.HOOK_DELETE, SideEffects.KIND_FILE_DELETE, null, null);

        assertThat(recorded(SideEffects.ENVIRONMENT)).isZero();
        drain(token);
        assertThat(CodePaths.FRAME.get().sideEffectOpen).isZero();
    }

    @Test
    void aStaleFilesHookNoLongerSilencesTheThread() {
        claimAll();

        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE)).isNotZero();
        // Its exit never ran.
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE)).isZero();
        CodePaths.Frame frame = CodePaths.FRAME.get();
        frame.sideEffectSince -= SideEffects.STALE_DEPTH_NANOS + 1;

        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE)).isNotZero();
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_FILES);
    }

    // ---- the blocking sensor's hooks on an event loop (M5-5c) -----------------------------------------------------

    @Test
    void aParkInsideAnOpenNetworkOrFilesHookIsThatHooksAndNeverRecords() {
        claimAllOnAnEventLoop();
        CodePaths.Frame frame = CodePaths.frame();

        for (int open : new int[] {SideEffects.MASK_NETWORK, SideEffects.MASK_FILES}) {
            frame.sideEffectOpen = open;
            frame.sideEffectSince = System.nanoTime();
            assertThat(Blocking.starting(SideEffects.HOOK_PARK))
                    .as("a park inside an open hook of mask %s", open)
                    .isZero();
            assertThat(frame.sideEffectOpen).isEqualTo(open);
        }
        frame.sideEffectOpen = 0;
        long park = Blocking.starting(SideEffects.HOOK_PARK);
        assertThat(park).as("outside every hook, a park on the loop records").isNotZero();
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_BLOCKING);
        Blocking.done(park, Blocking.KIND_PARK, SideEffects.HOOK_PARK, null);
        assertThat(frame.sideEffectOpen).isZero();
    }

    @Test
    void aFileOperationInsideAnOpenBlockingHookIsSilencedWhileAConnectStillRecords() {
        claimAllOnAnEventLoop();
        CodePaths.Frame frame = CodePaths.frame();

        long sleep = Blocking.starting(SideEffects.HOOK_SLEEP);
        assertThat(sleep).isNotZero();
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_FILE_INPUT_STREAM)).isZero();
        long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        assertThat(connect).as("network records inside a blocking hook").isNotZero();
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_BLOCKING | SideEffects.MASK_NETWORK);
        SideEffects.connected(
                connect,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                InetSocketAddress.createUnresolved("db.example", 5432),
                true,
                null);
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_BLOCKING);
        Blocking.done(sleep, Blocking.KIND_SLEEP, SideEffects.HOOK_SLEEP, null);
        assertThat(frame.sideEffectOpen).isZero();
    }

    @Test
    void aStaleBlockingHookNoLongerSilencesTheThread() {
        claimAllOnAnEventLoop();
        CodePaths.Frame frame = CodePaths.frame();

        assertThat(Blocking.starting(SideEffects.HOOK_PARK)).isNotZero();
        // Its exit never ran.
        assertThat(Blocking.starting(SideEffects.HOOK_PARK)).isZero();
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE)).isZero();
        frame.sideEffectSince -= SideEffects.STALE_DEPTH_NANOS + 1;

        assertThat(Blocking.starting(SideEffects.HOOK_PARK)).isNotZero();
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_BLOCKING);
        frame.sideEffectSince -= SideEffects.STALE_DEPTH_NANOS + 1;
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE))
                .as("a files hook recovers from a stale blocking hook too")
                .isNotZero();
        assertThat(frame.sideEffectOpen).isEqualTo(SideEffects.MASK_FILES);
    }

    // ---- the string table's rooms --------------------------------------------------------------------------------

    @Test
    void manyPathsNamesAndFramesNeverLeaveTheNetworkTargetsOrTheThreadNamesWithoutRoom() {
        claimAll();

        int files = intern(
                SideEffects.FILES_INTERN_QUOTA, i -> SideEffects.internQuota("/data/" + i, SideEffects.SENSOR_FILES));
        int names = intern(
                SideEffects.ENVIRONMENT_INTERN_QUOTA,
                i -> SideEffects.internQuota("NAME_" + i, SideEffects.SENSOR_ENVIRONMENT));
        int frames = intern(SideEffects.MAX_FRAME_IDS, i -> SideEffects.internFrame("com.example.C" + i, "run"));

        assertThat(files).isEqualTo(SideEffects.FILES_INTERN_QUOTA);
        assertThat(names).isEqualTo(SideEffects.ENVIRONMENT_INTERN_QUOTA);
        assertThat(frames)
                .as("frames borrow only what the other rooms' guarantees and the reserve leave")
                .isLessThan(SideEffects.MAX_FRAME_IDS)
                .isGreaterThanOrEqualTo(SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_FRAMES]);
        assertThat(refused("frames")).isPositive();

        assertTargetsAndThreadNamesStillIntern();
    }

    @Test
    void clearingTheRecordingResetsTheQuotasButNeverTheRoomsTheTableStillHolds() {
        claimAll();

        int files = 0;
        for (int round = 0; round < 3; round++) {
            int from = round * SideEffects.FILES_INTERN_QUOTA;
            files += intern(
                    SideEffects.FILES_INTERN_QUOTA,
                    i -> SideEffects.internQuota("/data/" + (from + i), SideEffects.SENSOR_FILES));
            SideEffects.recordingCleared(generation());
        }

        assertThat(files)
                .as("past its quota again after each clear, but never past the table's room")
                .isGreaterThan(SideEffects.FILES_INTERN_QUOTA)
                .isLessThan(3 * SideEffects.FILES_INTERN_QUOTA);
        assertThat(refused("files")).isPositive();
        assertThat(intern(
                        SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_ENVIRONMENT],
                        i -> SideEffects.internQuota("NAME_" + i, SideEffects.SENSOR_ENVIRONMENT)))
                .as("the environment's guarantee")
                .isEqualTo(SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_ENVIRONMENT]);
        assertThat(intern(
                        SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_FRAMES],
                        i -> SideEffects.internFrame("com.example.C" + i, "run")))
                .as("the frames' guarantee")
                .isEqualTo(SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_FRAMES]);
        assertTargetsAndThreadNamesStillIntern();
    }

    @Test
    void manyUnownedThreadNamesNeverTakeTheCommandNamesRoom() {
        claimAll();

        int threads = intern(SideEffects.DEFAULT_INTERNS, i -> SideEffects.threadName("Thread-" + i));

        assertThat(threads).isEqualTo(SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_THREADS]);
        assertThat(refused("threads")).isPositive();
        assertThat(string(SideEffects.intern("(other hosts)"))).isEqualTo(SideEffects.OTHER_HOSTS);
        int commands = SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_OTHER] - 64;
        assertThat(intern(commands, i -> SideEffects.intern("command-" + i))).isEqualTo(commands);
    }

    @Test
    void manyThreadNamesNeverLeaveTheFilesWithoutTheirGuaranteedRoom() {
        claimAll();

        int names = intern(SideEffects.DEFAULT_INTERNS, i -> SideEffects.intern("pool-" + i + "-thread-1"));

        assertThat(names).isLessThan(SideEffects.DEFAULT_INTERNS);
        assertThat(refused("other")).isPositive();
        int files = SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_FILES];
        assertThat(intern(files, i -> SideEffects.internQuota("/data/" + i, SideEffects.SENSOR_FILES)))
                .isEqualTo(files);
        assertTargetsAndThreadNamesStillInternWithin();
    }

    @Test
    void aStaleFilesHookIsRecoveredEvenWhenNetworkHooksKeepOpeningOnTheThread() {
        long token = claimAll();

        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE)).isNotZero();
        // Its exit never ran; connects keep happening on the thread.
        CodePaths.Frame frame = CodePaths.FRAME.get();
        frame.sideEffectSince -= SideEffects.STALE_DEPTH_NANOS - 1_000_000_000L;
        long connect = SideEffects.networkStarting(SideEffects.HOOK_SOCKET_CONNECT);
        assertThat(connect).isNotZero();
        SideEffects.connected(
                connect,
                SideEffects.HOOK_SOCKET_CONNECT,
                null,
                InetSocketAddress.createUnresolved("db", 5432),
                true,
                null);
        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE))
                .as("the files hook is still open, not stale yet")
                .isZero();
        frame.sideEffectSince -= 2_000_000_000L;

        assertThat(SideEffects.fileOpening(SideEffects.HOOK_DELETE))
                .as("a connect inside never made the open files hook look newer")
                .isNotZero();
        drain(token);
    }

    // ---- error budgets -------------------------------------------------------------------------------------------

    @Test
    void aScopesFailedCaptureIsChargedToTheFilesSensorNotToEverySensor() {
        claimAll();
        captureFails.set(true);

        SideEffects.scopeBegin(null, false);
        SideEffects.scopeEnd();
        captureFails.set(false);

        assertThat(SideEffects.status(SideEffects.FILES)).containsEntry("errors", 1L);
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("errors", 0L);
        assertThat(SideEffects.status(SideEffects.PROCESSES)).containsEntry("errors", 0L);
    }

    @Test
    void withoutFilesAScopesFailedCaptureIsChargedToTheEnvironmentSensor() {
        claimAll();
        SideEffects.disable(SideEffects.MASK_FILES, "test");
        captureFails.set(true);

        SideEffects.scopeBegin(null, false);
        SideEffects.scopeEnd();
        captureFails.set(false);

        assertThat(SideEffects.status(SideEffects.ENVIRONMENT)).containsEntry("errors", 1L);
        assertThat(SideEffects.status(SideEffects.NETWORK)).containsEntry("errors", 0L);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void assertTargetsAndThreadNamesStillInternWithin() {
        for (int i = 0; i < SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_TARGETS]; i++) {
            assertThat(string(SideEffects.networkTarget("host-" + i + ":443"))).isEqualTo("host-" + i + ":443");
        }
        int frames = SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_FRAMES];
        assertThat(intern(frames, i -> SideEffects.internFrame("com.example.C" + i, "run")))
                .isEqualTo(frames);
    }

    private void assertTargetsAndThreadNamesStillIntern() {
        int guaranteed = SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_TARGETS];
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < guaranteed; i++) {
            targets.add(string(SideEffects.networkTarget("host-" + i + ":443")));
        }
        assertThat(targets)
                .as("every guaranteed target keeps its own name")
                .doesNotContainNull()
                .doesNotContain(SideEffects.OTHER_HOSTS);
        for (int i = 0; i < SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_LOOKUPS]; i++) {
            assertThat(string(SideEffects.lookupTarget("name-" + i))).isEqualTo("name-" + i);
        }
        // The strings no quota bounds, as thread names, keep their room, less the few the claim interned.
        int other = SideEffects.ROOM_GUARANTEED[SideEffects.ROOM_OTHER] - 64;
        assertThat(intern(other, i -> SideEffects.intern("thread-" + i))).isEqualTo(other);
    }

    private static int intern(int count, java.util.function.IntUnaryOperator intern) {
        int interned = 0;
        for (int i = 0; i < count; i++) {
            if (intern.applyAsInt(i) != 0) {
                interned++;
            }
        }
        return interned;
    }

    @SuppressWarnings("unchecked")
    private static long refused(String room) {
        return (Long)
                ((Map<String, Object>) SideEffects.status(SideEffects.FILES).get("internRoomRefused")).get(room);
    }

    @SuppressWarnings("unchecked")
    private static long recorded(String sensor) {
        long total = 0;
        for (Object count : ((Map<String, Object>) SideEffects.status(sensor).get("recorded")).values()) {
            total += (Long) count;
        }
        return total;
    }

    /** Every side-effect sensor, blocking included, with the test's thread registered as an event loop. */
    private long claimAllOnAnEventLoop() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put(
                "sensors",
                List.of(
                        SideEffects.PROCESSES,
                        SideEffects.NETWORK,
                        SideEffects.FILES,
                        SideEffects.ENVIRONMENT,
                        SideEffects.BLOCKING));
        Supplier<Object> capture = () -> null;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        SideEffects.enable(ALL | SideEffects.MASK_BLOCKING);
        Blocking.registerEventLoop();
        assertThat(SideEffects.mask & SideEffects.MASK_LOOPS).isNotZero();
        return (Long) result.get("token");
    }

    private long claimAll() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "shop");
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put(
                "sensors",
                List.of(SideEffects.PROCESSES, SideEffects.NETWORK, SideEffects.FILES, SideEffects.ENVIRONMENT));
        Supplier<Object> capture = () -> {
            if (captureFails.get()) {
                throw new IllegalStateException("capture failed");
            }
            return null;
        };
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        SideEffects.enable(ALL);
        return (Long) result.get("token");
    }

    private static long generation() {
        return (Long) ((Map<?, ?>) AgentBridge.status().get("claim")).get("generation");
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
