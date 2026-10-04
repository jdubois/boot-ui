package io.github.jdubois.bootui.autoconfigure.web;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTLP/HTTP receiver mounted under {@code /bootui/api/otlp}.
 *
 * <p>Accepts protobuf-encoded {@code ExportTraceServiceRequest} payloads from
 * the host JVM (or any cooperating local process) and stores normalized spans
 * in the {@link TelemetryStore}. Returns an empty {@code ExportTraceServiceResponse}
 * with HTTP 200 on success per the OTLP spec.</p>
 *
 * <p>In the aggregator topology other services export here too, so an AI span reaches this application's runtime
 * journal only when its resource {@code service.name} is this application's, as Spring Boot names its OpenTelemetry
 * resource, or when this application started it. Every span is still stored for the Traces panel.</p>
 *
 * <p>This receiver is reached through {@code LocalhostOnlyFilter}, so non-loopback
 * callers are rejected unless {@code bootui.allow-non-localhost=true}.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/otlp")
public class OtlpReceiverController {

    private static final Logger log = LoggerFactory.getLogger(OtlpReceiverController.class);

    private static final byte[] EMPTY_RESPONSE =
            ExportTraceServiceResponse.getDefaultInstance().toByteArray();

    private final TelemetryStore store;

    private final OtlpSpanDecoder decoder;

    private final BootUiProperties properties;

    private final SelfTelemetryClassifier selfClassifier;

    private static final String RESOURCE_ATTRIBUTES = "management.opentelemetry.resource-attributes";

    private static final String SERVICE_NAME = "service.name";

    private static final String UNKNOWN_SERVICE = "unknown_service";

    private final Environment environment;

    /**
     * Convenience constructor for manual/test wiring outside a Spring context; shares no state with
     * any other component, so it builds its own default {@link BootUiSelfDataFilter}.
     */
    public OtlpReceiverController(TelemetryStore store, OtlpSpanDecoder decoder, BootUiProperties properties) {
        this(store, decoder, properties, BootUiSelfDataFilter.defaults(), null);
    }

    @Autowired
    public OtlpReceiverController(
            TelemetryStore store,
            OtlpSpanDecoder decoder,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter,
            Environment environment) {
        this.store = store;
        this.decoder = decoder;
        this.properties = properties;
        this.selfClassifier = selfDataFilter.telemetryClassifier();
        this.environment = environment;
    }

    /**
     * The {@code service.name} this application's OpenTelemetry resource carries, resolved exactly as Spring Boot's
     * {@code OpenTelemetryResourceAttributes} resolves it: the bound {@code management.opentelemetry.resource-attributes}
     * map, then the {@code OTEL_SERVICE_NAME} environment variable, then a URI-decoded {@code service.name} in the
     * {@code OTEL_RESOURCE_ATTRIBUTES} environment variable, then {@code spring.application.name}. An explicitly empty
     * value still wins, as it does in Boot. {@code null} when the resolved name is blank or Boot's
     * {@code unknown_service} default, since such a service cannot be told apart from another unnamed one.
     */
    static String applicationServiceName(Environment environment) {
        return applicationServiceName(environment, System::getenv);
    }

    static String applicationServiceName(Environment environment, Function<String, String> systemEnvironment) {
        if (environment == null) {
            return null;
        }
        String name = Binder.get(environment)
                .bind(RESOURCE_ATTRIBUTES, Bindable.mapOf(String.class, String.class))
                .map(attributes -> attributes.get(SERVICE_NAME))
                .orElse(null);
        if (name == null) {
            name = systemEnvironment.apply("OTEL_SERVICE_NAME");
        }
        if (name == null) {
            name = resourceAttribute(systemEnvironment.apply("OTEL_RESOURCE_ATTRIBUTES"), SERVICE_NAME);
        }
        if (name == null) {
            name = environment.getProperty("spring.application.name", UNKNOWN_SERVICE);
        }
        return name.isBlank() || name.equals(UNKNOWN_SERVICE) ? null : name;
    }

    private static String resourceAttribute(String attributes, String key) {
        String value = null;
        for (String attribute : StringUtils.tokenizeToStringArray(attributes, ",")) {
            int equals = attribute.indexOf('=');
            if (equals > 0 && attribute.substring(0, equals).trim().equals(key)) {
                value = StringUtils.uriDecode(attribute.substring(equals + 1).trim(), StandardCharsets.UTF_8);
            }
        }
        return value;
    }

    private static ResponseEntity<byte[]> okResponse() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-protobuf"))
                .body(EMPTY_RESPONSE);
    }

    @PostMapping(
            path = "/v1/traces",
            consumes = {"application/x-protobuf", "application/octet-stream"})
    public ResponseEntity<byte[]> receiveTraces(@RequestBody byte[] body) {
        BootUiProperties.Telemetry telemetry = properties.getTelemetry();
        if (!telemetry.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (body == null || body.length == 0) {
            return okResponse();
        }
        if (body.length > telemetry.getMaxRequestBytes()) {
            log.warn(
                    "Rejecting OTLP payload exceeding bootui.telemetry.max-request-bytes ({} > {})",
                    body.length,
                    telemetry.getMaxRequestBytes());
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        try {
            List<NormalizedSpan> spans = decoder.decode(body);
            boolean excludeSelf = telemetry.isExcludeSelfSpans();
            // Self spans first, collecting their traces, so that no span of a BootUI trace in the batch, such as an AI
            // child exported ahead of its BootUI request span, reaches the journal as application work.
            Set<String> selfTraces = new HashSet<>();
            List<NormalizedSpan> others = new ArrayList<>(spans.size());
            int kept = 0;
            for (NormalizedSpan span : spans) {
                if (excludeSelf && selfClassifier.isBootUiSpan(span)) {
                    selfTraces.add(span.traceId());
                    store.addImported(span, true, false);
                } else {
                    others.add(span);
                }
            }
            String applicationService = others.isEmpty() ? null : applicationServiceName(environment);
            for (NormalizedSpan span : others) {
                boolean applicationSpan = applicationService != null && applicationService.equals(span.serviceName());
                if (store.addImported(span, selfTraces.contains(span.traceId()), applicationSpan)) {
                    kept++;
                }
            }
            if (log.isTraceEnabled()) {
                log.trace("OTLP receiver stored {} spans (of {} received)", kept, spans.size());
            }
            return okResponse();
        } catch (InvalidProtocolBufferException ex) {
            log.warn("Rejecting invalid OTLP protobuf payload: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (RuntimeException ex) {
            log.warn("OTLP receiver failed to handle payload", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
