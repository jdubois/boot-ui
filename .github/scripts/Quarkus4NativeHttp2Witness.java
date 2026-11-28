import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Http2ClientConfig;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientConfig;
import io.vertx.core.http.HttpClientConnection;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnectOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.StreamResetException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-tool HTTP/2 witness. Arguments: native host port, actual API prefix, output JSON.
 *
 * <p>BOOTUI_NATIVE_HTTP2_OSV_WITNESS_DIR names an owned local driver's directory. Its atomically
 * published state.json has schema native-http2-osv-v1, a loopback baseUri and batches with integral
 * id, held, disconnected, released, responded and holdTimedOut fields. The single scan remains held
 * until its socket disconnects. Its sibling is an explicitly armed, immediate get_config call, not a
 * second scan: the real scanner's single-flight policy remains intact.
 */
public final class Quarkus4NativeHttp2Witness {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String VERSION = "2026-07-28";
    private static final long CANCELLATION_MILLIS = 1_500;

    private final int port;
    private final String api;
    private final Path output;
    private final Path osvDirectory;
    private final Map<String, Object> evidence;
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> connectionEvents = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> connectionFailure = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private Vertx vertx;
    private HttpClientAgent client;
    private HttpClientConnection connection;
    private SseCollector victim;
    private PendingJson sibling;

