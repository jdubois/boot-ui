package bootuiagentit;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.Resources;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import io.github.jdubois.bootui.agent.bridge.ThreadActivity;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bootuiit.library.SocketPool;

/**
 * The resources sensor's behaviors (PLAN-v2 §5.16, M5-5g), in a forked JVM beside the agent, claimed with {@code files},
 * {@code network}, and {@code resources}, never {@code thread-activity}, whose request-end feed the harness uses as an
 * adapter does ({@link ThreadActivity#requestEnded}). A loopback server accepts the sockets. Prints one PASS or FAIL
 * line per behavior, then the bridge's status.
 */
public final class ResourcesBehaviors {

    static long nextRequest = 0x300L;

    static final ThreadLocal<Object[]> CONTEXT = new ThreadLocal<>();
    static final List<String> RESULTS = new ArrayList<>();
    static final List<long[]> RECORDS = new ArrayList<>();
    static long token;
    static long generation;
    static Path directory;
    static ServerSocket server;
    static final List<Socket> ACCEPTED = new ArrayList<>();

    private ResourcesBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        directory = Files.createTempDirectory("bootui-resources");
        // Loaded and used before the claim, as an application's are: the close hooks retransform them.
        Path warm = Files.writeString(directory.resolve("warm.txt"), "x");
        new FileInputStream(warm.toFile()).close();
        new Socket().close();
        SocketChannel.open().close();
        startServer();
        token = claim(List.of(SideEffects.FILES, SideEffects.NETWORK, SideEffects.RESOURCES));
        for (String sensor : List.of(SideEffects.FILES, SideEffects.NETWORK, SideEffects.RESOURCES)) {
            awaitSelfTest(sensor);
        }
        if ("missing-close-hook".equals(mode)) {
            missingCloseHook();
        } else if (!"check".equals(mode)) {
            streamLeftOpen();
            tryWithResources();
            closedInFinally();
            randomAccessLeftOpen();
            providerStreams();
            lines();
            channelLeftOpen();
            streamReclaimed();
            socketClosed();
            socketLeftOpen();
            socketReclaimed();
            socketChannelLeftOpen();
            interruptedChannel();
            pooledSocket();
            httpClientPool();
            unowned();
            jobReclaimed();
            bootUiWork();
            filesSwitchKeepsCloseHooks();
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("RESOURCES=" + status.get(SideEffects.RESOURCES));
        System.out.println("SENSOR=" + sensor(SideEffects.RESOURCES));
        RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
        System.out.flush();
        System.exit(0);
    }

    /** Held strongly: the bridge keeps the claim's capture and reopen weakly. */
    static final Supplier<Object> CAPTURE = new Supplier<Object>() {
        @Override
        public Object get() {
            return CONTEXT.get();
        }
    };

    static final Function<Object, AutoCloseable> REOPEN = argument -> null;

    static long claim(List<String> sensors) {
        Supplier<Object> capture = CAPTURE;
        Function<Object, AutoCloseable> reopen = REOPEN;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "resources-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        System.out.println("CLAIM=" + result.get("status"));
        generation = (Long) result.get("generation");
        return (Long) result.get("token");
    }

    // ---- behaviors ---------------------------------------------------------------------------------------------

    static void streamLeftOpen() throws Exception {
        RECORDS.clear();
        long request = request();
        FileInputStream stream = new FileInputStream(file("report-1.csv").toFile());
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        stream.close();
        long[] late = await(kind(SideEffects.KIND_RESOURCE_CLOSED_LATE));
        check(
                "a FileInputStream still open after its request is reported open with thread-activity off, then closed"
                        + " late (" + describe(RECORDS) + ")",
                left != null
                        && late != null
                        && left[SideEffects.R_REQUEST] == request
                        && resourceKind(left) == Resources.KIND_FILE_INPUT_STREAM
                        && origin(left) == Resources.ORIGIN_APPLICATION
                        && first(left)
                        && !first(late)
                        && String.valueOf(string(left[SideEffects.R_TARGET])).endsWith("report-{n}.csv")
                        && applicationFrame(left).startsWith("bootuiagentit.ResourcesBehaviors#streamLeftOpen"));
    }

    static void tryWithResources() throws Exception {
        RECORDS.clear();
        long request = request();
        try (FileInputStream stream = new FileInputStream(file("try.csv").toFile())) {
            stream.read();
        }
        endRequest(request);
        settle();
        check(
                "a FileInputStream in try-with-resources is never reported (" + describe(RECORDS) + ")",
                RECORDS.isEmpty());
    }

