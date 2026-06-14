package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpHeaderDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/bootui/api/http-exchanges")
public class HttpExchangesController {

    private static final String UNAVAILABLE_REASON = "HTTP exchange repository not available";

    private static final Set<String> SENSITIVE_HEADER_NAMES =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-xsrf-token", "x-csrf-token");

    private final ObjectProvider<HttpExchangeRepository> repository;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    private final BootUiSelfDataFilter selfDataFilter;

    private final BootUiChangeStream changeStream;

    private final SecretMasker masker = new SecretMasker();

    public HttpExchangesController(ObjectProvider<HttpExchangeRepository> repository, BootUiProperties properties) {
        this(repository, properties, BootUiSelfDataFilter.defaults(), new BootUiExposure(properties), null);
    }

    @Autowired
    public HttpExchangesController(
            ObjectProvider<HttpExchangeRepository> repository,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter,
            BootUiExposure exposure,
            ObjectProvider<BootUiChangeStream> changeStreamProvider) {
        this.repository = repository;
        this.properties = properties;
        this.selfDataFilter = selfDataFilter;
        this.exposure = exposure;
        this.changeStream = changeStreamProvider == null
                ? new BootUiChangeStream("http-exchanges")
                : changeStreamProvider.getIfAvailable(() -> new BootUiChangeStream("http-exchanges"));
    }

