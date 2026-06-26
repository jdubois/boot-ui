package io.github.jdubois.bootui.quarkus.sample.catalog;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Type-safe config for the sample app (mirrors the Spring sample's {@code SampleSettings}). */
@ConfigMapping(prefix = "sample")
public interface SampleSettings {

    @WithDefault("Hello")
    String greeting();

    @WithDefault("3")
    int retries();
}