    static void closedInFinally() throws Exception {
        RECORDS.clear();
        long request = request();
        FileOutputStream stream =
                new FileOutputStream(directory.resolve("finally.csv").toFile());
        try {
            stream.write(1);
        } finally {
            stream.close();
        }
        endRequest(request);
        settle();
        check("a FileOutputStream closed in finally is never reported (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void randomAccessLeftOpen() throws Exception {
        RECORDS.clear();
        long request = request();
        RandomAccessFile file = new RandomAccessFile(file("random.bin").toFile(), "r");
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        file.close();
        check(
                "a RandomAccessFile still open after its request is reported open (" + describe(RECORDS) + ")",
                left != null && resourceKind(left) == Resources.KIND_RANDOM_ACCESS_FILE);
    }

    static void providerStreams() throws Exception {
        RECORDS.clear();
        long request = request();
        InputStream left = Files.newInputStream(file("provider-in.csv"));
        try (OutputStream out = Files.newOutputStream(directory.resolve("provider-out.csv"))) {
            out.write(1);
        }
        endRequest(request);
        long[] open = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        settle();
        left.close();
        long[] late = await(kind(SideEffects.KIND_RESOURCE_CLOSED_LATE));
        check(
                "Files.newInputStream left open is reported as its file channel, and Files.newOutputStream closed is"
                        + " not (" + describe(RECORDS) + ")",
                open != null
                        && late != null
                        && resourceKind(open) == Resources.KIND_FILE_CHANNEL
                        && count(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN)) == 1
                        && String.valueOf(string(open[SideEffects.R_TARGET])).endsWith("provider-in.csv"));
    }

