package io.github.jdubois.bootui.engine.httpclient;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import java.net.URI;
import java.util.Locale;

/**
 * The single place a declared HTTP client's URL or URL-like setting is reduced to something displayable.
 *
 * <p>It is deliberately subtractive and deliberately stricter than the panel's value-exposure mode: user
 * info is removed and secret-named query values are masked under <em>every</em> exposure mode, because a
 * base URL is configuration the console renders by default rather than a value a user explicitly asked to
 * reveal. {@link ValueExposure#METADATA_ONLY} additionally drops the whole query string, matching the REST
 * Client and HTTP Exchanges panels.</p>
 *
 * <p>Nothing here contacts a target or resolves a name; parsing is purely lexical, and an unparseable value
 * is still stripped of user info and of any fragment rather than being passed through.</p>
 */
public final class HttpClientUrls {

    /** Upper bound on any displayed URL, so a pathological value cannot bloat the response. */
    static final int MAX_URL_LENGTH = 240;

    private static final SecretMasker MASKER = new SecretMasker();

    /** A value the masker leaves untouched, used to ask it whether a name alone looks secret. */
    private static final String SECRET_PROBE = "probe";

    private HttpClientUrls() {}

    /** Whether the value still carries an unresolved {@code ${...}} property placeholder. */
    public static boolean hasUnresolvedPlaceholder(String value) {
        return value != null && value.contains("${");
    }

    /**
     * Reduces a raw URL to its displayable form: no user info, no fragment, secret-named query values
     * masked, and length-bounded. Returns {@code null} for a blank input.
     */
    public static String sanitize(String rawUrl, ValueExposure exposure) {
        String value = blankToNull(rawUrl);
        if (value == null) {
            return null;
        }
        value = stripFragment(value);
        value = stripUserInfo(value);
        value = maskQuery(value, exposure);
        return truncate(value);
    }

    /**
     * The lowercase host of a resolved base URL, used as the only key BootUI will attribute retained REST
     * Client calls by. Returns {@code null} when the value is relative, unresolved or has no authority, so an
     * unidentifiable client is never linked under a guessed identity.
     *
     * <p>The authority is read lexically rather than through {@link URI}, which is RFC 2396-strict and
     * rejects host names that are both legal and ubiquitous in containerised development — {@code my_service}
     * being the obvious one. Treating those as unparseable would report a perfectly healthy client as having
     * an unresolved base URL and raise a warning about a placeholder that does not exist.</p>
     */
    public static String host(String resolvedUrl) {
        String value = blankToNull(resolvedUrl);
        if (value == null || hasUnresolvedPlaceholder(value)) {
            return null;
        }
        int authorityStart = authorityStart(value);
        if (authorityStart < 0) {
            return null;
        }
        int authorityEnd = value.length();
        for (int index = authorityStart; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                authorityEnd = index;
                break;
            }
        }
        String authority = value.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 1 ? null : authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
        }
        int port = authority.indexOf(':');
        if (port >= 0) {
            authority = authority.substring(0, port);
        }
        if (authority.isBlank() || authority.indexOf(' ') >= 0) {
            return null;
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    /** Whether the value carries both a scheme and a host, so it identifies a target on its own. */
    public static boolean isResolvedAbsoluteUrl(String value) {
        return host(value) != null;
    }

    /**
     * The index just past {@code <scheme>://}, or {@code -1} when the value does not start with a scheme and
     * therefore names no authority of its own.
     */
    private static int authorityStart(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0) {
            return -1;
        }
        for (int index = 0; index < separator; index++) {
            char character = value.charAt(index);
            boolean legal =
                    Character.isLetterOrDigit(character) || character == '+' || character == '.' || character == '-';
            if (!legal || (index == 0 && !Character.isLetter(character))) {
                return -1;
            }
        }
        return separator + 3;
    }

    private static String stripFragment(String value) {
        int fragment = value.indexOf('#');
        return fragment < 0 ? value : value.substring(0, fragment);
    }

    /**
     * Removes {@code user:password@} from the authority. This is done lexically rather than through
     * {@link URI} so that a value which is not a valid URI — an unresolved placeholder, a template, a bare
     * {@code host:port} proxy address — is still stripped instead of being passed through untouched.
     */
    private static String stripUserInfo(String value) {
        int authority = value.indexOf("//");
        int authorityStart = authority < 0 ? 0 : authority + 2;
        int authorityEnd = value.length();
        for (int index = authorityStart; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '?') {
                authorityEnd = index;
                break;
            }
        }
        int at = value.lastIndexOf('@', authorityEnd - 1);
        if (at >= authorityStart) {
            return value.substring(0, authorityStart) + value.substring(at + 1);
        }
        return value;
    }

    private static String maskQuery(String value, ValueExposure exposure) {
        int queryIndex = value.indexOf('?');
        if (queryIndex < 0) {
            return value;
        }
        String base = value.substring(0, queryIndex);
        if (exposure == ValueExposure.METADATA_ONLY) {
            return base;
        }
        if (queryIndex == value.length() - 1) {
            return value;
        }
        // Both `&` and the legacy `;` separator, so a secret cannot hide behind the less common spelling.
        String[] pairs = value.substring(queryIndex + 1).split("[&;]");
        StringBuilder display = new StringBuilder(base).append('?');
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                display.append('&');
            }
            display.append(maskQueryPart(pairs[index]));
        }
        return display.toString();
    }

    private static String maskQueryPart(String pair) {
        int equals = pair.indexOf('=');
        if (equals < 0) {
            // A value-less parameter is itself the credential when its name looks like one.
            return isSecretName(pair) ? SecretMasker.MASKED_VALUE : pair;
        }
        if (equals == 0) {
            return pair;
        }
        String name = pair.substring(0, equals);
        String value = pair.substring(equals + 1);
        // Always name-masked: a base URL is rendered by default, so it must not reveal a secret query
        // value even when the panel's exposure mode is FULL.
        String masked = String.valueOf(MASKER.mask(name, value));
        if (masked.equals(value) && isSecretName(decode(name))) {
            masked = SecretMasker.MASKED_VALUE;
        }
        return name + "=" + masked;
    }

    /** Whether a query parameter name reads like a credential once percent-decoded. */
    private static boolean isSecretName(String name) {
        String candidate = decode(name);
        return candidate != null
                && !candidate.isBlank()
                && !String.valueOf(MASKER.mask(candidate, SECRET_PROBE)).equals(SECRET_PROBE);
    }

    /** Decodes {@code %XX} escapes for classification only; the original spelling is what gets displayed. */
    private static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' && index + 2 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(value.substring(index + 1, index + 3), 16));
                    index += 2;
                    continue;
                } catch (NumberFormatException ex) {
                    // Not an escape after all: keep the literal character.
                }
            }
            decoded.append(character);
        }
        return decoded.toString();
    }

    static String truncate(String value) {
        if (value == null || value.length() <= MAX_URL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_URL_LENGTH - 1) + "…";
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
