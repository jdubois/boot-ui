package io.github.jdubois.bootui.engine.correlation;

import io.github.jdubois.bootui.core.SecretMasker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single canonical policy for correlation-identifier capture: which inbound header names may be read,
 * how a header name and an identifier value are normalized and bounded, and how the opaque lookup identity
 * used for matching is derived.
 *
 * <p>Framework-neutral and JSON-free, so Spring servlet, Spring WebFlux and Quarkus all resolve exactly
 * the same header names, apply exactly the same bounds, and produce exactly the same lookup identities for
 * the same request. Nothing here reads a request body, registers a filter, or performs any I/O.</p>
 *
 * <p><strong>Reserved names are refused even when configured.</strong> A configured name is rejected when
 * it is not a valid HTTP field name, is too long, or names credential-bearing material — either explicitly
 * ({@code cookie} and friends) or because the shared {@link SecretMasker} recognises it as secret-like
 * ({@code authorization}, {@code x-api-key}, {@code x-auth-token}, ...). Rejection is deliberate and
 * reported by {@link CorrelationIdSettings#rejectedHeaderNames()} rather than silently dropped.</p>
 *
 * <p><strong>The lookup identity is one-way.</strong> It is the first {@value #LOOKUP_ID_LENGTH} hex
 * characters of a SHA-256 digest over a fixed domain-separation prefix plus the normalized identifier, so
 * BootUI can match a user-supplied identifier exactly, and propagate the match to correlated child
 * entries, without broadcasting the raw identifier into every activity DTO or into any BootUI-generated
 * URL. The derivation is deliberately reproducible (no per-process salt) so the browser can derive the
 * same identity locally from a value the developer typed and never has to send that raw value to the
 * server.</p>
 */
public final class CorrelationIdPolicy {

    /** Header names recognised on every adapter without any configuration. */
    public static final List<String> BUILT_IN_HEADER_NAMES = List.of("x-correlation-id", "x-request-id", "x-flow-id");

    /** Maximum number of additional header names that configuration may add. */
    public static final int MAX_ADDITIONAL_HEADER_NAMES = 5;

    /** Maximum length of a configurable header name. */
    public static final int MAX_HEADER_NAME_LENGTH = 64;

    /** Maximum number of identifiers captured for one request; later matches are dropped. */
    public static final int MAX_IDS_PER_REQUEST = 4;

    /** Maximum length of a captured identifier; longer values are truncated and flagged. */
    public static final int MAX_VALUE_LENGTH = 128;

    /**
     * Domain-separation prefix mixed into the lookup digest. Bumping the version invalidates previously
     * derived lookup identities, which is safe because they are never persisted.
     */
    public static final String LOOKUP_DOMAIN = "bootui-correlation-id:v1:";

    /** Number of hex characters kept from the digest (64 bits of collision resistance). */
    public static final int LOOKUP_ID_LENGTH = 16;

    /**
     * Credential-bearing header names that must never become a correlation identifier, beyond what
     * {@link SecretMasker} already recognises as secret-like.
     */
    private static final Set<String> RESERVED_HEADER_NAMES = Set.of("cookie", "authenticate", "authentication");

    /**
     * Distributed-trace propagation headers. BootUI already surfaces these as the exchange's trace id and
     * correlates activity on it, so accepting one as a correlation identifier would be redundant — and,
     * because a recognized correlation header is masked below {@code FULL} exposure, it would also hide the
     * evidence the trace id itself is parsed from. Refused rather than silently degraded.
     */
    private static final Set<String> TRACE_PROPAGATION_HEADER_NAMES =
            Set.of("traceparent", "tracestate", "b3", "x-b3-traceid", "x-b3-spanid", "x-amzn-trace-id");

    /** RFC 9110 field-name token characters, excluding the letters and digits handled separately. */
    private static final String TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

    private static final SecretMasker MASKER = new SecretMasker();

    private CorrelationIdPolicy() {}

    /**
     * Normalizes a configured or built-in header name to its canonical lower-case form, or returns
     * {@code null} when the name is blank, over-long, or not a valid HTTP field name. Header-name matching
     * is case-insensitive, so normalization is what makes {@code X-Correlation-Id} and
     * {@code x-correlation-id} the same name on every adapter.
     */
    public static String normalizeHeaderName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HEADER_NAME_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphanumeric && TOKEN_SPECIALS.indexOf(c) < 0) {
                return null;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether an already-{@link #normalizeHeaderName(String) normalized} header name is refused because it
     * carries credentials. Built-in names are never reserved.
     */
    public static boolean isReserved(String normalizedName) {
        if (normalizedName == null) {
            return false;
        }
        if (BUILT_IN_HEADER_NAMES.contains(normalizedName)) {
            return false;
        }
        // Substring rather than exact match, matching how SecretMasker recognizes secret-like names:
        // `x-session-cookie` carries exactly what `cookie` does.
        for (String reserved : RESERVED_HEADER_NAMES) {
            if (normalizedName.contains(reserved)) {
                return true;
            }
        }
        return TRACE_PROPAGATION_HEADER_NAMES.contains(normalizedName) || MASKER.isSecret(normalizedName);
    }

    /**
     * Normalizes a raw header value to the identifier BootUI captures, or {@code null} when the value
     * cannot be a correlation identifier. Values are trimmed; blank values and values containing control
     * characters (which cannot legitimately appear in a header value, and which must never reach a log
     * line or the UI) are refused outright rather than sanitized into something the developer never sent.
     * Case is preserved: matching is exact, never case-insensitive.
     */
    public static String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        return trimmed;
    }

    /** Whether a normalized value exceeds the per-value bound and will therefore be truncated. */
    public static boolean isOverlong(String normalizedValue) {
        return normalizedValue != null && normalizedValue.length() > MAX_VALUE_LENGTH;
    }

    /**
     * Bounds a normalized value to {@link #MAX_VALUE_LENGTH} characters.
     *
     * <p>A cut that lands inside a surrogate pair is pulled back by one character. Java would encode the
     * orphaned half as {@code ?} while the browser's {@code TextEncoder} substitutes {@code U+FFFD}, so
     * leaving it in place would make the server and the browser derive different lookup identities for
     * the same identifier — the filter would silently never match.</p>
     */
    public static String truncate(String normalizedValue) {
        if (!isOverlong(normalizedValue)) {
            return normalizedValue;
        }
        int end = MAX_VALUE_LENGTH;
        if (Character.isHighSurrogate(normalizedValue.charAt(end - 1))) {
            end -= 1;
        }
        return normalizedValue.substring(0, end);
    }

    /**
     * Derives the opaque, one-way lookup identity for an already normalized and bounded identifier.
     *
     * @param boundedValue the normalized, {@link #truncate(String) bounded} identifier
     * @return the lookup identity, or {@code null} when {@code boundedValue} is {@code null}
     */
    public static String lookupId(String boundedValue) {
        if (boundedValue == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((LOOKUP_DOMAIN + boundedValue).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, LOOKUP_ID_LENGTH / 2);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is unavailable", ex);
        }
    }
}