    private Quarkus4NativeHttp2Witness(
            int port, String api, Path output, Path osvDirectory, Map<String, Object> evidence) {
        this.port = port;
        this.api = api;
        this.output = output;
        this.osvDirectory = osvDirectory;
        this.evidence = evidence;
        evidence.put("requests", requests);
        evidence.put("connectionEvents", connectionEvents);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            System.err.println("Usage: Quarkus4NativeHttp2Witness PORT API_PREFIX OUTPUT_JSON");
            System.exit(2);
            return;
        }
        Path output = Path.of(arguments[2]).toAbsolutePath().normalize();
        require(!Files.exists(output), "Refusing to overwrite an existing witness result");
        Files.createDirectories(output.getParent());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", "native-http2-mcp-witness-v1");
        evidence.put("status", "running");
        evidence.put("startedAt", Instant.now().toString());
        evidence.put("javaVersion", System.getProperty("java.version"));
        evidence.put("vertxLibrary", library(Vertx.class));
        evidence.put("jacksonLibrary", library(JsonMapper.class));
        evidence.put("nativeProvenance", "The owning driver must retain the executed native binary hash/build.");
        Quarkus4NativeHttp2Witness witness = null;
        Throwable failure = null;
        boolean completed = false;
        try {
            int port = Integer.parseInt(arguments[0]);
            require(port > 0 && port <= 65535, "Invalid native host port");
            String api = arguments[1];
            URI path = URI.create(api);
            require(api.startsWith("/") && !api.endsWith("/") && !api.contains("//")
                            && !api.contains("/../") && path.getScheme() == null
                            && path.getRawAuthority() == null && api.equals(path.getRawPath())
                            && path.normalize().equals(path),
                    "Supply a normalized API prefix without query, fragment or authority");
            String directory = System.getenv("BOOTUI_NATIVE_HTTP2_OSV_WITNESS_DIR");
            require(directory != null && !directory.isBlank(), "An owned OSV observation directory is required");
            Path osvDirectory = Path.of(directory);
            require(osvDirectory.isAbsolute() && Files.isDirectory(osvDirectory),
                    "The OSV observation directory must already exist and be absolute");
            witness = new Quarkus4NativeHttp2Witness(port, api, output, osvDirectory, evidence);
            witness.run();
            completed = true;
        } catch (Exception | AssertionError | LinkageError problem) {
            failure = problem;
        } finally {
            if (witness != null) {
                try {
                    witness.close();
                } catch (Exception cleanup) {
                    if (failure == null) {
                        failure = cleanup;
                    } else {
                        failure.addSuppressed(cleanup);
                    }
                }
                witness.retainStreams();
            }
            evidence.put("finishedAt", Instant.now().toString());
            evidence.put("status", completed && failure == null ? "passed" : "failed");
            if (!completed && failure == null) {
                evidence.put("error", "The witness did not complete");
            }
            if (failure != null) {
                evidence.put("error", failure.toString());
                evidence.put("cleanupErrors",
                        java.util.Arrays.stream(failure.getSuppressed()).map(Throwable::toString).toList());
                failure.printStackTrace(System.err);
            }
            save(output, evidence);
        }
        System.exit(completed && failure == null ? 0 : 1);
    }

    private void run() throws Exception {
        JsonNode initialOsv = osvState();
        require(initialOsv.path("batches").size() == 0, "The owned OSV driver must start without existing batches");
        String baseUri = initialOsv.path("baseUri").asString();
        URI base = URI.create(baseUri);
        require("http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost())
                        && base.getPort() > 0 && base.getPort() <= 65535 && base.getRawUserInfo() == null
                        && base.getRawQuery() == null && base.getRawFragment() == null
                        && (base.getRawPath().isEmpty() || "/".equals(base.getRawPath())),
                "OSV witness work must target an explicitly owned loopback HTTP server");
        evidence.put("osvBaseUri", baseUri);
        evidence.put("phase", "opening-prior-knowledge-http2-connection");
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(1));
        vertx.exceptionHandler(problem -> connectionFailure.compareAndSet(null, problem));
        client = vertx.createHttpClient(new HttpClientConfig()
                .setVersions(HttpVersion.HTTP_2)
                .setHttp2Config(new Http2ClientConfig().setClearTextUpgrade(false)));
        connection = await(client.connect(new HttpConnectOptions()
                .setHost("127.0.0.1").setPort(port).setSsl(false).setProtocolVersion(HttpVersion.HTTP_2)), 5_000);
        connection.goAwayHandler(event -> connectionEvents.add(Map.of(
                "event", "GOAWAY", "duringWitness", !closing.get(), "errorCode", event.getErrorCode(),
                "lastStreamId", event.getLastStreamId())));
        connection.closeHandler(ignored -> connectionEvents.add(Map.of(
                "event", "close", "duringWitness", !closing.get())));
        connection.exceptionHandler(problem -> {
            if (!closing.get()) {
                connectionFailure.compareAndSet(null, problem);
            }
        });
        require(connection.protocolVersion() == HttpVersion.HTTP_2 && !connection.isSsl(),
                "The explicitly opened connection is not prior-knowledge cleartext HTTP/2");
        evidence.put("physicalConnection", Map.of(
                "identity", System.identityHashCode(connection), "protocol", connection.protocolVersion().name(),
                "local", connection.localAddress().toString(), "remote", connection.remoteAddress().toString(),
                "explicitConnectionsOpened", 1, "clearTextUpgrade", false));
        save(output, evidence);

        JsonNode configReply = jsonCall("http2-config", "get_config",
                Map.of("query", "bootui.vulnerabilities", "limit", 25), true, 5_000);
        JsonNode config = successfulTool(configReply, "http2-config");
        Map<String, String> settings = new LinkedHashMap<>();
        for (JsonNode property : config.path("properties")) {
            require(property.path("masked").isBoolean() && !property.path("masked").booleanValue(),
                    "The local scan settings must be observable, not guessed from masked values");
            settings.put(property.path("name").asString(), property.path("value").asString());
        }
        Map<String, String> required = Map.of(
                "bootui.vulnerabilities.osv-base-uri", baseUri,
                "bootui.vulnerabilities.epss-base-uri", baseUri,
                "bootui.vulnerabilities.epss-enabled", "false",
                "bootui.vulnerabilities.max-packages", "2",
                "bootui.vulnerabilities.max-advisories", "2",
                "bootui.vulnerabilities.request-timeout", "PT6S",
                "bootui.vulnerabilities.scan-timeout", "PT8S");
        require(required.entrySet().stream().allMatch(entry -> entry.getValue().equals(settings.get(entry.getKey()))),
                "The native scanner is not configured for the bounded local OSV witness");
        evidence.put("immediateRead", Map.of("tool", "get_config", "progressTokenSupplied", true,
                "contentType", "application/json", "response", configReply));
        evidence.put("scanSettings", settings);

        JsonNode rootConfig = successfulTool(jsonCall("http2-root", "get_config",
                Map.of("query", "quarkus.http.root-path", "limit", 25), false, 5_000), "http2-root");
        String root = configValue(rootConfig, "quarkus.http.root-path");
        URI rootPath = URI.create(root);
        require(root.startsWith("/") && !root.contains("//") && !root.contains("/../")
                        && rootPath.getScheme() == null && rootPath.getRawAuthority() == null
                        && root.equals(rootPath.getRawPath()) && rootPath.normalize().equals(rootPath),
                "The fixture root must be obtained from the real normalized host configuration");
        String fixture = (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + "/native-fixture";
        JsonNode slowBefore = slowCounters(fixtureCall(HttpMethod.GET, fixture + "/probe", "http2-probe-before"));
        evidence.put("fixturePrefix", fixture);
        evidence.put("phase", "starting-real-scan-victim");
        AtomicBoolean reset = new AtomicBoolean();
        victim = new SseCollector("http2-victim", "http2-victim-token", reset);
        HttpClientRequest victimRequest = openStream(victim);
        await(victim.firstProgress, 3_000);
        waitBatches(1, 3_000);
        JsonNode heldVictim = osvState().path("batches").get(0);
        int victimId = batchId(heldVictim);
        requireHeld(heldVictim);

        require("armed".equals(fixtureCall(HttpMethod.POST, fixture + "/slow-config", "http2-arm")
                        .path("operation").asString()),
                "The fixture did not explicitly arm its bounded slow configuration operation");
        evidence.put("phase", "waiting-for-immediate-config-sibling");
        sibling = beginJsonCall("http2-sibling", "get_config",
                Map.of("query", "fixture.slow-witness", "limit", 25), true);
        long enteredDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        JsonNode slowDuring;
        do {
            healthyConnection();
            sibling.requirePending();
            slowDuring = slowCounters(fixtureCall(HttpMethod.GET, fixture + "/probe", "http2-probe-entered"));
            if (slowDuring.path("entered").asInt() == slowBefore.path("entered").asInt() + 1) {
                break;
            }
            require(System.nanoTime() < enteredDeadline, "The actual immediate get_config sibling did not enter");
            Thread.sleep(10);
        } while (true);
        require(slowDuring.path("completed").asInt() == slowBefore.path("completed").asInt()
                        && slowDuring.path("interrupted").asInt() == slowBefore.path("interrupted").asInt(),
                "The slow sibling completed or was interrupted before victim cancellation");
        JsonNode heldState = osvState();
        require(heldState.path("batches").size() == 1,
                "The real single-flight scanner must have exactly one OSV batch");
        require(victimRequest.streamId() != sibling.request.streamId() && sibling.request.streamId() > 0
                        && sibling.request.connection() == connection && sibling.request.version() == HttpVersion.HTTP_2,
                "The actual scan and immediate config sibling must be distinct streams on the same HTTP/2 connection");
        requireHeld(batch(heldState, victimId));
        victim.healthy();
        sibling.requirePending();
        require(!victim.finalResult.isDone(), "The victim scan must still be in flight");
        evidence.put("beforeReset", Map.of("osv", heldState, "slowConfig", slowDuring,
                "siblingResponseHeadersPending", true, "siblingBodyPending", true));
        evidence.put("phase", "cancelling-only-the-scan-stream");
        save(output, evidence);

        long cancelledAt = System.nanoTime();
        long deadline = cancelledAt + TimeUnit.MILLISECONDS.toNanos(CANCELLATION_MILLIS);
        reset.set(true);
        await(victimRequest.reset(8), remaining(deadline));
        JsonNode cancelled;
        do {
            healthyConnection();
            victim.healthy();
            sibling.requirePending();
            cancelled = batch(osvState(), victimId);
            if (cancelled.path("disconnected").booleanValue()) {
                break;
            }
            require(System.nanoTime() < deadline, "Victim OSV socket did not close within 1500ms");
            Thread.sleep(10);
        } while (true);
        require(!cancelled.path("released").booleanValue() && !cancelled.path("responded").booleanValue()
                        && !cancelled.path("holdTimedOut").booleanValue(),
                "Socket cancellation cannot be inferred from release, response or timeout");
        JsonNode overview = jsonCall("http2-permit-reuse", "get_overview", Map.of(), false, remaining(deadline));
        JsonNode overviewPayload = successfulTool(overview, "http2-permit-reuse");
        require("bootui-native-acceptance".equals(overviewPayload.path("applicationName").asString())
                        && overviewPayload.path("serverPort").asInt() == port,
                "The freed permit did not execute a real same-connection overview request");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);
        sibling.requirePending();
        require(elapsed <= CANCELLATION_MILLIS,
                "The admission permit was not reused within 1500ms while the sibling was pending");
        require(!victim.finalResult.isDone(), "A reset victim must not deliver a successful final result");
        evidence.put("cancellation", Map.of("resetCode", 8, "elapsedMillis", elapsed,
                "victimBatchId", victimId, "observedVictim", cancelled,
                "siblingPending", true, "siblingResponseHeadersPending", true, "permitReuse", overview));
        evidence.put("phase", "awaiting-successful-immediate-config-sibling");
        save(output, evidence);

        JsonNode finalReply = await(sibling.body.result, 10_000);
        JsonNode configSibling = successfulTool(finalReply, "http2-sibling");
        require("bounded-host-config-witness".equals(configValue(configSibling, "fixture.slow-witness")),
                "The immediate get_config sibling did not return its actual host configuration value");
        JsonNode slowAfter = slowCounters(fixtureCall(HttpMethod.GET, fixture + "/probe", "http2-probe-after"));
        require(slowAfter.path("entered").asInt() == slowBefore.path("entered").asInt() + 1
                        && slowAfter.path("completed").asInt() == slowBefore.path("completed").asInt() + 1
                        && slowAfter.path("interrupted").asInt() == slowBefore.path("interrupted").asInt(),
                "Resetting the scan interrupted or prevented completion of the immediate config sibling");
        healthyConnection();
        sibling.body.healthy();
        victim.healthy();
        JsonNode followUp = jsonCall("http2-follow-up", "get_overview", Map.of(), false, 5_000);
        require("bootui-native-acceptance".equals(
                        successfulTool(followUp, "http2-follow-up").path("applicationName").asString()),
                "The actual same-connection follow-up did not execute after sibling completion");
        evidence.put("siblingFinal", finalReply);
        evidence.put("slowConfig", Map.of("before", slowBefore, "during", slowDuring, "after", slowAfter));
        evidence.put("followUp", followUp);
        evidence.put("phase", "awaiting-matching-http2-ping-ack");
        save(output, evidence);
        Buffer ping = Buffer.buffer("h2-proof");
        CompletableFuture<Buffer> pong = new CompletableFuture<>();
        long pingStarted = System.nanoTime();
        // Vert.x registers its pong promise after writing: keep both operations on its one event loop.
        vertx.runOnContext(ignored -> {
            try {
                connection.ping(ping).onComplete(result -> {
                    if (result.succeeded()) {
                        pong.complete(result.result());
                    } else {
                        pong.completeExceptionally(result.cause());
                    }
                });
            } catch (RuntimeException | LinkageError problem) {
                pong.completeExceptionally(problem);
            }
        });
        Buffer acknowledgment = await(pong, 3_000);
        require(java.util.Arrays.equals(ping.getBytes(), acknowledgment.getBytes()),
                "The native server did not acknowledge the exact HTTP/2 PING payload");
        evidence.put("ping", Map.of("eventLoopSubmission", true,
                "payloadHex", HexFormat.of().formatHex(ping.getBytes()),
                "acknowledgmentHex", HexFormat.of().formatHex(acknowledgment.getBytes()),
                "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pingStarted)));
        healthyConnection();
        JsonNode finalState = osvState();
        JsonNode finalVictim = batch(finalState, victimId);
        require(finalVictim.path("disconnected").booleanValue() && !finalVictim.path("released").booleanValue()
                        && !finalVictim.path("responded").booleanValue() && !finalVictim.path("holdTimedOut").booleanValue(),
                "The cancelled victim OSV request must not be released, answered or timed out");
        require(finalState.path("batches").size() == 1, "Unexpected additional OSV batches");
        evidence.put("osvFinal", finalState);
        evidence.put("singleFlightPreserved", true);
        evidence.put("samePhysicalConnection", true);
        evidence.put("goAwayBeforeOwnedClose", false);
        evidence.put("connectionClosedBeforeOwnedClose", false);
        evidence.put("phase", "completed");
    }

    private HttpClientRequest openStream(SseCollector collector) throws Exception {
        HttpClientRequest request = request(HttpMethod.POST, api + "/mcp", "application/json, text/event-stream");
        rpcHeaders(request, "vulnerabilities_scan");
        request.exceptionHandler(collector::transportFailure);
        HttpClientResponse response = await(request
                .send(Buffer.buffer(JSON.writeValueAsString(payload(
                        collector.id, "vulnerabilities_scan", Map.of(), collector.token))))
                .map(HttpClientResponse::pause), 5_000);
        record(request, response, collector.id);
        require(response.statusCode() == 200 && response.getHeader("Content-Type") != null
                        && response.getHeader("Content-Type").startsWith("text/event-stream"),
                "A real progress tool did not negotiate modern SSE");
        response.exceptionHandler(collector::transportFailure);
        response.handler(collector::accept);
        response.endHandler(ignored -> collector.end());
        response.resume();
        return request;
    }

    private JsonNode jsonCall(
            String id, String tool, Map<String, Object> arguments, boolean progressToken, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        return await(beginJsonCall(id, tool, arguments, progressToken).body.result, remaining(deadline));
    }

    private PendingJson beginJsonCall(
            String id, String tool, Map<String, Object> arguments, boolean progressToken) throws Exception {
        HttpClientRequest request = request(HttpMethod.POST, api + "/mcp",
                progressToken ? "application/json, text/event-stream" : "application/json");
        rpcHeaders(request, tool);
        return collectJson(request,
                request.send(Buffer.buffer(JSON.writeValueAsString(
                        payload(id, tool, arguments, progressToken ? id : null)))).map(HttpClientResponse::pause), id);
    }

    private JsonNode fixtureCall(HttpMethod method, String path, String id) throws Exception {
        HttpClientRequest request = request(method, path, "application/json");
        PendingJson pending = collectJson(request,
                (method == HttpMethod.GET ? request.send() : request.send(Buffer.buffer("{}")))
                        .map(HttpClientResponse::pause), id);
        return await(pending.body.result, 2_000);
    }

    private PendingJson collectJson(HttpClientRequest request, Future<HttpClientResponse> headers, String id) {
        PendingJson pending = new PendingJson(request, headers.toCompletionStage().toCompletableFuture());
        headers.onComplete(received -> {
            if (received.failed()) {
                pending.body.fail(received.cause());
                return;
            }
            try {
                HttpClientResponse response = received.result();
                record(request, response, id);
                require(response.statusCode() == 200 && response.getHeader("Content-Type") != null
                                && response.getHeader("Content-Type").startsWith("application/json"),
                        "An immediate read must be HTTP 200 JSON, never an SSE surrogate");
                response.exceptionHandler(pending.body::fail);
                response.handler(pending.body::accept);
                response.endHandler(ignored -> pending.body.end());
                response.resume();
            } catch (RuntimeException | LinkageError problem) {
                pending.body.fail(problem);
            }
        });
        return pending;
    }

    static String configValue(JsonNode config, String name) {
        require(config.path("properties").isArray(), "Missing real configuration properties");
        for (JsonNode property : config.path("properties")) {
            if (name.equals(property.path("name").asString())) {
                require(property.path("masked").isBoolean() && !property.path("masked").booleanValue()
                                && property.path("value").isString(),
                        "The witness configuration value must be present and truthfully unmasked");
                return property.path("value").asString();
            }
        }
        throw new IllegalStateException("Missing witness configuration property " + name);
    }

    static JsonNode slowCounters(JsonNode probe) {
        JsonNode counters = probe.path("slowConfig");
        for (String field : List.of("entered", "interrupted", "completed")) {
            require(counters.path(field).isIntegralNumber() && counters.path(field).asInt() >= 0,
                    "The actual slow-config counter is unavailable: " + field);
        }
        return counters;
    }

    private HttpClientRequest request(HttpMethod method, String path, String accept) throws Exception {
        healthyConnection();
        return await(connection.request(method, path), 2_000)
                .putHeader("Accept", accept).putHeader("Content-Type", "application/json");
    }

    private static void rpcHeaders(HttpClientRequest request, String tool) {
        request.putHeader("MCP-Protocol-Version", VERSION)
                .putHeader("Mcp-Method", "tools/call").putHeader("Mcp-Name", tool);
    }

    static Map<String, Object> payload(String id, String tool, Map<String, Object> arguments, String token) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", VERSION);
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
        if (token != null) {
            meta.put("progressToken", token);
        }
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments, "_meta", meta));
    }

    private void record(HttpClientRequest request, HttpClientResponse response, String id) {
        require(request.connection() == connection && response.request().connection() == connection,
                "A request escaped the one explicitly opened physical connection");
        require(request.version() == HttpVersion.HTTP_2 && response.version() == HttpVersion.HTTP_2
                        && request.streamId() > 0,
                "An actual request/response was not negotiated HTTP/2");
        Map<String, Object> entry = new LinkedHashMap<>(Map.of("id", id, "path", request.path(), "streamId", request.streamId(),
                "sameConnection", true, "connectionIdentity", System.identityHashCode(request.connection()),
                "requestVersion", request.version().name(), "responseVersion", response.version().name(),
                "status", response.statusCode()));
        entry.put("contentType", response.getHeader("Content-Type"));
        requests.add(entry);
    }

    private JsonNode osvState() throws Exception {
        Path state = osvDirectory.resolve("state.json");
        require(Files.size(state) <= MAX_BYTES, "OSV state exceeded its observation bound");
        JsonNode value = JSON.readTree(Files.readAllBytes(state));
        require(value.isObject() && "native-http2-osv-v1".equals(value.path("schema").asString())
                        && value.path("batches").isArray(),
                "Missing truthful per-request OSV socket observations");
        return value;
    }

    private void waitBatches(int count, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (osvState().path("batches").size() != count) {
            healthyConnection();
            require(System.nanoTime() < deadline, "The actual OSV batch operation did not start");
            Thread.sleep(10);
        }
    }

    static JsonNode batch(JsonNode state, int id) {
        for (JsonNode value : state.path("batches")) {
            if (batchId(value) == id) {
                return value;
            }
        }
        throw new IllegalStateException("Missing OSV batch identity " + id);
    }

    static int batchId(JsonNode batch) {
        require(batch.path("id").isIntegralNumber() && batch.path("id").asInt() > 0,
                "Unknown OSV batch identity");
        return batch.path("id").asInt();
    }

    static void requireHeld(JsonNode batch) {
        for (String field : List.of("held", "disconnected", "released", "responded", "holdTimedOut")) {
            require(batch.path(field).isBoolean(), "An OSV socket observation is unavailable: " + field);
        }
        require(batch.path("held").booleanValue() && !batch.path("disconnected").booleanValue()
                        && !batch.path("released").booleanValue() && !batch.path("responded").booleanValue()
                        && !batch.path("holdTimedOut").booleanValue(),
                "The actual victim OSV request was not still held");
    }

    static JsonNode successfulTool(JsonNode reply, String id) {
        require(reply != null && reply.isObject() && "2.0".equals(reply.path("jsonrpc").asString())
                        && id.equals(reply.path("id").asString()) && !reply.has("error"),
                "MCP returned a protocol error or mismatched request identity");
        JsonNode result = reply.path("result");
        require("complete".equals(result.path("resultType").asString())
                        && result.path("isError").isBoolean() && !result.path("isError").booleanValue()
                        && result.path("structuredContent").isObject(),
                "MCP returned an incomplete, timed-out or in-band error result");
        JsonNode content = result.path("content");
        require(content.isArray() && content.size() == 1
                        && "text".equals(content.get(0).path("type").asString())
                        && content.get(0).path("text").isString()
                        && JSON.readTree(content.get(0).path("text").asString()).equals(result.path("structuredContent")),
                "MCP text and nested structured DTOs disagree");
        return result.path("structuredContent");
    }

    private void healthyConnection() {
        require(connectionFailure.get() == null, "The physical HTTP/2 connection failed: " + connectionFailure.get());
        require(connectionEvents.stream().noneMatch(event -> Boolean.TRUE.equals(event.get("duringWitness"))),
                "The physical connection received GOAWAY or closed during the witness");
    }

    private void retainStreams() {
        if (victim != null) {
            evidence.put("victimStream", victim.snapshot());
        }
        if (sibling != null) {
            evidence.put("immediateSibling", sibling.snapshot());
        }
    }

    private void close() throws Exception {
        closing.set(true);
        try {
            if (client != null) {
                await(client.close(), 5_000);
            }
        } finally {
            if (vertx != null) {
                await(vertx.close(), 5_000);
            }
        }
        if (victim != null) {
            victim.healthy();
        }
        if (sibling != null) {
            sibling.body.healthy();
        }
        evidence.put("ownedClientAndVertxClosed", true);
    }

    static final class PendingJson {
        final HttpClientRequest request;
        final CompletableFuture<HttpClientResponse> headers;
        final JsonBodyCollector body = new JsonBodyCollector();

        PendingJson(HttpClientRequest request, CompletableFuture<HttpClientResponse> headers) {
            this.request = request;
            this.headers = headers;
        }

        void requirePending() {
            body.healthy();
            require(!headers.isDone() && !body.result.isDone(),
                    "The immediate JSON sibling did not remain pending during victim cancellation");
        }

        Map<String, Object> snapshot() {
            return Map.of("tool", "get_config", "transport", "application/json", "streamId", request.streamId(),
                    "responseHeadersReceived", headers.isDone() && !headers.isCompletedExceptionally(),
                    "completeBodyReceived", body.result.isDone() && !body.result.isCompletedExceptionally(),
                    "bytes", body.bytes(), "connectionIdentity", System.identityHashCode(request.connection()));
        }
    }

    static final class JsonBodyCollector {
        final CompletableFuture<JsonNode> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        synchronized void accept(Buffer chunk) {
            if (body.size() + chunk.length() > MAX_BYTES) {
                fail(new IllegalStateException("JSON exceeded its bounded response budget"));
            } else if (!result.isDone()) {
                body.writeBytes(chunk.getBytes());
            }
        }

        synchronized void end() {
            try {
                JsonNode value = JSON.readTree(body.toByteArray());
                require(value != null && value.isObject(), "The immediate response was not a JSON object");
                result.complete(value);
            } catch (Exception problem) {
                fail(problem);
            }
        }

        void fail(Throwable problem) {
            failure.compareAndSet(null, problem);
            result.completeExceptionally(problem);
        }

        void healthy() {
            require(failure.get() == null, "The immediate JSON response failed: " + failure.get());
        }

        synchronized int bytes() {
            return body.size();
        }
    }

    static final class SseCollector {
        final String id;
        final String token;
        final AtomicBoolean reset;
        final CompletableFuture<JsonNode> firstProgress = new CompletableFuture<>();
        final CompletableFuture<JsonNode> finalResult = new CompletableFuture<>();
        final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final StringBuilder data = new StringBuilder();
        private final List<JsonNode> frames = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private int bytes;
        private String expectedReset;

        SseCollector(String id, String token, AtomicBoolean reset) {
            this.id = id;
            this.token = token;
            this.reset = reset;
        }

        synchronized void accept(Buffer chunk) {
            try {
                require(bytes + chunk.length() <= MAX_BYTES, "SSE exceeded its bounded byte budget");
                bytes += chunk.length();
                for (byte value : chunk.getBytes()) {
                    if (value == '\n') {
                        String text = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(line.toByteArray())).toString();
                        line.reset();
                        if (text.endsWith("\r")) {
                            text = text.substring(0, text.length() - 1);
                        }
                        if (text.isEmpty()) {
                            if (!data.isEmpty()) {
                                frame(JSON.readTree(data.toString()));
                                data.setLength(0);
                            }
                        } else if (text.startsWith("data:")) {
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(text.substring(5).stripLeading());
                        }
                    } else {
                        line.write(value);
                    }
                }
            } catch (Exception problem) {
                fail(problem);
            }
        }

        private void frame(JsonNode frame) {
            require("2.0".equals(frame.path("jsonrpc").asString()) && !frame.has("error"),
                    "An SSE frame was a malformed/protocol-error result");
            if ("notifications/progress".equals(frame.path("method").asString())) {
                require(token.equals(frame.path("params").path("progressToken").asString())
                                && frame.path("params").path("progress").isNumber() && !finalResult.isDone(),
                        "A progress frame has the wrong token or arrived after completion");
                frames.add(frame);
                firstProgress.complete(frame);
            } else {
                successfulTool(frame, id);
                require(!finalResult.isDone(), "The stream emitted duplicate final results");
                frames.add(frame);
                finalResult.complete(frame);
            }
        }

        synchronized void transportFailure(Throwable problem) {
            if (reset.get() && problem instanceof StreamResetException stream && stream.getCode() == 8) {
                expectedReset = problem.toString();
                ended.complete(null);
            } else {
                fail(problem);
            }
        }

        synchronized void end() {
            if (!reset.get() && (!finalResult.isDone() || !data.isEmpty() || line.size() != 0)) {
                fail(new IllegalStateException("SSE ended without exactly one complete final frame"));
            } else {
                ended.complete(null);
            }
        }

        private void fail(Throwable problem) {
            failure.compareAndSet(null, problem);
            firstProgress.completeExceptionally(problem);
            finalResult.completeExceptionally(problem);
            ended.completeExceptionally(problem);
        }

        void healthy() {
            require(failure.get() == null, "The MCP stream failed: " + failure.get());
        }

        boolean pending() {
            healthy();
            return !finalResult.isDone() && !ended.isDone();
        }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("progressToken", token);
            value.put("bytes", bytes);
            value.put("frames", List.copyOf(frames));
            value.put("finalReceived", finalResult.isDone() && !finalResult.isCompletedExceptionally());
            value.put("expectedReset", expectedReset);
            value.put("error", failure.get() == null ? null : failure.get().toString());
            return value;
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static long remaining(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        require(remaining > 0, "The bounded witness deadline expired");
        return remaining;
    }

    private static <T> T await(Future<T> future, long millis) throws Exception {
        return await(future.toCompletionStage().toCompletableFuture(), millis);
    }

    private static <T> T await(CompletableFuture<T> future, long millis) throws Exception {
        require(millis > 0, "Invalid bounded wait");
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    private static String library(Class<?> type) throws Exception {
        return type.getProtectionDomain().getCodeSource().getLocation().toURI().toString();
    }

    private static void save(Path output, Map<String, Object> evidence) throws Exception {
        Path staging = output.resolveSibling(output.getFileName() + ".part");
        Files.writeString(staging, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
