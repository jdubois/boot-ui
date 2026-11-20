package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class BootUiMcpSseHttpMessageWriterTests {

    @ParameterizedTest
    @ValueSource(strings = {"/bootui/api/mcp", "/bootui/api/m%63p", "/bootui/api/mcp;v=1"})
    @SuppressWarnings("unchecked")
    void flushesEveryRawMcpFrameWithoutDelegating(String path) {
        HttpMessageWriter<DataBuffer> delegate = mock(HttpMessageWriter.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        AtomicInteger flushedBatches = new AtomicInteger();
        when(response.writeAndFlushWith(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            Publisher<? extends Publisher<? extends DataBuffer>> batches = invocation.getArgument(0);
            return Flux.from(batches)
                    .flatMapSequential(batch -> Flux.from(batch).then(Mono.just(1)))
                    .count()
                    .doOnNext(count -> flushedBatches.set(count.intValue()))
                    .then();
        });
        var properties = new BootUiProperties();
        var writer = new BootUiMcpSseHttpMessageWriter(properties, delegate);
        DataBuffer first = DefaultDataBufferFactory.sharedInstance.wrap("data:{\"frame\":1}\n\n".getBytes());
        DataBuffer second = DefaultDataBufferFactory.sharedInstance.wrap("data:{\"frame\":2}\n\n".getBytes());
        Flux<DataBuffer> input = Flux.just(first, second);
        ResolvableType type = ResolvableType.forClass(DataBuffer.class);

        writer.write(
                        input,
                        type,
                        type,
                        MediaType.TEXT_EVENT_STREAM,
                        MockServerHttpRequest.method(HttpMethod.POST, URI.create(path))
                                .build(),
                        response,
                        Map.of())
                .block();

        verify(response).writeAndFlushWith(org.mockito.ArgumentMatchers.any(Publisher.class));
        verify(delegate, never())
                .write(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(flushedBatches).hasValue(2);
    }

    @ParameterizedTest
    @CsvSource({"/other/stream,text/event-stream", "/bootui/api/mcp,application/json"})
    @SuppressWarnings("unchecked")
    void delegatesNonMcpAndNonSseBodies(String path, String mediaTypeValue) {
        HttpMessageWriter<DataBuffer> delegate = mock(HttpMessageWriter.class);
        when(delegate.write(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        ResolvableType type = ResolvableType.forClass(DataBuffer.class);
        var request =
                MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)).build();
        Flux<DataBuffer> input = Flux.empty();
        MediaType mediaType = MediaType.parseMediaType(mediaTypeValue);

        new BootUiMcpSseHttpMessageWriter(new BootUiProperties(), delegate)
                .write(input, type, type, mediaType, request, response, Map.of())
                .block();

        verify(response, never()).writeAndFlushWith(org.mockito.ArgumentMatchers.any());
        verify(delegate).write(input, type, type, mediaType, request, response, Map.of());
    }
}
