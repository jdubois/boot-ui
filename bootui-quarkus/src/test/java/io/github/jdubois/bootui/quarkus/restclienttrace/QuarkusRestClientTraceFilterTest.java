package io.github.jdubois.bootui.quarkus.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QuarkusRestClientTraceFilterTest {

    @Test
    void capturesSanitizedMetadataWithoutReadingHeadersOrEntities() {
        RestClientTraceRecorder recorder = recorder(true);
        recorder.setTraceIdProvider(() -> "trace-123");
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request(
                "POST",
                URI.create("https://alice:password@example.test/orders/eyJabcdefgh.abcdefgh.abcdef"
                        + "?api%2Dtoken=top-secret&safe=visible&access-token#fragment"));

        filter.filter(request);
        filter.filter(request, response(503));

        assertThat(recorder.recent()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri())
                    .isEqualTo("https://example.test/orders/******?api%2Dtoken=******&safe=visible&******")
                    .doesNotContain("alice", "password", "top-secret", "fragment");
            assertThat(call.host()).isEqualTo("example.test");
            assertThat(call.path()).isEqualTo("/orders/******");
            assertThat(call.status()).isEqualTo(503);
            assertThat(call.success()).isTrue();
            assertThat(call.errorMessage()).isNull();
            assertThat(call.clientType()).isEqualTo("Quarkus REST Client Reactive");
            assertThat(call.requestHeaders()).isEmpty();
            assertThat(call.traceId()).isEqualTo("trace-123");
        });
    }

    @Test
    void captureFailuresNeverEscapeIntoTheApplicationCall() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request("GET", URI.create("http://localhost/ping"));

        filter.filter(request);

        assertThatCode(() -> filter.filter(request, failingResponse())).doesNotThrowAnyException();
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void aTransportFailureWithoutAResponseDoesNotCreateAnEntry() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);

        filter.filter(request("GET", URI.create("http://127.0.0.1:1/unreachable")));

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void capturesQuarkusStatusZeroAsATransportFailureWithoutAnHttpStatus() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request("GET", URI.create("http://127.0.0.1:1/unreachable"));

        filter.filter(request);
        filter.filter(request, response(0));

        assertThat(recorder.recent()).singleElement().satisfies(call -> {
            assertThat(call.status()).isNull();
            assertThat(call.success()).isFalse();
            assertThat(call.errorMessage()).contains("transport failed");
        });
    }

    @Test
    void retainsTheTraceIdActiveAtRequestTimeWhenResponseContextIsDetached() {
        RestClientTraceRecorder recorder = recorder(false);
        AtomicReference<String> currentTrace = new AtomicReference<>("request-trace");
        recorder.setTraceIdProvider(currentTrace::get);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request("GET", URI.create("http://localhost/ping"));

        filter.filter(request);
        currentTrace.set(null);
        filter.filter(request, response(200));

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(call -> assertThat(call.traceId()).isEqualTo("request-trace"));
    }

    private static RestClientTraceRecorder recorder(boolean captureHeaders) {
        return new RestClientTraceRecorder(true, true, captureHeaders, false, 20, 1000, 2000, 200, 5);
    }

    private static ClientRequestContext request(String method, URI uri) {
        Map<String, Object> properties = new HashMap<>();
        return (ClientRequestContext) Proxy.newProxyInstance(
                ClientRequestContext.class.getClassLoader(),
                new Class<?>[] {ClientRequestContext.class},
                (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "getMethod" -> method;
                    case "getUri" -> uri;
                    case "getProperty" -> properties.get(args[0]);
                    case "setProperty" -> {
                        properties.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "removeProperty" -> {
                        properties.remove(args[0]);
                        yield null;
                    }
                    case "getHeaders", "getStringHeaders", "getEntity", "getEntityStream", "hasEntity" ->
                        throw new AssertionError("The metadata-only filter must not access " + invoked.getName());
                    case "toString" -> "ClientRequestContext test proxy";
                    default -> defaultValue(invoked.getReturnType());
                });
    }

    private static ClientResponseContext response(int status) {
        return (ClientResponseContext) Proxy.newProxyInstance(
                ClientResponseContext.class.getClassLoader(),
                new Class<?>[] {ClientResponseContext.class},
                (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "getStatus" -> status;
                    case "getHeaders", "getEntityStream", "hasEntity" ->
                        throw new AssertionError("The metadata-only filter must not access " + invoked.getName());
                    case "toString" -> "ClientResponseContext test proxy";
                    default -> defaultValue(invoked.getReturnType());
                });
    }

    private static ClientResponseContext failingResponse() {
        return (ClientResponseContext) Proxy.newProxyInstance(
                ClientResponseContext.class.getClassLoader(),
                new Class<?>[] {ClientResponseContext.class},
                (proxy, invoked, args) -> {
                    if ("getStatus".equals(invoked.getName())) {
                        throw new IllegalStateException("capture failure");
                    }
                    return defaultValue(invoked.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
