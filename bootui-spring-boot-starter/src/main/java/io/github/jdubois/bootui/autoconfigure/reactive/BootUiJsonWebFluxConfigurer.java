package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Gives BootUI's path-scoped JSON writer precedence while retaining the host writer for other paths.
 */
public final class BootUiJsonWebFluxConfigurer implements WebFluxConfigurer {

    private final BootUiProperties properties;

    public BootUiJsonWebFluxConfigurer(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        HttpMessageWriter<Object> hostWriter = configurer.getWriters().stream()
                .filter(EncoderHttpMessageWriter.class::isInstance)
                .map(EncoderHttpMessageWriter.class::cast)
                .filter(writer -> writer.getEncoder() instanceof JacksonJsonEncoder)
                .map(BootUiJsonWebFluxConfigurer::asObjectWriter)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BootUI requires a Jackson JSON encoder"));
        configurer.customCodecs().register(new BootUiJsonHttpMessageWriter(properties, hostWriter));
    }

    @SuppressWarnings("unchecked")
    private static HttpMessageWriter<Object> asObjectWriter(EncoderHttpMessageWriter<?> writer) {
        return (HttpMessageWriter<Object>) writer;
    }
}
