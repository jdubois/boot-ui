package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps BootUI's raw MCP event-stream bytes while flushing every frame on every WebFlux server adapter.
 */
final class BootUiMcpSseHttpMessageWriter implements HttpMessageWriter<DataBuffer> {

    private final String mcpPath;
    private final HttpMessageWriter<DataBuffer> delegate;

    BootUiMcpSseHttpMessageWriter(BootUiProperties properties, HttpMessageWriter<DataBuffer> delegate) {
        this.mcpPath = properties.getApiPath() + "/mcp";
        this.delegate = delegate;
    }

    @Override
    public List<MediaType> getWritableMediaTypes() {
        return delegate.getWritableMediaTypes();
    }

    @Override
    public List<MediaType> getWritableMediaTypes(ResolvableType elementType) {
        return delegate.getWritableMediaTypes(elementType);
    }

    @Override
    public boolean canWrite(ResolvableType elementType, MediaType mediaType) {
        return delegate.canWrite(elementType, mediaType);
    }

    @Override
    public Mono<Void> write(
            Publisher<? extends DataBuffer> inputStream,
            ResolvableType elementType,
            MediaType mediaType,
            ReactiveHttpOutputMessage message,
            Map<String, Object> hints) {
        return delegate.write(inputStream, elementType, mediaType, message, hints);
    }

    @Override
    public Mono<Void> write(
            Publisher<? extends DataBuffer> inputStream,
            ResolvableType actualType,
            ResolvableType elementType,
            MediaType mediaType,
            ServerHttpRequest request,
            ServerHttpResponse response,
            Map<String, Object> hints) {
        if (BootUiReactivePaths.pathWithinApplication(request).equals(mcpPath)
                && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(mediaType)) {
            return response.writeAndFlushWith(Flux.from(inputStream).map(Mono::just));
        }
        return delegate.write(inputStream, actualType, elementType, mediaType, request, response, hints);
    }
}
