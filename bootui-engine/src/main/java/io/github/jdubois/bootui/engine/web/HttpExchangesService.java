package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.HttpHeaderDto;
import io.github.jdubois.bootui.engine.support.PagedList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral assembly, masking, trace-id extraction, self-exclusion and paging for the HTTP
 * Exchanges panel. Adapters translate their native captured exchanges into {@link CapturedHttpExchange}
 * records and call {@link #report}; the wire ({@link HttpExchangesReport}) is identical across Spring
 * Boot and Quarkus because every transformation lives here. Pure functions over core DTOs and the JDK.
 */
public final class HttpExchangesService {

    private static final Set<String> SENSITIVE_HEADER_NAMES =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "x-xsrf-token", "x-csrf-token");

    private final SecretMasker masker = new SecretMasker();

    /**
     * Builds the report from already-captured exchanges. The {@code selfFilter} hides BootUI's own
     * traffic; {@code maskSecrets}/{@code exposure} drive credential masking identically to config-time
     * exposure.
     */
    public HttpExchangesReport report(
            List<CapturedHttpExchange> captured,
            BootUiSelfPath selfFilter,
            boolean maskSecrets,
            ValueExposure exposure,
            String query,
            String method,
            String statusClass,
            Integer offset,
            Integer limit) {
        List<HttpExchangeDto> visible = new ArrayList<>();
        int hiddenSelf = 0;
        for (CapturedHttpExchange exchange : captured) {
            if (isSelfExchange(exchange, selfFilter)) {
                hiddenSelf++;
                continue;
            }
            visible.add(toDto(exchange, maskSecrets, exposure));
        }

        String normalizedQuery = PagedList.normalize(query);
        String normalizedMethod = PagedList.normalize(method).toUpperCase(Locale.ROOT);
        String normalizedStatusClass = PagedList.normalize(statusClass);
        PagedList.Result<HttpExchangeDto> page = PagedList.from(
                visible,
                exchange -> matches(exchange, normalizedQuery, normalizedMethod, normalizedStatusClass),
                offset,
                limit);
        return new HttpExchangesReport(visible.size(), captured.size(), hiddenSelf, page.items(), page.page(), null);
    }

    private boolean isSelfExchange(CapturedHttpExchange exchange, BootUiSelfPath selfFilter) {
        return exchange.uri() != null
                && selfFilter != null
                && selfFilter.isBootUiPath(exchange.uri().toString());
    }

    private HttpExchangeDto toDto(CapturedHttpExchange exchange, boolean maskSecrets, ValueExposure exposure) {
        java.net.URI requestUri = exchange.uri();
        String method = exchange.method();
        String uri = requestUri == null ? null : displayUri(requestUri, maskSecrets, exposure);
        String path = requestUri == null ? null : requestUri.getPath();
        String query = requestUri == null ? null : displayQuery(requestUri.getRawQuery(), maskSecrets, exposure);
        int status = exchange.status();
        Long durationMs = exchange.durationMs();
        List<HttpHeaderDto> requestHeaders = headers(exchange.requestHeaders(), maskSecrets, exposure);
        List<HttpHeaderDto> responseHeaders = headers(exchange.responseHeaders(), maskSecrets, exposure);
        String principal = displayValue("principal", exchange.principal(), maskSecrets, exposure);
        String sessionId = displayValue("session-id", exchange.sessionId(), maskSecrets, exposure);
        return new HttpExchangeDto(
                id(exchange, method, uri, status, durationMs),
                exchange.timestamp(),
                method,
                path,
                query,
                uri,
                status,
                statusFamily(status),
                durationMs,
                responseSizeBytes(responseHeaders),
                exchange.remoteAddress(),
                principal,
                sessionId,
                resolveTraceId(exchange.traceId(), requestHeaders),
                requestHeaders,
                responseHeaders);
    }

    /**
     * Prefer the trace id the adapter captured from the active server span (so same-origin local requests
     * with no {@code traceparent} still correlate); otherwise fall back to the id parsed from inbound
     * propagation headers. The Spring adapter passes {@code null}, preserving the header-derived behavior.
     */
    private String resolveTraceId(String capturedTraceId, List<HttpHeaderDto> requestHeaders) {
        if (capturedTraceId != null && !capturedTraceId.isBlank()) {
            return capturedTraceId;
        }
        return traceId(requestHeaders);
    }

    private List<HttpHeaderDto> headers(
            Map<String, List<String>> headers, boolean maskSecrets, ValueExposure exposure) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        return headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new HttpHeaderDto(
                        entry.getKey(),
                        displayValues(entry.getKey(), entry.getValue(), maskSecrets, exposure),
                        shouldMask(entry.getKey(), maskSecrets)))
                .toList();
    }

    private List<String> displayValues(String name, List<String> values, boolean maskSecrets, ValueExposure exposure) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return List.of();
        }
        if (shouldMask(name, maskSecrets)) {
            if (exposure == ValueExposure.FULL) {
                return List.copyOf(values);
            }
            return values.stream().map(ignored -> SecretMasker.MASKED_VALUE).toList();
        }
        return List.copyOf(values);
    }

    private String displayValue(String name, String value, boolean maskSecrets, ValueExposure exposure) {
        if (value == null) {
            return null;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (shouldMask(name, maskSecrets) && exposure != ValueExposure.FULL) {
            return SecretMasker.MASKED_VALUE;
        }
        return value;
    }

    private boolean shouldMask(String name, boolean maskSecrets) {
        if (name == null) {
            return false;
        }
        return maskSecrets && (masker.isSecret(name) || SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT)));
    }

    private String displayUri(java.net.URI uri, boolean maskSecrets, ValueExposure exposure) {
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
        String displayQuery = displayQuery(rawQuery, maskSecrets, exposure);
        if (displayQuery != null) {
            builder.append('?').append(displayQuery);
        }
        if (uri.getRawFragment() != null) {
            builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
    }

    private String displayQuery(String rawQuery, boolean maskSecrets, ValueExposure exposure) {
        if (rawQuery == null) {
            return null;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        String[] parts = rawQuery.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = displayQueryPart(parts[i], maskSecrets, exposure);
        }
        return String.join("&", parts);
    }

    private String displayQueryPart(String part, boolean maskSecrets, ValueExposure exposure) {
        int equalsIndex = part.indexOf('=');
        String name = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
        if (!shouldMask(name, maskSecrets) || exposure == ValueExposure.FULL) {
            return part;
        }
        return equalsIndex >= 0 ? name + "=" + SecretMasker.MASKED_VALUE : SecretMasker.MASKED_VALUE;
    }

    private Long responseSizeBytes(List<HttpHeaderDto> responseHeaders) {
        for (HttpHeaderDto header : responseHeaders) {
            if (!"content-length".equalsIgnoreCase(header.name())
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
        String parsedTraceparent = parseTraceparent(firstHeaderValue(requestHeaders, "traceparent"));
        if (parsedTraceparent != null) {
            return parsedTraceparent;
        }
        String b3TraceId = firstHeaderValue(requestHeaders, "x-b3-traceid");
        if (b3TraceId != null && !b3TraceId.isBlank()) {
            return b3TraceId;
        }
        String parsedB3 = parseB3(firstHeaderValue(requestHeaders, "b3"));
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

    private String id(CapturedHttpExchange exchange, String method, String uri, int status, Long durationMs) {
        String input = exchange.timestamp()
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

    /** Decides whether an exchange URI is BootUI's own traffic (hidden from the panel). */
    public interface BootUiSelfPath {
        boolean isBootUiPath(String uri);
    }
}
