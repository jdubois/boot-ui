package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder.CapturedCall;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class RestClientTraceInterceptorTests {

    private RestClientTraceRecorder recorder() {
        return new RestClientTraceRecorder(true, true, true, false, 10, 1000, 2000, 200, 5);
    }

    private HttpRequest request(String method, String uri) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.valueOf(method));
        when(request.getURI()).thenReturn(URI.create(uri));
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-Id", "abc-123");
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    void recordsSuccessfulCallWithStatusAndHeaders() throws IOException {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceInterceptor interceptor = new RestClientTraceInterceptor(recorder, "RestTemplate");
        HttpRequest request = request("GET", "https://api.example.com/orders/42");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.host()).isEqualTo("api.example.com");
        assertThat(entry.path()).isEqualTo("/orders/42");
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.success()).isTrue();
        assertThat(entry.clientType()).isEqualTo("RestTemplate");
        assertThat(entry.requestHeaders()).containsEntry("X-Request-Id", "abc-123");
    }

    @Test
    void recordsFailureAndRethrowsWhenExecutionThrows() throws IOException {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceInterceptor interceptor = new RestClientTraceInterceptor(recorder, "RestTemplate");
        HttpRequest request = request("GET", "https://api.example.com/down");
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        IOException failure = new IOException("Connection refused");
        when(execution.execute(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isSameAs(failure);

        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorMessage()).isEqualTo("Connection refused");
        assertThat(entry.status()).isNull();
    }

    @Test
    void recordsGracefullyWhenStatusCodeExtractionFails() throws IOException {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceInterceptor interceptor = new RestClientTraceInterceptor(recorder, "RestTemplate");
        HttpRequest request = request("GET", "https://api.example.com/orders");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenThrow(new IOException("broken"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.status()).isNull();
        assertThat(entry.success()).isTrue();
    }

    @Test
    void neverDisruptsTheCallWhenCaptureFailsInternally() throws IOException {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceInterceptor interceptor = new RestClientTraceInterceptor(recorder, "RestTemplate");
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenThrow(new RuntimeException("boom"));
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void doesNotRecordWhenRecorderIsDisabled() throws IOException {
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(false, true, true, false, 10, 1000, 2000, 200, 5);
        RestClientTraceInterceptor interceptor = new RestClientTraceInterceptor(recorder, "RestTemplate");
        HttpRequest request = request("GET", "https://api.example.com/orders");
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        assertThat(recorder.recent()).isEmpty();
    }
}
