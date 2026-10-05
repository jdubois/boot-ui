package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder.CapturedCall;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RestClientTraceExchangeFilterTests {

    private RestClientTraceRecorder recorder() {
        return new RestClientTraceRecorder(true, true, true, false, 10, 1000, 2000, 200, 5);
    }

    private ClientRequest request(String uri) {
        return ClientRequest.create(HttpMethod.GET, URI.create(uri))
                .header("X-Request-Id", "abc-123")
                .build();
    }

    @Test
    void recordsSuccessfulExchangeWithStatusAndHeaders() {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceExchangeFilter filter = new RestClientTraceExchangeFilter(recorder);
        ClientRequest request = request("https://api.example.com/orders/42");
        ClientResponse response = mock(ClientResponse.class);
        when(response.statusCode()).thenReturn(HttpStatus.OK);
        ExchangeFunction next = req -> Mono.just(response);

        Mono<ClientResponse> result = filter.filter(request, next);

        StepVerifier.create(result).expectNext(response).verifyComplete();
        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.host()).isEqualTo("api.example.com");
        assertThat(entry.path()).isEqualTo("/orders/42");
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.success()).isTrue();
        assertThat(entry.clientType()).isEqualTo("WebClient");
        assertThat(entry.requestHeaders()).containsEntry("X-Request-Id", "abc-123");
    }

    @Test
    void recordsTransportFailureAndPropagatesTheError() {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceExchangeFilter filter = new RestClientTraceExchangeFilter(recorder);
        ClientRequest request = request("https://api.example.com/down");
        RuntimeException failure = new RuntimeException("Connection refused");
        ExchangeFunction next = req -> Mono.error(failure);

        Mono<ClientResponse> result = filter.filter(request, next);

        StepVerifier.create(result).expectErrorMatches(ex -> ex == failure).verify();
        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.success()).isFalse();
        assertThat(entry.errorMessage()).isEqualTo("Connection refused");
        assertThat(entry.status()).isNull();
    }

    @Test
    void recordsGracefullyWhenStatusCodeExtractionFails() {
        RestClientTraceRecorder recorder = recorder();
        RestClientTraceExchangeFilter filter = new RestClientTraceExchangeFilter(recorder);
        ClientRequest request = request("https://api.example.com/orders");
        ClientResponse response = mock(ClientResponse.class);
        when(response.statusCode()).thenThrow(new RuntimeException("broken"));
        ExchangeFunction next = req -> Mono.just(response);

        Mono<ClientResponse> result = filter.filter(request, next);

        StepVerifier.create(result).expectNext(response).verifyComplete();
        assertThat(recorder.recent()).hasSize(1);
        CapturedCall entry = recorder.recent().get(0);
        assertThat(entry.status()).isNull();
        assertThat(entry.success()).isTrue();
    }

    @Test
    void doesNotRecordWhenRecorderIsDisabled() {
        RestClientTraceRecorder recorder =
                new RestClientTraceRecorder(false, true, true, false, 10, 1000, 2000, 200, 5);
        RestClientTraceExchangeFilter filter = new RestClientTraceExchangeFilter(recorder);
        ClientRequest request = request("https://api.example.com/orders");
        ClientResponse response = mock(ClientResponse.class);
        when(response.statusCode()).thenReturn(HttpStatus.OK);
        ExchangeFunction next = req -> Mono.just(response);

        StepVerifier.create(filter.filter(request, next)).expectNext(response).verifyComplete();

        assertThat(recorder.recent()).isEmpty();
    }
}
