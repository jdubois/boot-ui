package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SecurityLogDataDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogTypeSummaryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Framework-neutral assembly, type summary, masking, bounding and paging for the Security Logs panel.
 * Adapters translate native events (Spring's Actuator {@code AuditEvent}, Quarkus CDI security events)
 * into {@link CapturedSecurityEvent} records and call {@link #report}; the wire ({@link SecurityLogsReport})
 * is identical across Spring Boot and Quarkus because every transformation lives here. Pure functions over
 * core DTOs and the JDK only.
 *
 * <p>Filtering (principal/type/after) is applied here too. Spring pre-filters via the audit repository's
 * {@code find}, so re-applying is a no-op; Quarkus's capped buffer has no native filter, so the engine
 * provides it. Limits mirror the original Spring controller so byte-for-byte output is preserved.
 */
public final class SecurityLogsService {

    private static final int MAX_MAX_LOGS = 10_000;

    private static final int MAX_DATA_ENTRIES = 20;

    private static final int MAX_DATA_VALUE_LENGTH = 256;

    private final SecretMasker masker = new SecretMasker();

    /** Clamps a requested retention maximum into the bounded, safe range. */
    public int maxLogs(int requested) {
        return Math.max(1, Math.min(MAX_MAX_LOGS, requested));
    }

    /**
     * Builds the report from already-captured events. {@code maskSecrets}/{@code exposure} drive masking
     * identically to config-time exposure; {@code principal}/{@code type}/{@code after} filter, and the
     * retained set is bounded to {@code maxLogs} newest-first before summary and paging.
     */
    public SecurityLogsReport report(
            List<CapturedSecurityEvent> captured,
            int maxLogs,
            boolean maskSecrets,
            ValueExposure exposure,
            String principal,
            String type,
            Instant after,
            Integer offset,
            Integer limit) {
        List<CapturedSecurityEvent> events = new ArrayList<>();
        for (CapturedSecurityEvent event : captured) {
            if (matches(event, principal, type, after)) {
                events.add(event);
            }
        }
        events.sort(Comparator.comparing(CapturedSecurityEvent::timestamp).reversed());
        if (events.size() > maxLogs) {
            events = new ArrayList<>(events.subList(0, maxLogs));
        }

        List<SecurityLogTypeSummaryDto> typeSummaries = summarizeTypes(events);
        List<SecurityLogEventDto> eventDtos = events.stream()
                .map(event -> toDto(event, maskSecrets, exposure))
                .toList();
        PagedList.Result<SecurityLogEventDto> page = PagedList.from(eventDtos, offset, limit);
        return new SecurityLogsReport(true, null, maxLogs, typeSummaries, page.items(), page.page());
    }

    private boolean matches(CapturedSecurityEvent event, String principal, String type, Instant after) {
        if (principal != null && !principal.equals(event.principal())) {
            return false;
        }
        if (type != null && !type.equals(event.type())) {
            return false;
        }
        return after == null || (event.timestamp() != null && event.timestamp().isAfter(after));
    }

    private List<SecurityLogTypeSummaryDto> summarizeTypes(List<CapturedSecurityEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CapturedSecurityEvent event : events) {
            counts.merge(event.type(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new SecurityLogTypeSummaryDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private SecurityLogEventDto toDto(CapturedSecurityEvent event, boolean maskSecrets, ValueExposure exposure) {
        return new SecurityLogEventDto(
                event.timestamp().toString(),
                displayText("principal", event.principal(), maskSecrets, exposure)
                        .value(),
                event.type(),
                dataEntries(event.data(), maskSecrets, exposure),
                event.traceId());
    }

    private List<SecurityLogDataDto> dataEntries(
            Map<String, Object> data, boolean maskSecrets, ValueExposure exposure) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(data.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        List<SecurityLogDataDto> result = new ArrayList<>();
        int count = Math.min(entries.size(), MAX_DATA_ENTRIES);
        for (int i = 0; i < count; i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            DisplayValue displayValue = displayText(entry.getKey(), entry.getValue(), maskSecrets, exposure);
            result.add(new SecurityLogDataDto(
                    entry.getKey(), displayValue.value(), displayValue.masked(), displayValue.truncated()));
        }
        if (entries.size() > MAX_DATA_ENTRIES) {
            result.add(new SecurityLogDataDto(
                    "...", (entries.size() - MAX_DATA_ENTRIES) + " more entries omitted", false, false));
        }
        return result;
    }

    private DisplayValue displayText(String key, Object value, boolean maskSecrets, ValueExposure exposure) {
        if (exposure == ValueExposure.METADATA_ONLY) {
            return new DisplayValue(null, false, false);
        }
        if (value == null) {
            return new DisplayValue(null, false, false);
        }
        boolean masked =
                exposure == ValueExposure.MASKED && maskSecrets && (masker.isSecret(key) || isSensitiveAuditKey(key));
        if (masked) {
            return new DisplayValue(SecretMasker.MASKED_VALUE, true, false);
        }
        String text = String.valueOf(value);
        if (text.length() > MAX_DATA_VALUE_LENGTH) {
            return new DisplayValue(text.substring(0, MAX_DATA_VALUE_LENGTH) + "...", false, true);
        }
        return new DisplayValue(text, false, false);
    }

    private boolean isSensitiveAuditKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("sessionid")
                || normalized.contains("remoteaddress")
                || normalized.contains("remoteaddr")
                || normalized.contains("ipaddress");
    }

    private record DisplayValue(String value, boolean masked, boolean truncated) {}
}