    static void lines() throws Exception {
        RECORDS.clear();
        long request = request();
        Path path = file("lines.txt");
        try (Stream<String> closed = Files.lines(path)) {
            closed.count();
        }
        Stream<String> left = Files.lines(path);
        left.count();
        endRequest(request);
        long[] open = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        settle();
        left.close();
        check(
                "Files.lines left open is reported once, and Files.lines in try-with-resources is not ("
                        + describe(RECORDS) + ")",
                open != null && count(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN)) == 1);
    }

    static void channelLeftOpen() throws Exception {
        RECORDS.clear();
        long request = request();
        FileChannel channel = FileChannel.open(file("channel.bin"), StandardOpenOption.READ);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        channel.close();
        check(
                "a FileChannel still open after its request is reported open (" + describe(RECORDS) + ")",
                left != null && resourceKind(left) == Resources.KIND_FILE_CHANNEL);
    }

    static void streamReclaimed() throws Exception {
        RECORDS.clear();
        long request = request();
        WeakReference<Object> leaked = leakStream(file("leak.csv"));
        endRequest(request);
        long[] reclaimed = awaitCollected(leaked, kind(SideEffects.KIND_RESOURCE_RECLAIMED));
        check(
                "a FileInputStream never closed and reclaimed by the collector is reported reclaimed without close(),"
                        + " counted once (" + describe(RECORDS) + ")",
                reclaimed != null
                        && reclaimed[SideEffects.R_REQUEST] == request
                        && resourceKind(reclaimed) == Resources.KIND_FILE_INPUT_STREAM
                        && firsts() == 1);
    }

    static void socketClosed() throws Exception {
        RECORDS.clear();
        long request = request();
        try (Socket socket = new Socket()) {
            socket.connect(serverAddress(), 5_000);
        }
        endRequest(request);
        settle();
        check("a socket closed by try-with-resources is never reported (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void socketLeftOpen() throws Exception {
        RECORDS.clear();
        long request = request();
        Socket socket = new Socket();
        socket.connect(serverAddress(), 5_000);
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        socket.getOutputStream().close();
        long[] late = await(kind(SideEffects.KIND_RESOURCE_CLOSED_LATE));
        check(
                "a socket still open after its request is reported open, then closed late through its stream ("
                        + describe(RECORDS) + ")",
                left != null
                        && late != null
                        && resourceKind(left) == Resources.KIND_SOCKET
                        && String.valueOf(string(left[SideEffects.R_TARGET])).contains(":" + server.getLocalPort()));
    }

    static void socketReclaimed() throws Exception {
        RECORDS.clear();
        long request = request();
        WeakReference<Object> leaked = leakSocket();
        endRequest(request);
        long[] reclaimed = awaitCollected(leaked, kind(SideEffects.KIND_RESOURCE_RECLAIMED));
        check(
                "a socket never closed and reclaimed by the collector is reported reclaimed without close() ("
                        + describe(RECORDS) + ")",
                reclaimed != null && resourceKind(reclaimed) == Resources.KIND_SOCKET);
    }

    static void socketChannelLeftOpen() throws Exception {
        RECORDS.clear();
        long request = request();
        SocketChannel channel = SocketChannel.open(serverAddress());
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        channel.close();
        long[] late = await(kind(SideEffects.KIND_RESOURCE_CLOSED_LATE));
        check(
                "a SocketChannel still open after its request is reported open, then closed late (" + describe(RECORDS)
                        + ")",
                left != null && late != null && resourceKind(left) == Resources.KIND_SOCKET_CHANNEL);
    }

    static void interruptedChannel() throws Exception {
        RECORDS.clear();
        long request = request();
        WeakReference<Object> interrupted = interruptChannel(file("interrupted.bin"));
        endRequest(request);
        for (int i = 0; i < 20 && interrupted.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        settle();
        check(
                "a FileChannel closed by its thread's interruption is never reported reclaimed (" + describe(RECORDS)
                        + ")",
                interrupted.get() == null && none(kind(SideEffects.KIND_RESOURCE_RECLAIMED)));
    }

    static void pooledSocket() throws Exception {
        RECORDS.clear();
        long request = request();
        SocketPool.borrow(serverAddress());
        endRequest(request);
        long[] left = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        SocketPool.evictAll();
        long[] late = await(kind(SideEffects.KIND_RESOURCE_CLOSED_LATE));
        collect();
        settle();
        check(
                "a library pool's socket kept past its request is reported open and closed late, the library's, never"
                        + " reclaimed (" + describe(RECORDS) + ")",
                left != null
                        && late != null
                        && origin(left) == Resources.ORIGIN_LIBRARY
                        && none(kind(SideEffects.KIND_RESOURCE_RECLAIMED)));
    }

    static void httpClientPool() throws Exception {
        RECORDS.clear();
        com.sun.net.httpserver.HttpServer http =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        http.start();
        try {
            long tracked = counter("tracked");
            long request = request();
            String port = ":" + http.getAddress().getPort();
            sendTwice(http.getAddress().getPort());
            endRequest(request);
            long[] left = await(records -> records.stream()
                    .anyMatch(record -> record[SideEffects.R_KIND] == SideEffects.KIND_RESOURCE_LEFT_OPEN
                            && String.valueOf(string(record[SideEffects.R_TARGET]))
                                    .endsWith(port)));
            collect();
            settle();
            check(
                    "the JDK HttpClient's pooled connection, opened on the request's thread, is tracked and reported open"
                            + " after its request as the library's, never reclaimed (tracked "
                            + (counter("tracked") - tracked) + " " + describe(RECORDS) + ")",
                    counter("tracked") > tracked
                            && left != null
                            && left[SideEffects.R_REQUEST] == request
                            && origin(left) == Resources.ORIGIN_LIBRARY
                            && none(kind(SideEffects.KIND_RESOURCE_RECLAIMED)));
        } finally {
            http.stop(0);
        }
    }

    static void unowned() throws Exception {
        RECORDS.clear();
        CONTEXT.remove();
        WeakReference<Object> leaked = leakStream(file("unowned.csv"));
        awaitCollected(leaked, records -> false);
        check("a resource no request or job owns is never tracked (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void jobReclaimed() throws Exception {
        RECORDS.clear();
        CONTEXT.set(new Object[] {null, "00000000000000f1", null, null, null, null, null, 1L, 1L});
        WeakReference<Object> leaked = leakStream(file("job.csv"));
        CONTEXT.remove();
        long[] reclaimed = awaitCollected(leaked, kind(SideEffects.KIND_RESOURCE_RECLAIMED));
        check(
                "a job's stream never closed is reported reclaimed without close() under its execution ("
                        + describe(RECORDS) + ")",
                reclaimed != null && reclaimed[SideEffects.R_EXECUTION] == 0xf1L);
    }

    static void bootUiWork() throws Exception {
        RECORDS.clear();
        long request = request();
        boolean previous = AgentBridge.bootUiWork(true);
        WeakReference<Object> leaked;
        try {
            leaked = leakStream(file("bootui.csv"));
        } finally {
            AgentBridge.bootUiWork(previous);
        }
        endRequest(request);
        awaitCollected(leaked, records -> false);
        check("BootUI's own resources are never tracked (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void filesSwitchKeepsCloseHooks() throws Exception {
        RECORDS.clear();
        long request = request();
        Socket socket = new Socket();
        socket.connect(serverAddress(), 5_000);
        long closes = recorded("Socket.close");
        Map<String, Object> off = AgentBridge.switchSensor(token, SideEffects.FILES, false);
        for (int i = 0; i < 400; i++) {
            Map<String, Object> files = sensor(SideEffects.FILES);
            if ("released".equals(files.get("state")) && Boolean.TRUE.equals(files.get("idle"))) {
                break;
            }
            Thread.sleep(25);
        }
        socket.close();
        endRequest(request);
        settle();
        String state = String.valueOf(sensor(SideEffects.RESOURCES).get("state"));
        check(
                "switching files off at run time keeps the resources sensor's close hooks: a socket closed meanwhile"
                        + " is seen by its hook and never reported (" + off.get("status") + " " + state + " closes "
                        + (recorded("Socket.close") - closes) + " " + describe(RECORDS) + ")",
                RECORDS.isEmpty() && "installed".equals(state) && recorded("Socket.close") > closes);
    }

    /** The FileInputStream close hook left out: its kind is never tracked, so its leak is never misreported. */
    static void missingCloseHook() throws Exception {
        RECORDS.clear();
        long request = request();
        WeakReference<Object> leaked = leakStream(file("missing.csv"));
        FileOutputStream left =
                new FileOutputStream(directory.resolve("missing-out.csv").toFile());
        endRequest(request);
        awaitCollected(leaked, records -> false);
        long[] open = await(kind(SideEffects.KIND_RESOURCE_LEFT_OPEN));
        left.close();
        check(
                "without its close hook, a FileInputStream is never tracked, while a FileOutputStream still is ("
                        + describe(RECORDS) + ")",
                open != null
                        && resourceKind(open) == Resources.KIND_FILE_OUTPUT_STREAM
                        && RECORDS.stream()
                                .noneMatch(record -> resourceKind(record) == Resources.KIND_FILE_INPUT_STREAM));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static WeakReference<Object> leakStream(Path path) throws Exception {
        FileInputStream stream = new FileInputStream(path.toFile());
        stream.read();
        return new WeakReference<>(stream);
    }

    static WeakReference<Object> leakSocket() throws Exception {
        Socket socket = new Socket();
        socket.connect(serverAddress(), 5_000);
        return new WeakReference<>(socket);
    }

    /** Reads a channel on a helper thread that is interrupted first: the JDK closes the channel itself. */
    static WeakReference<Object> interruptChannel(Path path) throws Exception {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        Object[] owner = CONTEXT.get();
        Thread reader = new Thread(() -> {
            CONTEXT.set(owner);
            Thread.currentThread().interrupt();
            try {
                channel.read(ByteBuffer.allocate(1));
            } catch (ClosedByInterruptException expected) {
                // Closed by the JDK through implCloseChannel.
            } catch (Exception ex) {
                // Not this behavior's concern.
            }
        });
        reader.start();
        reader.join();
        return new WeakReference<>(channel);
    }

    static void sendTwice(int port) throws Exception {
        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest ping = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping"))
                .GET()
                .build();
        client.send(ping, HttpResponse.BodyHandlers.ofString());
        client.send(ping, HttpResponse.BodyHandlers.ofString());
    }

    static void startServer() throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(
                () -> {
                    while (!server.isClosed()) {
                        try {
                            Socket accepted = server.accept();
                            synchronized (ACCEPTED) {
                                ACCEPTED.add(accepted);
                            }
                        } catch (Exception ex) {
                            return;
                        }
                    }
                },
                "resources-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    static InetSocketAddress serverAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    static Path file(String name) throws Exception {
        return Files.writeString(directory.resolve(name), "line one\nline two\n");
    }

    static long request() {
        long request = nextRequest++;
        CONTEXT.set(new Object[] {String.format("%016x", request), null, null, null, "/reports", null, null, 1L, 1L});
        return request;
    }

    /** The request on this thread ends: the adapter tells the engine, which feeds thread-activity's request ends. */
    static void endRequest(long request) {
        CONTEXT.remove();
        SideEffects.flushThread();
        ThreadActivity.requestEnded(generation, request);
    }

    static void settle() throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread.sleep(60);
            drain();
        }
    }

    static void collect() throws Exception {
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(20);
        }
    }

    static long[] awaitCollected(WeakReference<Object> reference, Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (reference.get() != null) {
                System.gc();
            }
            drain();
            if (reference.get() == null && done.test(RECORDS)) {
                break;
            }
            Thread.sleep(25);
            if (i > 20 && reference.get() == null) {
                // Collected: a few more sweeps for its report, then stop.
                settle();
                break;
            }
        }
        return first(done);
    }

    static Predicate<List<long[]>> kind(int kind) {
        return records -> records.stream().anyMatch(record -> record[SideEffects.R_KIND] == kind);
    }

    static boolean none(Predicate<List<long[]>> predicate) {
        return !predicate.test(RECORDS);
    }

    static long count(Predicate<List<long[]>> predicate) {
        return RECORDS.stream()
                .filter(record -> predicate.test(List.of(record)))
                .count();
    }

    static long firsts() {
        return RECORDS.stream().filter(ResourcesBehaviors::first).count();
    }

    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 200; i++) {
            drain();
            if (done.test(RECORDS)) {
                break;
            }
            Thread.sleep(50);
        }
        return first(done);
    }

    static long[] first(Predicate<List<long[]>> done) {
        for (long[] record : RECORDS) {
            if (done.test(List.of(record))) {
                return record;
            }
        }
        return null;
    }

    static void drain() {
        SideEffects.drain(token, record -> {
            if (record[SideEffects.R_SENSOR] == SideEffects.SENSOR_RESOURCES) {
                RECORDS.add(record.clone());
            }
        });
    }

    /** A counter of the bridge's resources status. */
    @SuppressWarnings("unchecked")
    static long counter(String name) {
        Object value = ((Map<String, Object>) AgentBridge.status().get(SideEffects.RESOURCES)).get(name);
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    /** What a resources hook recorded: the closes of a tracked resource. */
    @SuppressWarnings("unchecked")
    static long recorded(String hook) {
        Map<String, Object> status = (Map<String, Object>) AgentBridge.status().get(SideEffects.RESOURCES);
        Object value = ((Map<String, Object>) status.get("recorded")).get(hook);
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    static String string(long id) {
        if (id <= 0) {
            return null;
        }
        String[] strings = SideEffects.interned(generation, (int) id);
        return strings == null || strings.length == 0 ? null : strings[0];
    }

    static int detail(long[] record) {
        return (int) (record[SideEffects.R_FLAGS] >>> 32);
    }

    static int origin(long[] record) {
        return detail(record) & 3;
    }

    static int resourceKind(long[] record) {
        return (detail(record) >>> 4) & 0xF;
    }

    static boolean first(long[] record) {
        return (detail(record) & Resources.DETAIL_FIRST) != 0;
    }

    static String applicationFrame(long[] record) {
        String frame = string((int) record[SideEffects.R_FRAMES]);
        return frame == null ? "" : frame;
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " request=" + Long.toHexString(record[SideEffects.R_REQUEST]) + " detail="
                    + Integer.toBinaryString(detail(record)) + " frames=" + string(record[SideEffects.R_FRAMES] >>> 32)
                    + "|" + string((int) record[SideEffects.R_FRAMES]));
        }
        return described.toString();
    }

    static void awaitSelfTest(String id) throws Exception {
        Map<String, Object> sensor = SensorWait.awaitSettled(id);
        System.out.println("SELF_TEST_" + id + "=" + sensor.get("selfTestPassed") + " " + sensor.get("selfTestError")
                + " " + sensor.get("hooks"));
    }

    static Map<String, Object> sensor(String id) {
        return SideEffectsBehaviors.sensor(id);
    }

    static void check(String name, boolean ok) {
        RESULTS.add((ok ? "  PASS " : "  FAIL ") + name);
        System.out.println("CHECKED " + (ok ? "PASS " : "FAIL ") + name);
    }

    static List<String> strings() {
        String[] strings = SideEffects.interned(generation, 1);
        return strings == null ? List.of() : Arrays.asList(strings);
    }
}
