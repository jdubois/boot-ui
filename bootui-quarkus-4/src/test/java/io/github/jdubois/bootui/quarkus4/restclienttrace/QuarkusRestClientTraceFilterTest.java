package io.github.jdubois.bootui.quarkus4.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.quarkus4.nativeconsole.BootUiNativeActivation;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.Configuration;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuarkusRestClientTraceFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void clientBuiltBeforeFreezeKeepsAnInertFilterThatResolvesItsRecorderAfterAdmission() {
        RestClientBuilder builder = mock(RestClientBuilder.class);
        Configuration configuration = mock(Configuration.class);
        when(builder.getConfiguration()).thenReturn(configuration);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<RestClientTraceRecorder> handle = mock(InstanceHandle.class);
        RestClientTraceRecorder recorder = mock(RestClientTraceRecorder.class);
        when(container.isRunning()).thenReturn(true);
        when(container.instance(RestClientTraceRecorder.class)).thenReturn(handle);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(recorder);
        try (var activation = mockStatic(BootUiNativeActivation.class);
                var arc = mockStatic(Arc.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            arc.when(Arc::container).thenReturn(container);
            new QuarkusRestClientTraceListener().onNewClient(Object.class, builder);
            ArgumentCaptor<QuarkusRestClientTraceFilter> filters =
                    ArgumentCaptor.forClass(QuarkusRestClientTraceFilter.class);
            verify(builder).register(filters.capture(), eq(Integer.MAX_VALUE));
            filters.getValue().filter(null);
            filters.getValue().filter(null, null);
            arc.verifyNoInteractions();

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            filters.getValue().filter(mock(ClientRequestContext.class));
            verify(recorder).registerClientCustomization("Quarkus REST Client Reactive");
        }
    }

    @Test
    void filterRegisteredBeforeFreezeResolvesCaptureOnlyOnAnAdmittedCall() {
        RestClientTraceRecorder recorder = recorder(false);
        java.util.concurrent.atomic.AtomicInteger resolutions = new java.util.concurrent.atomic.AtomicInteger();
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(() -> {
            resolutions.incrementAndGet();
            return recorder;
        });
        try (var activation = mockStatic(BootUiNativeActivation.class)) {
            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(true);
            filter.filter(null);
            filter.filter(null, null);
            assertThat(resolutions).hasValue(0);

            activation.when(BootUiNativeActivation::isNativeInactive).thenReturn(false);
            ClientRequestContext request = request("GET", URI.create("http://localhost/orders"));
            filter.filter(request);
            filter.filter(request, response(200));
            assertThat(recorder.recent()).hasSize(1);
        }
    }

    @Test
    void capturesSanitizedMetadataWithoutReadingHeadersOrEntities() {
        RestClientTraceRecorder recorder = recorder(true);
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace("trace-123", null));
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
    void sanitizesSemicolonSeparatedAndMatrixParametersBeforeStorage() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request(
                "GET", URI.create("https://example.test/orders;token=pathsecret/42?page=2;api_key=querysecret"));

        filter.filter(request);
        filter.filter(request, response(200));

        assertThat(recorder.recent()).singleElement().satisfies(call -> {
            assertThat(call.uri())
                    .isEqualTo("https://example.test/orders;token=******/42?page=2;api_key=******")
                    .doesNotContain("pathsecret", "querysecret");
            assertThat(call.path()).isEqualTo("/orders;token=******/42");
        });
    }

    @Test
    void keepsAPathSegmentThatSharesASensitiveParameterName() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request = request("GET", URI.create("https://example.test/api/token/refresh;v=1"));

        filter.filter(request);
        filter.filter(request, response(200));

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(call -> assertThat(call.path()).isEqualTo("/api/token/refresh;v=1"));
    }

    @Test
    void sanitizesANestedCredentialBearingRedirectBeforeStorage() {
        RestClientTraceRecorder recorder = recorder(false);
        QuarkusRestClientTraceFilter filter = new QuarkusRestClientTraceFilter(recorder);
        ClientRequestContext request =
                request("GET", URI.create("https://example.test/go?redirect=https%3A%2F%2Falice%3Apw%40host%2Fcb"));

        filter.filter(request);
        filter.filter(request, response(200));

        assertThat(recorder.recent())
                .singleElement()
                .satisfies(call -> assertThat(call.uri())
                        .isEqualTo("https://example.test/go?redirect=******")
                        .doesNotContain("alice", "pw"));
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
        recorder.setCorrelationContextProvider(() -> BootUiCorrelation.current().withTrace(currentTrace.get(), null));
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
