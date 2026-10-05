package bootuiagentit;

import static bootuiagentit.SideEffectsBehaviors.CONTEXT;
import static bootuiagentit.SideEffectsBehaviors.RECORDS;
import static bootuiagentit.SideEffectsBehaviors.REQUEST;
import static bootuiagentit.SideEffectsBehaviors.REQUEST_BITS;
import static bootuiagentit.SideEffectsBehaviors.check;
import static bootuiagentit.SideEffectsBehaviors.drain;
import static bootuiagentit.SideEffectsBehaviors.interned;
import static bootuiagentit.SideEffectsBehaviors.outcome;
import static bootuiagentit.SideEffectsBehaviors.sensor;
import static bootuiagentit.SideEffectsBehaviors.string;

import com.sun.net.httpserver.HttpServer;
import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.SideEffects;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The network sensor's behaviors (PLAN-v2 §5.16, M5-5b), in a forked JVM beside the agent, claimed with the {@code
 * network} sensor alone and a harness engine whose
 * context is a thread-local request id. Prints one PASS or FAIL line per behavior, then the bridge's status. Modes:
 * {@code check} (the retransformation check only), {@code behaviors}, {@code overhead} (per-operation cost with the
 * sensor recording and switched off), {@code jfr-first} and {@code jfr-after} (JFR's socket events beside the sensor),
 * and {@code mockito-first} and {@code bootui-first} (Mockito's inline mock maker mocking {@code Socket}).
 */
public final class NetworkBehaviors {

    static final String PAYLOAD = "BOOTUI-NETWORK-PAYLOAD-never-recorded";

    /** The loopback address without a host name, so a target reads {@code 127.0.0.1:port}. */
    static final InetAddress LOOPBACK = loopback();

    private NetworkBehaviors() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "behaviors";
        // Loaded and used before the claim, as an application's are: the hooks retransform them.
        new Socket().close();
        Object mock = null;
        if ("mockito-first".equals(mode)) {
            mock = org.mockito.Mockito.mock(Socket.class);
        }
        Object jfr = "jfr-first".equals(mode) ? JfrSockets.start() : null;
        claim(List.of(SideEffects.NETWORK));
        SideEffectsBehaviors.awaitSelfTest(SideEffects.NETWORK);
        if ("jfr-after".equals(mode)) {
            jfr = JfrSockets.start();
        }
        if ("bootui-first".equals(mode)) {
            mock = org.mockito.Mockito.mock(Socket.class);
        }
        try (Server server = new Server()) {
            if (mock != null) {
                mockito((Socket) mock, server);
            } else if (jfr != null) {
                socketConnect(server);
                nonBlockingConnect(server);
                adaptorConnect(server);
                System.out.println("JFR=" + JfrSockets.stop(jfr));
            } else if ("overhead".equals(mode)) {
                overhead(server);
            } else if ("behaviors".equals(mode)) {
                socketConnect(server);
                refusedConnect();
                nonBlockingConnect(server);
                adaptorConnect(server);
                jdkHttpClient();
                httpUrlConnection();
                datagrams();
                lookups();
                unowned(server);
                bootUiWork(server);
                bootUiThread(server);
                userInformation(server);
                releaseRestores();
            }
        }
        Map<String, Object> status = AgentBridge.status();
        System.out.println("NETWORK=" + status.get(SideEffects.NETWORK));
        System.out.println("SENSOR=" + sensor(SideEffects.NETWORK));
        SideEffectsBehaviors.RESULTS.forEach(System.out::println);
        System.out.println("STATUS=" + status);
    }

    static void claim(List<String> sensors) {
        Object marker = new Object();
        SideEffectsBehaviors.capture = () -> CONTEXT.get() == null || marker == null
                ? null
                : new Object[] {CONTEXT.get(), null, null, null, "/reports", null, null, 1L, 1L};
        SideEffectsBehaviors.reopen = argument -> null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", "network-behaviors");
        request.put("mode", "dev");
        request.put("packages", List.of("bootuiagentit"));
        request.put("sensors", sensors);
        Map<String, Object> result =
                AgentBridge.claim(request, SideEffectsBehaviors.capture, SideEffectsBehaviors.reopen);
        System.out.println("CLAIM=" + result.get("status"));
        SideEffectsBehaviors.generation = (Long) result.get("generation");
        SideEffectsBehaviors.token = (Long) result.get("token");
    }

    // ---- behaviors -------------------------------------------------------------------------------------------------

    static void socketConnect(Server server) throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(PAYLOAD.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            CONTEXT.remove();
        }
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        List<String> strings = interned();
        check(
                "a blocking Socket connect records its host and port, its time, and its call site, never a byte ("
                        + describe(RECORDS) + ")",
                connect != null
                        && ("127.0.0.1:" + server.port()).equals(string(connect[SideEffects.R_TARGET]))
                        && connect[SideEffects.R_REQUEST] == REQUEST_BITS
                        && outcome(connect) == SideEffects.OUTCOME_CONNECTED
                        && connect[SideEffects.R_NANOS] > 0
                        && application(connect).startsWith("bootuiagentit.NetworkBehaviors#socketConnect")
                        && strings.stream().noneMatch(text -> text.contains(PAYLOAD)));
    }

    static void refusedConnect() throws Exception {
        RECORDS.clear();
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
            port = closed.getLocalPort();
        }
        CONTEXT.set(REQUEST);
        boolean refused = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), 2_000);
        } catch (java.io.IOException expected) {
            refused = true;
        } finally {
            CONTEXT.remove();
        }
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        check(
                "a refused connect records its failure (" + describe(RECORDS) + ")",
                refused && connect != null && outcome(connect) == SideEffects.OUTCOME_IO_ERROR);
    }

    static void nonBlockingConnect(Server server) throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(false);
            boolean connected = channel.connect(new InetSocketAddress(LOOPBACK, server.port()));
            CONTEXT.remove();
            // Finished on another thread, as a selector thread finishes it: the finish carries the connect's owner.
            Thread finisher = new Thread(() -> {
                try {
                    long deadline = System.currentTimeMillis() + 5_000;
                    while (!channel.finishConnect() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5);
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            if (!connected) {
                finisher.start();
                finisher.join();
            }
            long[] finish = await(kind(SideEffects.KIND_CONNECT_FINISH));
            long[] connect = first(kind(SideEffects.KIND_CONNECT));
            check(
                    "a non-blocking connect records pending, then its finish with its time and owner ("
                            + describe(RECORDS) + ")",
                    connected
                            || (connect != null
                                    && outcome(connect) == SideEffects.OUTCOME_PENDING
                                    && finish != null
                                    && outcome(finish) == SideEffects.OUTCOME_CONNECTED
                                    && finish[SideEffects.R_REQUEST] == REQUEST_BITS
                                    && finish[SideEffects.R_TARGET] == connect[SideEffects.R_TARGET]
                                    && finish[SideEffects.R_NANOS] > 0));
        } finally {
            CONTEXT.remove();
        }
    }

    static void adaptorConnect(Server server) throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        try (SocketChannel channel = SocketChannel.open()) {
            channel.socket().connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
        } finally {
            CONTEXT.remove();
        }
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        check(
                "SocketChannel.socket().connect records through blockingConnect (" + describe(RECORDS) + ")",
                connect != null
                        && outcome(connect) == SideEffects.OUTCOME_CONNECTED
                        && connect[SideEffects.R_REQUEST] == REQUEST_BITS);
    }

    static void jdkHttpClient() throws Exception {
        RECORDS.clear();
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/q?token=" + PAYLOAD))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            long[] connect = await(kind(SideEffects.KIND_CONNECT_FINISH).or(kind(SideEffects.KIND_CONNECT)));
            long[] any = first(kind(SideEffects.KIND_CONNECT));
            String client0 = any == null ? null : string((int) (any[SideEffects.R_FLAGS] >>> 32));
            check(
                    "a JDK HttpClient connect names its client, never the request's URI (" + describe(RECORDS) + " "
                            + client0 + ")",
                    connect != null
                            && client0 != null
                            && client0.startsWith("jdk.internal.net.http.")
                            && interned().stream().noneMatch(text -> text.contains(PAYLOAD) || text.contains("/q")));
        } finally {
            server.stop(0);
        }
    }

    static void httpUrlConnection() throws Exception {
        RECORDS.clear();
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                            .toURL()
                            .openConnection(java.net.Proxy.NO_PROXY);
            connection.getResponseCode();
            connection.disconnect();
            long[] connect = await(kind(SideEffects.KIND_CONNECT));
            String client = connect == null ? null : string((int) (connect[SideEffects.R_FLAGS] >>> 32));
            check(
                    "an HttpURLConnection connect names its client (" + describe(RECORDS) + " " + client + ")",
                    client != null && client.startsWith("sun.net."));
        } finally {
            server.stop(0);
        }
    }

    static void datagrams() throws Exception {
        RECORDS.clear();
        try (DatagramSocket receiver = new DatagramSocket(0, LOOPBACK);
                DatagramSocket sender = new DatagramSocket(0, LOOPBACK);
                DatagramChannel channel = DatagramChannel.open()) {
            byte[] payload = PAYLOAD.getBytes(StandardCharsets.UTF_8);
            CONTEXT.set(REQUEST);
            // A hot hook reads its owner from the slot an adapter's scope fills, never captures it itself.
            io.github.jdubois.bootui.agent.bridge.CodePaths.begin();
            for (int i = 0; i < 3; i++) {
                sender.send(new DatagramPacket(payload, payload.length, LOOPBACK, receiver.getLocalPort()));
            }
            for (int i = 0; i < 2; i++) {
                channel.send(ByteBuffer.wrap(payload), new InetSocketAddress(LOOPBACK, receiver.getLocalPort()));
            }
            io.github.jdubois.bootui.agent.bridge.CodePaths.end();
            CONTEXT.remove();
            await(records -> count(records, SideEffects.KIND_DATAGRAM) >= 5);
            check(
                    "datagram sends record their target once with frames and count the rest, never a byte ("
                            + describe(RECORDS) + ")",
                    count(RECORDS, SideEffects.KIND_DATAGRAM) == 5
                            && RECORDS.stream()
                                    .filter(record -> record[SideEffects.R_KIND] == SideEffects.KIND_DATAGRAM)
                                    .allMatch(record -> ("127.0.0.1:" + receiver.getLocalPort())
                                                    .equals(string(record[SideEffects.R_TARGET]))
                                            && record[SideEffects.R_REQUEST] == REQUEST_BITS
                                            && outcome(record) == SideEffects.OUTCOME_SENT
                                            && (int) record[SideEffects.R_FRAMES] != 0)
                            && interned().stream().noneMatch(text -> text.contains(PAYLOAD)));
        } finally {
            CONTEXT.remove();
        }
    }

    static void lookups() throws Exception {
        RECORDS.clear();
        String name = null;
        long[] lookup = null;
        CONTEXT.set(REQUEST);
        try {
            // A spelling the self-test resolved moments ago is cached: another one is tried.
            for (int attempt = 0; attempt < 5 && lookup == null; attempt++) {
                RECORDS.clear();
                name = spelling();
                InetAddress.getAllByName(name);
                Thread.sleep(100);
                drain();
                lookup = first(kind(SideEffects.KIND_LOOKUP));
            }
            InetAddress.getAllByName(name);
            InetAddress.getAllByName("127.0.0.1");
        } finally {
            CONTEXT.remove();
        }
        Thread.sleep(200);
        drain();
        check(
                "a lookup the JVM's cache missed records its resolution time, a cached one and a literal record nothing ("
                        + describe(RECORDS) + ")",
                lookup != null
                        && name.equals(string(lookup[SideEffects.R_TARGET]))
                        && outcome(lookup) == SideEffects.OUTCOME_RESOLVED
                        && lookup[SideEffects.R_REQUEST] == REQUEST_BITS
                        && lookup[SideEffects.R_NANOS] > 0
                        && count(RECORDS, SideEffects.KIND_LOOKUP) == 1);
    }

    static void unowned(Server server) throws Exception {
        RECORDS.clear();
        Thread worker = new Thread(
                () -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                "sdk-worker-42");
        worker.start();
        worker.join();
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        check(
                "unowned work names its thread family (" + describe(RECORDS) + ")",
                connect != null
                        && connect[SideEffects.R_REQUEST] == 0L
                        && "sdk-worker-{n}".equals(string((int) ((connect[SideEffects.R_FLAGS] >>> 16) & 0xFFFF))));
    }

    static void bootUiWork(Server server) throws Exception {
        RECORDS.clear();
        boolean previous = AgentBridge.bootUiWork(true);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
        } finally {
            AgentBridge.bootUiWork(previous);
        }
        Thread.sleep(200);
        drain();
        check("BootUI's own work is never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void bootUiThread(Server server) throws Exception {
        RECORDS.clear();
        Thread thread = new Thread(
                () -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                "bootui-http-1");
        thread.start();
        thread.join();
        Thread.sleep(200);
        drain();
        check("a bootui- thread's connect is never recorded (" + describe(RECORDS) + ")", RECORDS.isEmpty());
    }

    static void userInformation(Server server) throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        try (Socket socket = new Socket(java.net.Proxy.NO_PROXY)) {
            socket.connect(InetSocketAddress.createUnresolved("user:hunter2-secret@localhost", server.port()), 1_000);
        } catch (java.io.IOException expected) {
            // Unresolved: refused before any I/O, and recorded.
        } finally {
            CONTEXT.remove();
        }
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        check(
                "user information never reaches a target (" + describe(RECORDS) + ")",
                connect != null
                        && ("localhost:" + server.port()).equals(string(connect[SideEffects.R_TARGET]))
                        && interned().stream().noneMatch(text -> text.contains("hunter2")));
    }

    static void releaseRestores() throws Exception {
        AgentBridge.release("network-behaviors", "dev");
        Object state = SideEffectsBehaviors.awaitState(SideEffects.NETWORK, "released");
        Map<String, String> steps = new LinkedHashMap<>();
        SideEffects.beginSelfTest();
        Map<String, Object> hits;
        try {
            try (Socket socket = new Socket(java.net.Proxy.NO_PROXY)) {
                socket.connect(InetSocketAddress.createUnresolved("bootui-after-release.invalid", 9), 1);
            } catch (java.io.IOException expected) {
                steps.put("socket", "refused");
            }
            InetAddress.getAllByName(spelling());
        } finally {
            hits = SideEffects.endSelfTest();
        }
        check(
                "release restores the network classes (" + state + ", " + hits + ")",
                "released".equals(state)
                        && Long.valueOf(0L).equals(hits.get("Socket.connect"))
                        && Long.valueOf(0L).equals(hits.get("InetAddress.lookup")));
    }

    /** Mockito's inline mock maker mocks {@code Socket}: the stubbed connect never reaches the hook, a real one records. */
    static void mockito(Socket mock, Server server) throws Exception {
        RECORDS.clear();
        CONTEXT.set(REQUEST);
        mock.connect(new InetSocketAddress(LOOPBACK, 1), 1);
        Thread.sleep(200);
        drain();
        int afterMock = RECORDS.size();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
        } finally {
            CONTEXT.remove();
        }
        long[] connect = await(kind(SideEffects.KIND_CONNECT));
        org.mockito.Mockito.verify(mock)
                .connect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1));
        // When the sensor's advice wraps Mockito's, as when Mockito transformed Socket first, the mock's call is seen.
        System.out.println("MOCKITO=ok mockRecords=" + afterMock);
        check(
                "a real Socket beside a Mockito mock still records (" + describe(RECORDS) + ")",
                connect != null && outcome(connect) == SideEffects.OUTCOME_CONNECTED);
    }

    // ---- overhead ----------------------------------------------------------------------------------------------

    /**
     * Each operation's mean cost with the sensor recording and switched off, alternating five rounds: a loopback
     * connect, a datagram send, and a lookup the JVM's cache misses ({@code -Dsun.net.inetaddr.ttl=0}).
     */
    static void overhead(Server server) throws Exception {
        CONTEXT.set(REQUEST);
        try (DatagramSocket receiver = new DatagramSocket(0, LOOPBACK);
                DatagramSocket sender = new DatagramSocket(0, LOOPBACK)) {
            byte[] bytes = new byte[16];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, LOOPBACK, receiver.getLocalPort());
            receiver.setReceiveBufferSize(1 << 20);
            Operation connect = () -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(LOOPBACK, server.port()), 5_000);
                }
            };
            Operation send = () -> sender.send(packet);
            Operation lookup = () -> InetAddress.getAllByName("localhost");
            Map<String, long[]> totals = new LinkedHashMap<>();
            for (String name : List.of("connect", "datagram", "lookup")) {
                totals.put(name, new long[2]);
            }
            for (int round = 0; round < 6; round++) {
                for (int on = 0; on < 2; on++) {
                    if (on == 1) {
                        SideEffects.enable(SideEffects.MASK_NETWORK);
                    } else {
                        SideEffects.disable(SideEffects.MASK_NETWORK, null);
                    }
                    long connectNanos = time(connect, 300);
                    long sendNanos = time(send, 3_000);
                    long lookupNanos = time(lookup, 300);
                    if (round > 0) {
                        // The first round warms both states up.
                        totals.get("connect")[on] += connectNanos;
                        totals.get("datagram")[on] += sendNanos;
                        totals.get("lookup")[on] += lookupNanos;
                    }
                    SideEffects.flushThread();
                    drain();
                    RECORDS.clear();
                }
            }
            SideEffects.enable(SideEffects.MASK_NETWORK);
            for (Map.Entry<String, long[]> entry : totals.entrySet()) {
                long off = entry.getValue()[0] / 5;
                long on = entry.getValue()[1] / 5;
                System.out.println("OVERHEAD_" + entry.getKey() + "=off=" + off + " on=" + on + " delta=" + (on - off)
                        + " percent=" + String.format(Locale.ROOT, "%.1f", 100.0 * (on - off) / Math.max(1, off)));
            }
        } finally {
            CONTEXT.remove();
        }
    }

    interface Operation {
        void run() throws Exception;
    }

    /** The mean nanoseconds of {@code operation} over {@code times} runs. */
    static long time(Operation operation, int times) throws Exception {
        long started = System.nanoTime();
        for (int i = 0; i < times; i++) {
            operation.run();
        }
        return (System.nanoTime() - started) / times;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** {@code localhost} with a random mix of cases, so the JVM's case-sensitive address cache misses it. */
    static String spelling() {
        String name = "localhost";
        int bits = ThreadLocalRandom.current().nextInt(1, 1 << name.length());
        StringBuilder spelled = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            spelled.append((bits & (1 << i)) != 0 ? Character.toUpperCase(name.charAt(i)) : name.charAt(i));
        }
        return spelled.toString();
    }

    static Predicate<List<long[]>> kind(int kind) {
        return records -> records.stream()
                .anyMatch(record -> record[SideEffects.R_SENSOR] == SideEffects.SENSOR_NETWORK
                        && record[SideEffects.R_KIND] == kind);
    }

    static long count(List<long[]> records, int kind) {
        return records.stream()
                .filter(record -> record[SideEffects.R_KIND] == kind)
                .mapToLong(record -> record[SideEffects.R_COUNT])
                .sum();
    }

    static long[] await(Predicate<List<long[]>> done) throws Exception {
        for (int i = 0; i < 300; i++) {
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

    static String application(long[] record) {
        String frame = string((int) record[SideEffects.R_FRAMES]);
        return frame == null ? "" : frame;
    }

    static String describe(List<long[]> records) {
        List<String> described = new ArrayList<>();
        for (long[] record : records) {
            described.add("kind=" + record[SideEffects.R_KIND] + " target=" + string(record[SideEffects.R_TARGET])
                    + " outcome=" + outcome(record) + " request=" + Long.toHexString(record[SideEffects.R_REQUEST])
                    + " count=" + record[SideEffects.R_COUNT] + " nanos=" + record[SideEffects.R_NANOS] + " client="
                    + string((int) (record[SideEffects.R_FLAGS] >>> 32)) + " thread="
                    + string((int) ((record[SideEffects.R_FLAGS] >>> 16) & 0xFFFF)) + " app=" + application(record));
        }
        return described.toString();
    }

    /** A loopback server accepting connections and reading what they send, without answering. */
    static final class Server implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread acceptor;

        Server() throws Exception {
            socket = new ServerSocket(0, 512, LOOPBACK);
            acceptor = new Thread(
                    () -> {
                        while (!socket.isClosed()) {
                            try (Socket accepted = socket.accept()) {
                                accepted.setSoTimeout(200);
                                InputStream in = accepted.getInputStream();
                                try {
                                    in.read(new byte[64]);
                                } catch (java.io.IOException timeout) {
                                    // Nothing sent.
                                }
                            } catch (java.io.IOException closed) {
                                return;
                            }
                        }
                    },
                    "network-behaviors-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    /** JFR's socket events, recorded with no threshold, so the JDK's own socket instrumentation runs beside the sensor. */
    static final class JfrSockets {

        static Object start() {
            jdk.jfr.Recording recording = new jdk.jfr.Recording();
            recording.enable("jdk.SocketRead").withoutThreshold();
            recording.enable("jdk.SocketWrite").withoutThreshold();
            recording.start();
            return recording;
        }

        static String stop(Object handle) throws Exception {
            jdk.jfr.Recording recording = (jdk.jfr.Recording) handle;
            recording.stop();
            java.nio.file.Path file = java.nio.file.Files.createTempFile("bootui-network", ".jfr");
            recording.dump(file);
            recording.close();
            long writes = jdk.jfr.consumer.RecordingFile.readAllEvents(file).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.SocketWrite"))
                    .count();
            java.nio.file.Files.deleteIfExists(file);
            return writes > 0 ? "ok writes=" + writes : "no socket write recorded";
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
