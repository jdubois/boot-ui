package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.SecurityLogDataDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SecurityLogTypeSummaryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnClass(AuditEventRepository.class)
@RequestMapping("/bootui/api/security-logs")
public class SecurityLogsController {

    private static final int DEFAULT_MAX_LOGS = 500;

    private static final int MAX_MAX_LOGS = 10_000;

    private static final int MAX_DATA_ENTRIES = 20;

    private static final int MAX_DATA_VALUE_LENGTH = 256;

    private final ObjectProvider<AuditEventRepository> auditEventRepositoryProvider;

    private final BootUiProperties properties;

    private final SecretMasker masker = new SecretMasker();

    public SecurityLogsController(
            ObjectProvider<AuditEventRepository> auditEventRepositoryProvider, BootUiProperties properties) {
        this.auditEventRepositoryProvider = auditEventRepositoryProvider;
        this.properties = properties;
    }

    @GetMapping
    public SecurityLogsReport logs(
            @RequestParam(name = "principal", required = false) String principal,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int maxLogs = maxLogs();
        AuditEventRepository repository = auditEventRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return SecurityLogsReport.unavailable("No AuditEventRepository bean is available", maxLogs);
        }

        List<AuditEvent> events =
                new ArrayList<>(repository.find(blankToNull(principal), parseAfter(after), blankToNull(type)));
        events.sort(Comparator.comparing(AuditEvent::getTimestamp).reversed());
        if (events.size() > maxLogs) {
            events = new ArrayList<>(events.subList(0, maxLogs));
        }

        List<SecurityLogTypeSummaryDto> typeSummaries = summarizeTypes(events);
        List<SecurityLogEventDto> eventDtos = events.stream().map(this::toDto).toList();
        PagedList.Result<SecurityLogEventDto> page = PagedList.from(eventDtos, offset, limit);
        return new SecurityLogsReport(true, null, maxLogs, typeSummaries, page.items(), page.page());
    }

    @ExceptionHandler({DateTimeParseException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    private int maxLogs() {
        return Math.max(1, Math.min(MAX_MAX_LOGS, properties.getSecurityLogs().getMaxLogs()));
    }

    private Instant parseAfter(String after) {
        String value = blankToNull(after);
        return value == null ? null : Instant.parse(value);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private List<SecurityLogTypeSummaryDto> summarizeTypes(List<AuditEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            counts.merge(event.getType(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new SecurityLogTypeSummaryDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private SecurityLogEventDto toDto(AuditEvent event) {
        return new SecurityLogEventDto(
                event.getTimestamp().toString(),
                displayText("principal", event.getPrincipal()).value(),
                event.getType(),
                dataEntries(event.getData()));
    }

    private List<SecurityLogDataDto> dataEntries(Map<String, Object> data) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(data.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        List<SecurityLogDataDto> result = new ArrayList<>();
        int count = Math.min(entries.size(), MAX_DATA_ENTRIES);
        for (int i = 0; i < count; i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            DisplayValue displayValue = displayText(entry.getKey(), entry.getValue());
            result.add(new SecurityLogDataDto(
                    entry.getKey(), displayValue.value(), displayValue.masked(), displayValue.truncated()));
        }
        if (entries.size() > MAX_DATA_ENTRIES) {
            result.add(new SecurityLogDataDto(
                    "...", (entries.size() - MAX_DATA_ENTRIES) + " more entries omitted", false, false));
        }
        return result;
    }

    private DisplayValue displayText(String key, Object value) {
        if (properties.getExposeValues() == ValueExposure.METADATA_ONLY) {
            return new DisplayValue(null, false, false);
        }
        if (value == null) {
            return new DisplayValue(null, false, false);
        }
        boolean masked = properties.getExposeValues() == ValueExposure.MASKED
                && properties.isMaskSecrets()
                && (masker.isSecret(key) || isSensitiveAuditKey(key));
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
