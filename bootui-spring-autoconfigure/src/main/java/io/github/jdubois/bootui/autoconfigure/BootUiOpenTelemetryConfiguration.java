package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
class BootUiOpenTelemetryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "bootui.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "bootui.panels.traces",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SpanExporter bootUiSpanExporter(
            TelemetryStore store, BootUiProperties properties, BootUiSelfDataFilter selfDataFilter) {
        return new BootUiSpanExporter(
                store, selfDataFilter.telemetryClassifier(), new SpringTelemetrySettings(properties));
    }
}