    @GetMapping
    public HttpExchangesReport exchanges(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "statusClass", required = false) String statusClass,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) {
        HttpExchangeRepository exchangeRepository = repository.getIfAvailable();
        if (exchangeRepository == null) {
            return HttpExchangesReport.unavailable(UNAVAILABLE_REASON);
        }

        List<HttpExchange> recorded = exchangeRepository.findAll();
        List<HttpExchangeDto> visible = new ArrayList<>();
        int hiddenSelf = 0;
        for (HttpExchange exchange : recorded) {
            if (isSelfExchange(exchange)) {
                hiddenSelf++;
                continue;
            }
            visible.add(toDto(exchange));
        }

        String normalizedQuery = PagedList.normalize(query);
        String normalizedMethod = PagedList.normalize(method).toUpperCase(Locale.ROOT);
        String normalizedStatusClass = PagedList.normalize(statusClass);
        PagedList.Result<HttpExchangeDto> page = PagedList.from(
                visible,
                exchange -> matches(exchange, normalizedQuery, normalizedMethod, normalizedStatusClass),
                offset,
                limit);
        return new HttpExchangesReport(visible.size(), recorded.size(), hiddenSelf, page.items(), page.page(), null);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a new HTTP exchange is recorded, so the
     * browser can refresh live without polling. The push carries no data; the browser re-fetches the
     * GET endpoint, preserving all filtering, pagination, and masking.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }

    private boolean isSelfExchange(HttpExchange exchange) {
        HttpExchange.Request request = exchange.getRequest();
        return request != null
                && request.getUri() != null
                && !selfDataFilter.shouldInclude(
                        selfDataFilter.isBootUiPath(request.getUri().toString()));
    }

    private HttpExchangeDto toDto(HttpExchange exchange) {
        HttpExchange.Request request = exchange.getRequest();
        HttpExchange.Response response = exchange.getResponse();
        String method = request == null ? null : request.getMethod();
        String uri = request == null || request.getUri() == null ? null : displayUri(request.getUri());
        String path = request == null || request.getUri() == null
                ? null
                : request.getUri().getPath();
        String query = request == null || request.getUri() == null
                ? null
                : displayQuery(request.getUri().getRawQuery());
        int status = response == null ? 0 : response.getStatus();
        Long durationMs = durationMs(exchange.getTimeTaken());
        List<HttpHeaderDto> requestHeaders = headers(request == null ? Map.of() : request.getHeaders());
        List<HttpHeaderDto> responseHeaders = headers(response == null ? Map.of() : response.getHeaders());
        String principal = exchange.getPrincipal() == null
                ? null
                : displayValue("principal", exchange.getPrincipal().getName());
        String sessionId = exchange.getSession() == null
                ? null
                : displayValue("session-id", exchange.getSession().getId());
        return new HttpExchangeDto(
                id(exchange, method, uri, status, durationMs),
                exchange.getTimestamp(),
                method,
                path,
                query,
                uri,
                status,
                statusFamily(status),
                durationMs,
                responseSizeBytes(responseHeaders),
                request == null ? null : request.getRemoteAddress(),
                principal,
                sessionId,
                traceId(requestHeaders),
                requestHeaders,
                responseHeaders);
    }

    private Long durationMs(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private List<HttpHeaderDto> headers(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        return headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new HttpHeaderDto(
                        entry.getKey(), displayValues(entry.getKey(), entry.getValue()), shouldMask(entry.getKey())))
                .toList();
    }

    private List<String> displayValues(String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        BootUiProperties.ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == BootUiProperties.ValueExposure.METADATA_ONLY) {
            return List.of();
        }
        if (shouldMask(name)) {
            if (valueExposure == BootUiProperties.ValueExposure.FULL) {
                return List.copyOf(values);
            }
            return values.stream().map(ignored -> SecretMasker.MASKED_VALUE).toList();
        }
        return List.copyOf(values);
    }

    private String displayValue(String name, String value) {
        if (value == null) {
            return null;
        }
        BootUiProperties.ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == BootUiProperties.ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (shouldMask(name) && valueExposure != BootUiProperties.ValueExposure.FULL) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }

    private boolean shouldMask(String name) {
        if (name == null) {
            return false;
        }
        return exposure.maskSecrets()
                && (masker.isSecret(name) || SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT)));
    }

    private String displayUri(java.net.URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) {
            return uri.toString();
        }
        StringBuilder builder = new StringBuilder();
        if (uri.getScheme() != null) {
            builder.append(uri.getScheme()).append(':');
        }
        if (uri.getRawAuthority() != null) {
            builder.append("//").append(uri.getRawAuthority());
        }
        if (uri.getRawPath() != null) {
            builder.append(uri.getRawPath());
        }
        String displayQuery = displayQuery(rawQuery);
        if (displayQuery != null) {
            builder.append('?').append(displayQuery);
        }
        if (uri.getRawFragment() != null) {
            builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
    }

    private String displayQuery(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        if (exposure.valueExposure() == BootUiProperties.ValueExposure.METADATA_ONLY) {
            return null;
        }
        String[] parts = rawQuery.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = displayQueryPart(parts[i]);
        }
        return String.join("&", parts);
    }

    private String displayQueryPart(String part) {
        int equalsIndex = part.indexOf('=');
        String name = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
        if (!shouldMask(name) || exposure.valueExposure() == BootUiProperties.ValueExposure.FULL) {
            return part;
        }
        return equalsIndex >= 0 ? name + "=" + SecretMasker.MASKED_VALUE : SecretMasker.MASKED_VALUE;
    }

    private Long responseSizeBytes(List<HttpHeaderDto> responseHeaders) {
        for (HttpHeaderDto header : responseHeaders) {
            if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(header.name())
                    || header.values() == null
                    || header.values().isEmpty()) {
                continue;
            }
            try {
                return Long.parseLong(header.values().get(0));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private String traceId(List<HttpHeaderDto> requestHeaders) {
        String traceparent = firstHeaderValue(requestHeaders, "traceparent");
        String parsedTraceparent = parseTraceparent(traceparent);
        if (parsedTraceparent != null) {
            return parsedTraceparent;
        }
        String b3TraceId = firstHeaderValue(requestHeaders, "x-b3-traceid");
        if (b3TraceId != null && !b3TraceId.isBlank()) {
            return b3TraceId;
        }
        String b3 = firstHeaderValue(requestHeaders, "b3");
        String parsedB3 = parseB3(b3);
        if (parsedB3 != null) {
            return parsedB3;
        }
        return parseAmznTraceId(firstHeaderValue(requestHeaders, "x-amzn-trace-id"));
    }

    private String firstHeaderValue(List<HttpHeaderDto> headers, String name) {
        for (HttpHeaderDto header : headers) {
            if (header.name().equalsIgnoreCase(name)
                    && header.values() != null
                    && !header.values().isEmpty()) {
                return header.values().get(0);
            }
        }
        return null;
    }

    private String parseTraceparent(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("-");
        if (parts.length >= 4 && parts[1].matches("[0-9a-fA-F]{32}")) {
            return parts[1].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String parseB3(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("-");
        if (parts.length >= 2 && parts[0].matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{32}")) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String parseAmznTraceId(String value) {
        if (value == null) {
            return null;
        }
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("Root=")) {
                return trimmed.substring("Root=".length());
            }
        }
        return null;
    }

    private String statusFamily(int status) {
        if (status >= 100 && status < 200) {
            return "1xx";
        }
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 400) {
            return "3xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        if (status >= 500 && status < 600) {
            return "5xx";
        }
        return "unknown";
    }

    private boolean matches(HttpExchangeDto exchange, String query, String method, String statusClass) {
        return matchesMethod(exchange, method)
                && matchesStatusClass(exchange, statusClass)
                && matchesQuery(exchange, query);
    }

    private boolean matchesMethod(HttpExchangeDto exchange, String method) {
        return method.isEmpty() || method.equalsIgnoreCase(exchange.method());
    }

    private boolean matchesStatusClass(HttpExchangeDto exchange, String statusClass) {
        return statusClass.isEmpty() || statusClass.equals(exchange.statusFamily());
    }

    private boolean matchesQuery(HttpExchangeDto exchange, String query) {
        return query.isEmpty()
                || PagedList.contains(exchange.path(), query)
                || PagedList.contains(exchange.query(), query)
                || PagedList.contains(exchange.uri(), query)
                || PagedList.contains(exchange.traceId(), query);
    }

    private String id(HttpExchange exchange, String method, String uri, int status, Long durationMs) {
        String input = exchange.getTimestamp()
                + "|"
                + nullToEmpty(method)
                + "|"
                + nullToEmpty(uri)
                + "|"
                + status
                + "|"
                + (durationMs == null ? "" : durationMs);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is unavailable", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
