package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resources sensor's tracker (PLAN-v2 §5.16, M5-5g, D46): weak entries found again by identity on a lock-free close
 * path, reports at a request's end and when the collector reclaims a resource never closed, and nothing kept alive.
 */
class ResourceTrackerTests {

    private static final long GENERATION = 7L;
    private static final long REQUEST = 0x42L;

    @TempDir
    Path directory;

    private ResourceTracker tracker;
    private final List<AutoCloseable> opened = new ArrayList<>();

    @BeforeEach
    void create() {
        tracker = new ResourceTracker();
        tracker.accept(true);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable resource : opened) {
            resource.close();
        }
    }

    @Test
    void aResourceClosedBeforeItsRequestEndedIsForgottenWithoutAReport() throws Exception {
        FileInputStream stream = stream();
        track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST);

        stream.close();
        tracker.closed(stream, Resources.KIND_FILE_INPUT_STREAM);
        tracker.ended(REQUEST);

        List<ResourceTracker.Entry> reports = sweepAfterGrace();
        assertThat(reports).isEmpty();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void aResourceStillOpenAfterItsRequestIsReportedOpenThenClosedLate() throws Exception {
        FileInputStream stream = stream();
        track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        tracker.ended(REQUEST);

        List<ResourceTracker.Entry> reports = sweepAfterGrace();
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.reported).isEqualTo(ResourceTracker.LEFT_OPEN);
            assertThat(report.first).isTrue();
            assertThat(report.get()).as("a report never keeps the resource").isNull();
        });

        stream.close();
        tracker.closed(stream, Resources.KIND_FILE_INPUT_STREAM);
        assertThat(sweepAfterGrace()).singleElement().satisfies(report -> {
            assertThat(report.reported).isEqualTo(ResourceTracker.CLOSED_LATE);
            assertThat(report.first).as("the resource is counted once").isFalse();
        });
        assertThat(tracker.size()).isZero();
    }

    @Test
    void aResourceOpenedAfterItsRequestEndedIsNeverReportedOpen() throws Exception {
        FileInputStream before = stream();
        track(before, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        tracker.ended(REQUEST);
        sweepAfterGrace();

        FileInputStream after = stream();
        track(after, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        List<ResourceTracker.Entry> reports = sweepAfterGrace();
        assertThat(reports).isEmpty();
    }

    @Test
    void aResourceReclaimedNeverClosedIsReportedOnceAndNothingKeepsIt() throws Exception {
        Path file = Files.writeString(directory.resolve("leak.txt"), "x");
        WeakReference<Object> reference = trackLeaked(file);

        awaitCollected(reference);
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        for (int i = 0; i < 50 && reports.isEmpty(); i++) {
            System.gc();
            tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, reports);
            Thread.sleep(20);
        }
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.reported).isEqualTo(ResourceTracker.RECLAIMED);
            assertThat(report.first).isTrue();
            assertThat(report.request).isEqualTo(REQUEST);
        });
        assertThat(tracker.size()).isZero();
    }

    @Test
    void aClosedResourceReclaimedLaterIsNeverReportedReclaimed() throws Exception {
        Path file = Files.writeString(directory.resolve("closed.txt"), "x");
        WeakReference<Object> reference = trackClosed(file);

        awaitCollected(reference);
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            System.gc();
            tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, reports);
        }
        assertThat(reports).isEmpty();
    }

    @Test
    void aReclaimOfAKindWhoseCloseAHookMissedIsNotReported() throws Exception {
        Path file = Files.writeString(directory.resolve("missed.txt"), "x");
        WeakReference<Object> reference = trackLeaked(file);
        awaitCollected(reference);
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            System.gc();
            tracker.sweep(GENERATION, System.nanoTime(), 0L, 1 << Resources.KIND_FILE_INPUT_STREAM, reports);
        }
        assertThat(reports).isEmpty();
    }

    @Test
    void aCloseTheHooksMissedIsCaughtAtTheSecondSweepAndItsKindNamed() throws Exception {
        FileInputStream stream = stream();
        track(stream, Resources.KIND_FILE_INPUT_STREAM, 0L);
        // Closed, but the hook never told the tracker.
        stream.close();

        long now = System.nanoTime();
        assertThat(tracker.sweep(GENERATION, now, 0L, 0, new ArrayList<>()))
                .as("a close between its own state change and its hook's exit, or one waiting for blocked threads")
                .isZero();
        assertThat(tracker.sweep(GENERATION, now + ResourceTracker.SUSPECT_NANOS / 2, 0L, 0, new ArrayList<>()))
                .isZero();
        assertThat(tracker.sweep(GENERATION, now + ResourceTracker.SUSPECT_NANOS, 0L, 0, new ArrayList<>()))
                .isEqualTo(1 << Resources.KIND_FILE_INPUT_STREAM);
        assertThat(tracker.closeMissed.sum()).isEqualTo(1L);
    }

    @Test
    void aResourceClosedBeforeItWasTrackedIsMarkedClosed() throws Exception {
        FileInputStream stream = stream();
        stream.close();

        ResourceTracker.Entry entry = track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        assertThat(entry.closed).isTrue();
        assertThat(tracker.closedBeforeTracked.sum()).isEqualTo(1L);
    }

    @Test
    void theSameResourceIsTrackedOnce() throws Exception {
        FileInputStream stream = stream();
        assertThat(track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST)).isNotNull();
        assertThat(track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST)).isNull();
        assertThat(tracker.duplicates.sum()).isEqualTo(1L);
    }

    @Test
    void atTheCapANewOpenIsUntrackedAndTheDrainThreadEvictsReportedOnes() throws Exception {
        List<Object> resources = new ArrayList<>();
        for (int i = 0; i < ResourceTracker.MAX_ENTRIES; i++) {
            Object resource = new Object();
            resources.add(resource);
            assertThat(tracker.track(
                            resource,
                            Resources.KIND_SOCKET,
                            GENERATION,
                            REQUEST,
                            0L,
                            0,
                            1,
                            0,
                            1,
                            0L,
                            0L,
                            Resources.KIND_SOCKET << 4,
                            System.currentTimeMillis()))
                    .isNotNull();
        }
        Object extra = new Object();
        assertThat(track(extra, Resources.KIND_SOCKET, REQUEST)).isNull();
        assertThat(tracker.untracked.sum()).isEqualTo(1L);

        tracker.ended(REQUEST);
        List<ResourceTracker.Entry> reports = sweepAfterGrace();
        assertThat(reports).hasSize(ResourceTracker.MAX_ENTRIES);
        assertThat(tracker.evicted.sum()).isEqualTo(ResourceTracker.MAX_ENTRIES / 8);
        assertThat(tracker.size()).isEqualTo(ResourceTracker.MAX_ENTRIES - ResourceTracker.MAX_ENTRIES / 8);
        resources.clear();
    }

    @Test
    void aNewGenerationForgetsTheEarlierOnesEntries() throws Exception {
        track(stream(), Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        assertThat(tracker.size()).isEqualTo(1);

        tracker.sweep(GENERATION + 1, System.nanoTime(), 0L, 0, new ArrayList<>());
        assertThat(tracker.size()).isZero();
        assertThat(tracker.tracking(Resources.KIND_FILE_INPUT_STREAM)).isFalse();
    }

    @Test
    void anOpenRacingASwitchOffIsNeverKeptAndAnEarlierGenerationsOpenNeverInserted() throws Exception {
        FileInputStream stream = stream();
        tracker.clear();
        assertThat(track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST))
                .as("cleared: refused until the close hooks are enabled again")
                .isNull();

        tracker.accept(true);
        assertThat(track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST)).isNotNull();
        assertThat(tracker.track(
                        stream(),
                        Resources.KIND_FILE_INPUT_STREAM,
                        GENERATION - 1,
                        REQUEST,
                        0L,
                        0,
                        1,
                        0,
                        1,
                        0L,
                        0L,
                        0,
                        System.currentTimeMillis()))
                .isNull();
        assertThat(tracker.size()).isEqualTo(1);
    }

    @Test
    void aResourceClosedAfterItsRequestAndCollectedBeforeASweepIsReportedClosedLate() throws Exception {
        Path file = Files.writeString(directory.resolve("late.txt"), "x");
        WeakReference<Object> reference = trackOpenThenClose(file);

        awaitCollected(reference);
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        for (int i = 0; i < 50 && reports.isEmpty(); i++) {
            System.gc();
            tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, reports);
            Thread.sleep(20);
        }
        assertThat(reports)
                .singleElement()
                .satisfies(report -> assertThat(report.reported).isEqualTo(ResourceTracker.CLOSED_LATE));
    }

    @Test
    void clearCountsWhatWasTrackedAsDropped() throws Exception {
        track(stream(), Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        tracker.clear();
        assertThat(tracker.size()).isZero();
        assertThat(tracker.dropped.sum()).isEqualTo(1L);
    }

    @Test
    void closesRacingUnlinksAlwaysFindTheirLiveEntry() throws Exception {
        int count = 512;
        List<Object> live = new ArrayList<>();
        List<Object> churn = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object resource = new Object();
            live.add(resource);
            track(resource, Resources.KIND_SOCKET, 0L);
        }
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread unlinker = new Thread(() -> {
            started.countDown();
            while (!done.get()) {
                Object resource = new Object();
                churn.add(resource);
                ResourceTracker.Entry entry = track(resource, Resources.KIND_SOCKET, 0L);
                if (entry != null) {
                    entry.closed = true;
                }
                tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, new ArrayList<>());
                if (churn.size() > 64) {
                    churn.clear();
                }
            }
        });
        unlinker.start();
        started.await();
        try {
            for (Object resource : live) {
                tracker.closed(resource, Resources.KIND_SOCKET);
            }
        } finally {
            done.set(true);
            unlinker.join();
        }
        tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, new ArrayList<>());
        assertThat(tracker.size())
                .as("every live entry was found and closed, then forgotten")
                .isZero();
    }

    @Test
    void neitherALeakedResourceNorTheClassLoaderOfItsClassIsKeptAlive() throws Exception {
        WeakReference<ClassLoader> loader = trackStreamOfItsOwnLoader();

        awaitCollected(loader);
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        for (int i = 0; i < 50 && reports.isEmpty(); i++) {
            System.gc();
            tracker.sweep(GENERATION, System.nanoTime(), 0L, 0, reports);
            Thread.sleep(20);
        }
        assertThat(reports)
                .singleElement()
                .satisfies(report -> assertThat(report.reported).isEqualTo(ResourceTracker.RECLAIMED));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /**
     * A stream whose class, a {@code FileInputStream} subclass, is defined by a class loader of its own, tracked, then
     * dropped, never closed: the stream keeps its class and loader reachable, so the loader is collected only if the
     * tracker holds the stream weakly.
     */
    private WeakReference<ClassLoader> trackStreamOfItsOwnLoader() throws Exception {
        ClassLoader child = new IsolatingLoader(getClass().getClassLoader(), "com.example.resources.LeakyStream");
        Class<?> type = Class.forName("com.example.resources.LeakyStream", true, child);
        assertThat(type.getClassLoader()).isSameAs(child);
        Path file = Files.writeString(directory.resolve("held.txt"), "x");
        Object stream = type.getConstructor(java.io.File.class).newInstance(file.toFile());
        assertThat(track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST)).isNotNull();
        return new WeakReference<>(child);
    }

    /** Defines one class itself, from its parent's class file, and delegates every other. */
    private static final class IsolatingLoader extends ClassLoader {

        private final String isolated;

        IsolatingLoader(ClassLoader parent, String isolated) {
            super(parent);
            this.isolated = isolated;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isolated.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (java.io.InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException ex) {
                        throw new ClassNotFoundException(name, ex);
                    }
                }
                return loaded;
            }
        }
    }

    /** Reported open after its request, then closed and dropped before the next sweep. */
    private WeakReference<Object> trackOpenThenClose(Path file) throws IOException {
        FileInputStream stream = new FileInputStream(file.toFile());
        track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        tracker.ended(REQUEST);
        assertThat(sweepAfterGrace())
                .singleElement()
                .satisfies(report -> assertThat(report.reported).isEqualTo(ResourceTracker.LEFT_OPEN));
        stream.close();
        tracker.closed(stream, Resources.KIND_FILE_INPUT_STREAM);
        return new WeakReference<>(stream);
    }

    private WeakReference<Object> trackLeaked(Path file) throws IOException {
        FileInputStream stream = new FileInputStream(file.toFile());
        track(stream, Resources.KIND_FILE_INPUT_STREAM, REQUEST);
        return new WeakReference<>(stream);
    }

    private WeakReference<Object> trackClosed(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        track(channel, Resources.KIND_FILE_CHANNEL, REQUEST);
        channel.close();
        tracker.closed(channel, Resources.KIND_FILE_CHANNEL);
        return new WeakReference<>(channel);
    }

    private FileInputStream stream() throws IOException {
        Path file = Files.createTempFile(directory, "stream", ".txt");
        FileInputStream stream = new FileInputStream(file.toFile());
        opened.add(stream);
        return stream;
    }

    private ResourceTracker.Entry track(Object resource, int kind, long request) {
        return tracker.track(
                resource, kind, GENERATION, request, 0L, 0, 1, 0, 1, 0L, 0L, kind << 4, System.currentTimeMillis());
    }

    private List<ResourceTracker.Entry> sweepAfterGrace() {
        List<ResourceTracker.Entry> reports = new ArrayList<>();
        tracker.sweep(GENERATION, System.nanoTime() + Resources.GRACE_NANOS, Resources.GRACE_NANOS, 0, reports);
        return reports;
    }

    private static void awaitCollected(WeakReference<?> reference) throws InterruptedException {
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(reference.get())
                .as("nothing the tracker holds keeps it alive")
                .isNull();
    }
}
