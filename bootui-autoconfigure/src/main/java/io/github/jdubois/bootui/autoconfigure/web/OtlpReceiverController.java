package io.github.jdubois.bootui.autoconfigure.web;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;
import io.github.jdubois.bootui.autoconfigure.otlp.OtlpSpanDecoder;
import io.github.jdubois.bootui.autoconfigure.otlp.TelemetryStore;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OTLP/HTTP receiver mounted under {@code /bootui/api/otlp}.
 *
 * <p>Accepts protobuf-encoded {@code ExportTraceServiceRequest} payloads from
 * the host JVM (or any cooperating local process) and stores normalized spans
 * in the {@link TelemetryStore}. Returns an empty {@code ExportTraceServiceResponse}
 * with HTTP 200 on success per the OTLP spec.</p>
 *
 * <p>This receiver is reached through {@code LocalhostOnlyFilter}, so non-loopback
 * callers are rejected unless {@code bootui.allow-non-localhost=true}.</p>
 */
@RestController
@RequestMapping("/bootui/api/otlp")
public class OtlpReceiverController {

    private static final Logger log = LoggerFactory.getLogger(OtlpReceiverController.class);

    private static final byte[] EMPTY_RESPONSE = ExportTraceServiceResponse.getDefaultInstance().toByteArray();

    private final TelemetryStore store;

    private final OtlpSpanDecoder decoder;

    private final BootUiProperties properties;

    public OtlpReceiverController(TelemetryStore store,
                                  OtlpSpanDecoder decoder,
                                  BootUiProperties properties) {
        this.store = store;
        this.decoder = decoder;
        this.properties = properties;
    }

    private static boolean isSelfSpan(NormalizedSpan span, String apiPath) {
        if (apiPath == null || apiPath.isEmpty()) {
            return false;
        }
        if (span.attributes() == null) {
            return false;
        }
        String route = stringAttribute(span, "http.route");
        if (route != null && (route.startsWith(apiPath) || route.startsWith("/bootui"))) {
            return true;
        }
        String urlPath = stringAttribute(span, "url.path");
        if (urlPath != null && (urlPath.startsWith(apiPath) || urlPath.startsWith("/bootui"))) {
            return true;
        }
        String target = stringAttribute(span, "http.target");
        return target != null && (target.startsWith(apiPath) || target.startsWith("/bootui"));
    }

    private static String stringAttribute(NormalizedSpan span, String key) {
        if (span.attributes() == null) {
            return null;
        }
        var av = span.attributes().get(key);
        return av != null ? av.asString() : null;
    }

    private static ResponseEntity<byte[]> okResponse() {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-protobuf"))
            .body(EMPTY_RESPONSE);
    }

    @PostMapping(path = "/v1/traces",
        consumes = {"application/x-protobuf", "application/octet-stream"})
    public ResponseEntity<byte[]> receiveTraces(@RequestBody byte[] body,
                                                HttpServletRequest request) {
        BootUiProperties.Telemetry telemetry = properties.getTelemetry();
        if (!telemetry.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (body == null || body.length == 0) {
            return okResponse();
        }
        if (body.length > telemetry.getMaxRequestBytes()) {
            log.warn("Rejecting OTLP payload exceeding bootui.telemetry.max-request-bytes ({} > {})",
                body.length, telemetry.getMaxRequestBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        try {
            List<NormalizedSpan> spans = decoder.decode(body);
            String apiPath = properties.getApiPath();
            int kept = 0;
            for (NormalizedSpan span : spans) {
                if (telemetry.isExcludeSelfSpans() && isSelfSpan(span, apiPath)) {
                    continue;
                }
                store.add(span);
                kept++;
            }
            if (log.isDebugEnabled()) {
                log.debug("OTLP receiver stored {} spans (of {} received)", kept, spans.size());
            }
            return okResponse();
        } catch (InvalidProtocolBufferException ex) {
            log.warn("Rejecting invalid OTLP protobuf payload from {}: {}",
                request.getRemoteAddr(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (RuntimeException ex) {
            log.warn("OTLP receiver failed to handle payload", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
