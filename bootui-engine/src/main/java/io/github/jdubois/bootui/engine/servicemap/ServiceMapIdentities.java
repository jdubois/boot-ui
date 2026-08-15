package io.github.jdubois.bootui.engine.servicemap;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Safe identity normalization for the Live Flow service map.
 *
 * <p>This is the single place where a captured URI or JDBC URL is reduced to something that may be shown.
 * It is deliberately subtractive: it keeps only the coarse destination and drops everything that could
 * carry a secret or identify a specific request — user-info credentials, paths, query strings, and
 * fragments. When a value cannot be parsed into a safe identity, the identity is absent rather than
 * approximated from the raw text.</p>
 *
 * <p>It also owns the one place a captured distributed-trace id is touched at all: {@link #flowId} reduces
 * it to an opaque, one-way correlation token for {@code ServiceMapInteractionDto#flowId}, so the raw trace
 * id itself never reaches this contract.</p>
 */
public final class ServiceMapIdentities {

    /** Upper bound on any displayed identity, so a pathological URL cannot bloat the response. */
    static final int MAX_IDENTITY_LENGTH = 120;

    private ServiceMapIdentities() {}

    /**
     * Reduces a captured outbound HTTP URI to a {@code scheme://host[:port]} origin.
     *
     * <p>The port is kept only when it is explicit and not the scheme default, so {@code https://api:443}
     * and {@code https://api} do not become two nodes for one dependency. Returns {@code null} when the URI
     * is absent, relative, or has no host — an unidentifiable call is left out of the map rather than shown
     * under a guessed identity.</p>
     */
    public static String httpOrigin(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        URI parsed;
        try {
            parsed = new URI(uri.trim());
        } catch (Exception ex) {
            return null;
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
            return null;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        int port = parsed.getPort();
        String origin = normalizedScheme + "://" + host.toLowerCase(Locale.ROOT);
        if (port > 0 && port != defaultPort(normalizedScheme)) {
            origin = origin + ":" + port;
        }
        return origin;
    }

    /**
     * Reduces a JDBC URL to a displayable target.
     *
     * <p>This method independently removes authority user-info and Oracle driver-style credentials even when
     * the source panel's value-exposure mode permits raw values. It then drops any query/parameter tail
     * (both {@code ?} and {@code ;} styles) because driver-specific parameters can carry values BootUI has
     * no name-based reason to trust. Returns {@code null} when there is nothing safe left to show, in which
     * case callers fall back to the pool name.</p>
     */
    public static String jdbcTarget(String maskedJdbcUrl) {
        if (maskedJdbcUrl == null || maskedJdbcUrl.isBlank()) {
            return null;
        }
        String value = stripDb2Properties(maskedJdbcUrl.trim());
        int cut = value.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '?' || character == ';' || character == '#') {
                cut = index;
                break;
            }
        }
        String target = value.substring(0, cut).trim();
        target = stripAuthorityUserInfo(target);
        target = stripOracleCredentials(target);
        // A URL that was nothing but parameters, or that masking reduced to nothing, has no safe identity.
        return target.isEmpty() ? null : target;
    }

    /**
     * Builds the display identity of a RabbitMQ publish destination from its exchange and routing key.
     * Returns {@code null} when neither is usable, so an unidentifiable publish is omitted rather than
     * collapsed into a meaningless shared node.
     */
    public static String rabbitDestination(String exchange, String routingKey) {
        String safeExchange = emptyToNull(exchange);
        String safeRoutingKey = emptyToNull(routingKey);
        if (safeExchange == null && safeRoutingKey == null) {
            return null;
        }
        if (safeExchange == null) {
            return "(default exchange) → " + safeRoutingKey;
        }
        if (safeRoutingKey == null) {
            return safeExchange;
        }
        return safeExchange + " → " + safeRoutingKey;
    }

    /**
     * Builds an unambiguous grouping identity for a RabbitMQ exchange/routing-key tuple.
     *
     * <p>This deliberately differs from the human-readable destination: exchange and routing key values may
     * themselves contain the display separator, and a real exchange may use the default-exchange label.</p>
     */
    static String rabbitIdentity(String exchange, String routingKey) {
        String safeExchange = emptyToNull(exchange);
        String safeRoutingKey = emptyToNull(routingKey);
        if (safeExchange == null && safeRoutingKey == null) {
            return null;
        }
        return encodeTupleComponent(safeExchange) + encodeTupleComponent(safeRoutingKey);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String encodeTupleComponent(String value) {
        String component = value == null ? "" : value;
        return component.length() + ":" + component;
    }

    static String truncate(String value) {
        if (value == null || value.length() <= MAX_IDENTITY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_IDENTITY_LENGTH - 1) + "…";
    }

    /** Stable opaque id derived from the complete sanitized identity, never its truncated display label. */
    static String stableId(String prefix, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    /** Length of the opaque {@link #flowId} digest: short enough to stay cheap, long enough (64 bits) that a
     *  local dev tool's trace volume will never collide in practice. */
    private static final int FLOW_ID_HEX_LENGTH = 16;

    /**
     * Derives the Live Flow map's opaque, one-way flow correlation id from a captured distributed-trace id.
     *
     * <p>This is the only place a trace id is ever touched for the service map: the result is a stable
     * SHA-256-derived digest, never the trace id itself, so this contract cannot leak a raw identifier that
     * some other system (an APM, a log aggregator) might treat as sensitive. Two interactions hash to the
     * same flowId if and only if they shared the exact same trace id, which is all the browser needs to
     * sequence causally-related evidence — it never needs to know or reconstruct the original id.</p>
     *
     * @param traceId the distributed-trace id active when an interaction completed, or {@code null}/blank
     * @return the opaque flow id, or {@code null} when {@code traceId} is blank so an uncorrelated
     *     interaction (for example, one captured with no tracer configured) never gets a synthetic flow
     */
    static String flowId(String traceId) {
        String value = blankToNull(traceId);
        if (value == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FLOW_ID_HEX_LENGTH / 2);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    private static String stripAuthorityUserInfo(String value) {
        int authority = value.indexOf("//");
        if (authority < 0) {
            return value;
        }
        int authorityStart = authority + 2;
        int authorityEnd = value.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = value.length();
        }
        int at = value.indexOf('@', authorityStart);
        if (at >= authorityStart && at < authorityEnd) {
            return value.substring(0, authorityStart) + value.substring(at + 1);
        }
        return value;
    }

    private static String stripOracleCredentials(String value) {
        String prefix = "jdbc:oracle:";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return value;
        }
        int driverEnd = value.indexOf(':', prefix.length());
        if (driverEnd < 0) {
            return value;
        }
        int credentialStart = driverEnd + 1;
        if (credentialStart >= value.length() || value.charAt(credentialStart) == '@') {
            return value;
        }
        int at = value.indexOf('@', credentialStart);
        return at < 0 ? value : value.substring(0, credentialStart) + value.substring(at);
    }

    private static String stripDb2Properties(String value) {
        String prefix = "jdbc:db2:";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return value;
        }

        int propertyStart;
        if (value.regionMatches(prefix.length(), "//", 0, 2)) {
            int databaseStart = value.indexOf('/', prefix.length() + 2);
            propertyStart = databaseStart < 0 ? -1 : value.indexOf(':', databaseStart + 1);
        } else {
            propertyStart = value.indexOf(':', prefix.length());
        }
        if (propertyStart < 0) {
            return value;
        }

        int propertyEnd = value.length();
        for (char delimiter : new char[] {';', '?', '#'}) {
            int delimiterIndex = value.indexOf(delimiter, propertyStart + 1);
            if (delimiterIndex >= 0 && delimiterIndex < propertyEnd) {
                propertyEnd = delimiterIndex;
            }
        }
        return value.substring(propertyStart + 1, propertyEnd).contains("=")
                ? value.substring(0, propertyStart)
                : value;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            default -> -1;
        };
    }
}
