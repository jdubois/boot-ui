/**
 * Framework-neutral telemetry engine: the bounded in-memory {@link io.github.jdubois.bootui.engine.telemetry.TelemetryStore}
 * span buffer, the in-process OpenTelemetry {@link io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter},
 * the self-traffic {@link io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier}, and the
 * {@link io.github.jdubois.bootui.engine.telemetry.TracesService} / {@link io.github.jdubois.bootui.engine.telemetry.AiUsageService}
 * read models that transform normalized spans into BootUI core DTOs for the Traces and AI Usage panels.
 *
 * <p>Plain Java plus the OpenTelemetry SDK (an optional dependency, concentrated in
 * {@link io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter}); no framework or transport types.
 * Adapters supply live configuration through the {@link io.github.jdubois.bootui.engine.telemetry.TelemetrySettings}
 * seam, build the two {@link io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier} instances
 * (capture vs transform) themselves, and feed OTLP/HTTP-decoded spans straight into the store, so the
 * receiver transport and JSON decoding stay framework-specific.
 */
package io.github.jdubois.bootui.engine.telemetry;
