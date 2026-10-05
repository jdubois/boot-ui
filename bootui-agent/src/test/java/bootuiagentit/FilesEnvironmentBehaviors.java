package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.CodePaths;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The files and environment sensors' behaviors (PLAN-v2 §5.16, M5-5d), in a forked JVM beside the agent, claimed with
 * {@code files} and {@code environment} and a harness engine whose context is a thread-local request id. Prints one PASS
 * or FAIL line per behavior, then the bridge's status. Mode {@code check} claims and reports the self-test only; {@code
 * check-files} and {@code check-environment} claim one sensor alone.
 */
public final class FilesEnvironmentBehaviors {

    static final String REQUEST = "00000000000000ef";
    static final long REQUEST_BITS = 0xefL;
    static final String SECRET = "hunter2-bootui-secret-value";

    static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;
    static Path work;

    private FilesEnvironmentBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        work = Path.of("")
                .toAbsolutePath()
                .resolve("files-environment-" + ProcessHandle.current().pid());
        Files.createDirectories(work);
        List<String> sensors =
                switch (mode) {
                    case "check-files", "overhead-off" -> List.of(SideEffects.FILES);
                    case "check-environment", "overhead-environment" -> List.of(SideEffects.ENVIRONMENT);
                    default -> List.of(SideEffects.FILES, SideEffects.ENVIRONMENT);
                };
        // Loaded and used before the claim, as an application's are: the hooks retransform them.
        System.getProperty("bootui.before.claim");
        Files.exists(work);
        token = claim(sensors);
        for (String sensor : sensors) {
            awaitSelfTest(sensor);
        }
        if (mode.equals("behaviors")) {
            reportOutsideTheTemporaryDirectory();
            temporaryFile();
            everyFileHook();
            nestedHooksRecordOnce();
            scopeAggregates();
            bucketsAreCountedNotRecorded();
            classLoadingIsGroupedApart();
            jdkLoggingIsGroupedApart();
            loggingFrameworkIsNamed();
            propertyReadOncePerOwner();
            environmentVariables();
            jdkReadsAreNotRecorded();
            bootUiWork();
            unowned();
            releaseRestores();
        } else if (mode.startsWith("overhead")) {
            overhead();
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("FILES=" + status.get(SideEffects.FILES));
        System.out.println("ENVIRONMENT=" + status.get(SideEffects.ENVIRONMENT));
        for (String sensor : sensors) {
            System.out.println("SENSOR_" + sensor + "=" + SideEffectsBehaviors.sensor(sensor));
        }
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static long claim(List<String> sensors) {
        Supplier<Object> capture = () -> CONTEXT.get() == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/reports", null, null, 1L, 1L};
        Function<Object, AutoCloseable> reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "files-environment-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    static void reportOutsideTheTemporaryDirectory() throws Exception {
        RECORDS.clear();
        Path reports = work.resolve("reports");
        Files.createDirectories(reports);
        CONTEXT.set(REQUEST);
        try (FileOutputStream out =
                new FileOutputStream(reports.resolve("report-2026-10-05.csv").toString())) {
            out.write("a,b\n".getBytes(StandardCharsets.UTF_8));
        }
        CONTEXT.remove();
        long[] write = await(target(SideEffects.KIND_FILE_WRITE, "report-{n}-{n}-{n}.csv"));
        String pattern = write == null ? null : string(write[SideEffects.R_TARGET]);
        check(
                "a report written outside the temporary directory records its pattern, a write, under its request ("
                        + describe(RECORDS) + ")",
                write != null
                        && pattern.startsWith("./")
                        && pattern.endsWith("/reports/report-{n}-{n}-{n}.csv")
                        && write[SideEffects.R_REQUEST] == REQUEST_BITS
                        && outcome(write) == SideEffects.OUTCOME_DONE
                        && context(write) == SideEffects.CONTEXT_NONE
                        && frame((int) write[SideEffects.R_FRAMES])
                                .startsWith("bootuiagentit.FilesEnvironmentBehaviors#")
                        && interned().stream().noneMatch(text -> text.contains(work.toString())));
    }

    static void temporaryFile() throws Exception {
        RECORDS.clear();
        Path temporary =
                Path.of(System.getProperty("java.io.tmpdir"), "bootui-side-effects-" + System.nanoTime() + ".txt");
        CONTEXT.set(REQUEST);
        Files.writeString(temporary, "scratch");
        Files.delete(temporary);
        CONTEXT.remove();
        long[] write = await(target(SideEffects.KIND_FILE_WRITE, "$TMPDIR/bootui-side-effects-{n}.txt"));
        long[] delete = await(target(SideEffects.KIND_FILE_DELETE, "$TMPDIR/bootui-side-effects-{n}.txt"));
        check(
                "a temporary file's pattern starts with $TMPDIR (" + describe(RECORDS) + ")",
                write != null && delete != null);
    }

    static void everyFileHook() throws Exception {
        RECORDS.clear();
        Path data = work.resolve("data");
        Files.createDirectories(data);
        Path file = data.resolve("input.txt");
        Files.writeString(file, "x");
        drain();
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        new FileInputStream(file.toFile()).close();
        new RandomAccessFile(file.toFile(), "r").close();
        new RandomAccessFile(data.resolve("random.bin").toFile(), "rw").close();
        Files.newByteChannel(file).close();
        Files.newByteChannel(data.resolve("channel.bin"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .close();
        Files.newInputStream(file).close();
        Files.newOutputStream(data.resolve("output.txt")).close();
        Files.copy(file, data.resolve("copy.txt"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                new java.io.ByteArrayInputStream(new byte[] {1}),
                data.resolve("stream.txt"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(file, new java.io.ByteArrayOutputStream());
        Files.move(data.resolve("copy.txt"), data.resolve("moved.txt"), StandardCopyOption.REPLACE_EXISTING);
        FileChannel.open(file, StandardOpenOption.READ).close();
        FileChannel.open(data.resolve("append.txt"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                .close();
        Files.delete(data.resolve("moved.txt"));
        Files.deleteIfExists(data.resolve("stream.txt"));
        boolean failed = false;
        try {
            new FileInputStream(data.resolve("missing.txt").toFile()).close();
        } catch (java.io.FileNotFoundException expected) {
            failed = true;
        }
        CONTEXT.remove();
        String[][] expected = {
            {"read", "/data/input.txt"},
            {"write", "/data/random.bin"},
            {"write", "/data/channel.bin"},
            {"write", "/data/output.txt"},
            {"copy-from", "/data/input.txt"},
            {"copy-to", "/data/copy.txt"},
            {"copy-to", "/data/stream.txt"},
            {"move-from", "/data/copy.txt"},
            {"move-to", "/data/moved.txt"},
            {"write", "/data/append.txt"},
            {"delete", "/data/moved.txt"},
            {"delete", "/data/stream.txt"}
        };
        await(records -> missing(expected).isEmpty());
        long[] notFound = find(SideEffects.KIND_FILE_READ, "/data/missing.txt");
        long reads = count(SideEffects.KIND_FILE_READ, "/data/input.txt");
        check(
                "every files hook records its kind and pattern, and a failed open its failure (missing "
                        + missing(expected) + ", reads " + reads + ", " + describe(RECORDS) + ")",
                missing(expected).isEmpty()
                        && failed
                        && notFound != null
                        && outcome(notFound) == SideEffects.OUTCOME_IO_ERROR
                        // FileInputStream, RandomAccessFile "r", newByteChannel, newInputStream, FileChannel.open
                        && reads == 5);
    }

    static void nestedHooksRecordOnce() throws Exception {
        RECORDS.clear();
        Path file = work.resolve("nested.txt");
        Files.writeString(file, "nested");
        drain();
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        // newInputStream reaches newByteChannel through the provider; readAllBytes newByteChannel's varargs overload.
        Files.newInputStream(file).close();
        Files.readAllBytes(file);
        CONTEXT.remove();
        await(records -> count(SideEffects.KIND_FILE_READ, "/nested.txt") >= 2);
        Thread.sleep(100);
        drain();
        check(
                "a file operation nested in another records once (" + describe(RECORDS) + ")",
                count(SideEffects.KIND_FILE_READ, "/nested.txt") == 2);
    }

    static void scopeAggregates() throws Exception {
        RECORDS.clear();
        Path file = work.resolve("scoped.txt");
        Files.writeString(file, "scoped");
        drain();
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        // An adapter's scope: without the code-paths sensor, the bridge captures the scope's owner once.
        CodePaths.begin();
        for (int i = 0; i < 3; i++) {
            Files.readAllBytes(file);
        }
        Thread.sleep(50);
        // Drained without flushing the thread's table: the scope's operations wait in it until the scope ends.
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
        int beforeEnd = RECORDS.size();
        CodePaths.end();
        CONTEXT.remove();
        long[] read = await(target(SideEffects.KIND_FILE_READ, "/scoped.txt"));
        check(
                "inside a scope, operations aggregate under the scope's owner and flush at its end (" + beforeEnd + " "
                        + describe(RECORDS) + ")",
                beforeEnd == 0
                        && read != null
                        && read[SideEffects.R_COUNT] == 3
                        && read[SideEffects.R_REQUEST] == REQUEST_BITS);
    }

    @SuppressWarnings("unchecked")
    static void bucketsAreCountedNotRecorded() throws Exception {
        Map<String, Object> before = (Map<String, Object>) files().get("buckets");
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        String classFile = FilesEnvironmentBehaviors.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .getPath()
                + "bootuiagentit/FilesEnvironmentBehaviors.class";
        new FileInputStream(classFile).close();
        Path jar = work.resolve("library-1.0.jar");
        Files.write(jar, new byte[] {0});
        drain();
        RECORDS.clear();
        new RandomAccessFile(jar.toFile(), "r").close();
        Path release = Path.of(System.getProperty("java.home"), "release");
        drain();
        RECORDS.clear();
        int interned = interned().size();
        new FileInputStream(release.toFile()).close();
        new RandomAccessFile(jar.toFile(), "r").close();
        CONTEXT.remove();
        Thread.sleep(100);
        drain();
        Map<String, Object> after = (Map<String, Object>) files().get("buckets");
        RECORDS.removeIf(record -> record[SideEffects.R_SENSOR] != SideEffects.SENSOR_FILES);
        check(
                "class files, archives, and Java's home are counted in buckets, never recorded or interned (" + before
                        + " " + after + " " + describe(RECORDS) + ")",
                RECORDS.isEmpty()
                        && interned().size() == interned
                        && (Long) after.get("classFiles") > (Long) before.get("classFiles")
                        && (Long) after.get("archives") > (Long) before.get("archives")
                        && (Long) after.get("javaHome") > (Long) before.get("javaHome"));
    }

    static void classLoadingIsGroupedApart() throws Exception {
        RECORDS.clear();
        Path resources = work.resolve("plugin");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("plugin.properties"), "name=plugin");
        drain();
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            loader.getResourceAsStream("plugin.properties").close();
        }
        CONTEXT.remove();
        long[] read = await(target(SideEffects.KIND_FILE_READ, "/plugin/plugin.properties"));
        check(
                "a resource a class loader reads is class loading (" + describe(RECORDS) + ")",
                read != null && context(read) == SideEffects.CONTEXT_CLASS_LOADING && read[SideEffects.R_FRAMES] == 0L);
    }

    static void jdkLoggingIsGroupedApart() throws Exception {
        RECORDS.clear();
        Path logs = work.resolve("logs");
        Files.createDirectories(logs);
        CONTEXT.set(REQUEST);
        java.util.logging.FileHandler handler =
                new java.util.logging.FileHandler(logs.resolve("app.log").toString());
        handler.publish(new java.util.logging.LogRecord(java.util.logging.Level.INFO, "logged"));
        handler.close();
        CONTEXT.remove();
        long[] log = await(target(SideEffects.KIND_FILE_WRITE, "/logs/app.log"));
        check(
                "a JDK logging handler's file is JDK logging (" + describe(RECORDS) + ")",
                log != null && context(log) == SideEffects.CONTEXT_JDK_LOGGING);
    }

    static void loggingFrameworkIsNamed() throws Exception {
        RECORDS.clear();
        Path logs = work.resolve("logs");
        Files.createDirectories(logs);
        CONTEXT.set(REQUEST);
        ch.qos.logback.bootuiit.FakeAppender.append(logs.resolve("logback.log").toString(), "logged");
        CONTEXT.remove();
        long[] log = await(target(SideEffects.KIND_FILE_WRITE, "/logs/logback.log"));
        check(
                "a logging framework's file names the framework as its first frame outside the JDK ("
                        + describe(RECORDS) + ")",
                log != null
                        && context(log) == SideEffects.CONTEXT_NONE
                        && string(log[SideEffects.R_FRAMES] >>> 32)
                                .startsWith("ch.qos.logback.bootuiit.FakeAppender#"));
    }

    // ---- environment -------------------------------------------------------------------------------------------

    static void propertyReadOncePerOwner() throws Exception {
        RECORDS.clear();
        System.setProperty("bootui.it.report.title", SECRET);
        CONTEXT.set(REQUEST);
        CodePaths.begin();
        for (int i = 0; i < 5; i++) {
            System.getProperty("bootui.it.report.title");
            System.getProperty("bootui.it.report.title", "default-" + SECRET);
        }
        Boolean.getBoolean("bootui.it.flag");
        // Through a method reference: the stream's and Optional's JDK frames are passed, the read is still direct.
        List.of("bootui.it.stream").stream().map(System::getProperty).toList();
        java.util.Optional.of("BOOTUI_IT_OPTIONAL").map(System::getenv);
        CodePaths.end();
        CONTEXT.remove();
        long[] read = await(target(SideEffects.KIND_SYSTEM_PROPERTY, "bootui.it.report.title"));
        long[] flag = await(target(SideEffects.KIND_SYSTEM_PROPERTY, "bootui.it.flag"));
        long[] stream = await(target(SideEffects.KIND_SYSTEM_PROPERTY, "bootui.it.stream"));
        long[] optional = await(target(SideEffects.KIND_ENVIRONMENT_VARIABLE, "BOOTUI_IT_OPTIONAL"));
        check(
                "a property read records its name once per owner, never its value or default (" + describe(RECORDS)
                        + ")",
                read != null
                        && read[SideEffects.R_COUNT] == 1
                        && read[SideEffects.R_REQUEST] == REQUEST_BITS
                        && flag != null
                        && frame((int) flag[SideEffects.R_FRAMES])
                                .startsWith("bootuiagentit.FilesEnvironmentBehaviors#")
                        && stream != null
                        && optional != null
                        && interned().stream().noneMatch(text -> text.contains(SECRET)));
    }

    static void environmentVariables() throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        System.getenv("PATH");
        System.getenv();
        CONTEXT.remove();
        long[] path = await(target(SideEffects.KIND_ENVIRONMENT_VARIABLE, "PATH"));
        long[] all = await(target(SideEffects.KIND_ENVIRONMENT_VARIABLE, "(all variables)"));
        String value = System.getenv("PATH");
        check(
                "an environment variable records its name, and getenv() every variable, never a value ("
                        + describe(RECORDS) + ")",
                path != null
                        && all != null
                        && (value == null
                                || value.length() < 4
                                || interned().stream().noneMatch(text -> text.equals(value))));
    }

    @SuppressWarnings("unchecked")
    static void jdkReadsAreNotRecorded() throws Exception {
        RECORDS.clear();
        long before = (Long) environment().get("jdkReads");
        CONTEXT.set(REQUEST);
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
        CONTEXT.remove();
        Thread.sleep(100);
        drain();
        long after = (Long) environment().get("jdkReads");
        check(
                "the JDK's own property reads are not recorded (" + before + " -> " + after + " " + describe(RECORDS)
                        + ")",
                after > before
                        && RECORDS.stream()
                                .noneMatch(record -> string(record[SideEffects.R_TARGET]) != null
                                        && string(record[SideEffects.R_TARGET]).startsWith("javax.xml")));
    }

    // ---- exclusions and release --------------------------------------------------------------------------------

    static void bootUiWork() throws Exception {
        RECORDS.clear();
        boolean previous = AgentBridge.bootUiWork(true);
        try {
            Files.writeString(work.resolve("bootui-own.txt"), "own");
            System.getProperty("bootui.it.own.property");
        } finally {
            AgentBridge.bootUiWork(previous);
        }
        Thread.sleep(200);
        drain();
        check("BootUI's own work is never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void unowned() throws Exception {
        RECORDS.clear();
        Files.writeString(work.resolve("unowned.txt"), "unowned");
        long[] write = await(target(SideEffects.KIND_FILE_WRITE, "/unowned.txt"));
        check(
                "unowned work names its thread (" + describe(RECORDS) + ")",
                write != null
                        && write[SideEffects.R_REQUEST] == 0L
                        && Thread.currentThread().getName().equals(string((int)
                                ((write[SideEffects.R_FLAGS] >>> 16) & 0xFFFF))));
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("files-environment-behaviors", "dev");
        Object files = SideEffectsBehaviors.awaitState(SideEffects.FILES, "released");
        SideEffects.beginSelfTest();
        try {
            new FileInputStream(work.resolve("released.txt").toFile()).close();
        } catch (java.io.IOException expected) {
            // Missing: the point is whether the hook still runs.
        }
        System.getProperty("bootui.it.released");
        Map<String, Object> hits = SideEffects.endSelfTest();
        check(
                "release restores the file and System classes (" + files + ", " + hits + ")",
                "released".equals(files)
                        && Long.valueOf(0L).equals(hits.get("FileInputStream.open"))
                        && Long.valueOf(0L).equals(hits.get("System.getProperty")));
    }

    /** {@code System.getProperty} cost with the environment sensor recording, in nanoseconds per call. */
    static void overhead() {
        String[] names = {"user.dir", "java.version", "bootui.overhead.a", "bootui.overhead.b"};
        long sink = 0;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 2_000_000; i++) {
                String value = System.getProperty(names[i & 3]);
                sink += value == null ? 0 : value.length();
            }
            long elapsed = System.nanoTime() - start;
            System.out.println("GET_PROPERTY_NANOS_" + round + "=" + (elapsed / 2_000_000.0) + " " + (sink & 1));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static List<String> missing(String[][] expected) {
        List<String> missing = new ArrayList<>();
        for (String[] entry : expected) {
            if (find(kindOf(entry[0]), entry[1]) == null) {
                missing.add(entry[0] + " " + entry[1]);
            }
        }
        return missing;
    }

    static int kindOf(String kind) {
        return switch (kind) {
            case "read" -> SideEffects.KIND_FILE_READ;
            case "write" -> SideEffects.KIND_FILE_WRITE;
            case "delete" -> SideEffects.KIND_FILE_DELETE;
            case "move-from" -> SideEffects.KIND_FILE_MOVE_FROM;
            case "move-to" -> SideEffects.KIND_FILE_MOVE_TO;
            case "copy-from" -> SideEffects.KIND_FILE_COPY_FROM;
            case "copy-to" -> SideEffects.KIND_FILE_COPY_TO;
            default -> -1;
        };
    }

    static Predicate<List<long[]>> target(int kind, String suffix) {
        return records -> records.stream().anyMatch(record -> matches(record, kind, suffix));
    }

    static boolean matches(long[] record, int kind, String suffix) {
        String target = string(record[SideEffects.R_TARGET]);
        return record[SideEffects.R_KIND] == kind && target != null && target.endsWith(suffix);
    }

    static long[] find(int kind, String suffix) {
        for (long[] record : RECORDS) {
            if (matches(record, kind, suffix)) {
                return record;
            }
        }
        return null;
    }

    static long count(int kind, String suffix) {
        return RECORDS.stream()
                .filter(record -> matches(record, kind, suffix))
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
    }

    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 200; i++) {
            drain();
            if (done.test(RECORDS)) {
                break;
            }
            Thread.sleep(25);
        }
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.flushThread();
        SideEffects.drain(token, record -> RECORDS.add(record.clone()));
    }

    static List<String> interned() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static String frame(int id) {
        String text = string(id);
        return text == null ? "" : text;
    }

    static int outcome(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] & 0xFF);
    }

    static int context(long[] record) {
        return (int) ((record[SideEffects.R_FLAGS] >>> 32) & 0xFF);
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " outcome=" + outcome(record) + " context=" + context(record) + " request="
                    + Long.toHexString(record[SideEffects.R_REQUEST]) + " count=" + record[SideEffects.R_COUNT]
                    + " outside=" + string(record[SideEffects.R_FRAMES] >>> 32) + " application="
                    + string((int) record[SideEffects.R_FRAMES]));
        }
        return described.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> files() {
        return (Map<String, Object>) AgentBridge.status().get(SideEffects.FILES);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> environment() {
        return (Map<String, Object>) AgentBridge.status().get(SideEffects.ENVIRONMENT);
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = SensorWait.awaitSettled(id);
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
    }
}
