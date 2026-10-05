package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selects a clean Jackson encoder for BootUI responses while preserving the host encoder elsewhere.
 */
public final class BootUiJsonHttpMessageWriter implements HttpMessageWriter<Object> {

    private final String apiPath;
    private final HttpMessageWriter<Object> bootUiWriter;
    private final HttpMessageWriter<Object> hostWriter;

    public BootUiJsonHttpMessageWriter(BootUiProperties properties, HttpMessageWriter<Object> hostWriter) {
        this.apiPath = properties.getApiPath();
        this.bootUiWriter = new EncoderHttpMessageWriter<>(
                new JacksonJsonEncoder(JsonMapper.builder().findAndAddModules().build()));
        this.hostWriter = hostWriter;
    }

    @Override
    public List<MediaType> getWritableMediaTypes() {
        return hostWriter.getWritableMediaTypes();
    }

    @Override
    public List<MediaType> getWritableMediaTypes(ResolvableType elementType) {
        return hostWriter.getWritableMediaTypes(elementType);
    }

    @Override
    public boolean canWrite(ResolvableType elementType, MediaType mediaType) {
        Class<?> valueClass = elementType.resolve();
        return isJsonBody(valueClass) && hostWriter.canWrite(elementType, mediaType);
    }

    @Override
    public Mono<Void> write(
            Publisher<?> inputStream,
            ResolvableType elementType,
            MediaType mediaType,
            ReactiveHttpOutputMessage message,
            Map<String, Object> hints) {
        return hostWriter.write(inputStream, elementType, mediaType, message, hints);
    }

    @Override
    public Mono<Void> write(
            Publisher<?> inputStream,
            ResolvableType actualType,
            ResolvableType elementType,
            MediaType mediaType,
            ServerHttpRequest request,
            ServerHttpResponse response,
            Map<String, Object> hints) {
        HttpMessageWriter<Object> writer = isBootUiApiRequest(request) ? bootUiWriter : hostWriter;
        return writer.write(inputStream, actualType, elementType, mediaType, request, response, hints);
    }

    private boolean isBootUiApiRequest(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        return path.equals(apiPath) || path.startsWith(apiPath + "/");
    }

    private static boolean isJsonBody(Class<?> valueClass) {
        return valueClass != null
                && valueClass != String.class
                && valueClass != byte[].class
                && !Resource.class.isAssignableFrom(valueClass);
    }
}
