package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.BootUiIdentitySpanProcessor;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.OtelSpanEnricher;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

    /**
     * Stamps BootUI identity attributes ({@code bootui.enriched}/service/instance) on every span at start.
     * Spring Boot's OpenTelemetry autoconfiguration collects {@link SpanProcessor} beans into its tracer
     * provider, so declaring this bean is enough to wire it. Gated identically to the exporter, and the
     * processor itself re-reads the live enrichment toggle so it stays inert when enrichment is off.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bootui.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "bootui.panels.traces",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SpanProcessor bootUiIdentitySpanProcessor(BootUiProperties properties, Environment environment) {
        String serviceName = environment.getProperty("spring.application.name");
        String instanceId = environment.getProperty("HOSTNAME", System.getenv("HOSTNAME"));
        return new BootUiIdentitySpanProcessor(new SpringTelemetrySettings(properties), serviceName, instanceId);
    }

    /**
     * The capture-time enricher that stamps {@code bootui.sql.*}/{@code bootui.exception.*} depth on the
     * active span. It is a plain bean (the installer below wires it onto the SQL/exception capture points);
     * the enricher re-reads the live enrichment toggle so it no-ops when enrichment is off.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bootui.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "bootui.panels.traces",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    OtelSpanEnricher bootUiSpanEnricher(BootUiProperties properties) {
        return new OtelSpanEnricher(new SpringTelemetrySettings(properties));
    }

    /**
     * Installs the OpenTelemetry-backed enricher onto the SQL Trace recorder and exception store once all
     * singletons exist, so the existing capture points enrich the active span. When either backing panel is
     * disabled its bean is absent and installation is simply skipped.
     */
    @Bean
    @ConditionalOnProperty(prefix = "bootui.telemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "bootui.panels.traces",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    SmartInitializingSingleton bootUiSpanEnricherInstaller(
            OtelSpanEnricher enricher,
            ObjectProvider<SqlTraceRecorder> recorders,
            ObjectProvider<ExceptionStore> stores) {
        return () -> {
            recorders.ifAvailable(recorder -> recorder.setSpanEnricher(enricher));
            stores.ifAvailable(store -> store.setSpanEnricher(enricher));
        };
    }
}
