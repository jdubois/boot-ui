package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives BootUI's path-scoped JSON converter precedence over the host application's converter.
 */
public final class BootUiJsonWebMvcConfigurer implements WebMvcConfigurer {

    private final BootUiProperties properties;

    public BootUiJsonWebMvcConfigurer(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(
                converters -> converters.add(0, new BootUiJsonHttpMessageConverter(properties)));
    }
}
