package io.github.jdubois.bootui.engine.grpc;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Safe normalization of gRPC channel targets for display.
 *
 * <p>A configured target is a user-supplied string that can carry credentials ({@code user:pass@host}),
 * credential-bearing query parameters, or simply be malformed. BootUI never resolves it — normalization is
 * pure string work with no DNS lookup, socket, or channel creation — but it must never echo the raw value
 * either. Query strings and fragments are dropped wholesale rather than filtered by name, because a gRPC
 * target has no display-critical query parameter and a name-based allowlist would leak the next token
 * parameter someone invents.</p>
 */
public final class GrpcTargets {

    /** Display bound so a pathological target cannot dominate the response or the UI. */
    static final int MAX_TARGET_LENGTH = 200;

    /**
     * Target schemes whose remainder is a file-system path or an in-process name rather than an authority.
     * They carry no host, no port and no user-info, so neither authority parsing nor user-info redaction may
     * touch them: {@code unix:@abstract} is a legitimate abstract socket name, not a credential.
     */
    private static final String[] AUTHORITY_LESS_SCHEMES = {"unix:", "in-process:", "inprocess:"};

    /**
     * A credential-shaped {@code user:password@} segment anywhere in the target, including after a path
     * separator where the authority rules no longer apply. A gRPC target has no legitimate reason to carry
     * one there, so it is masked unconditionally rather than echoed.
     */
    private static final Pattern EMBEDDED_CREDENTIALS = Pattern.compile("([^:/@\\s]+):([^:/@\\s]+)@");

    private GrpcTargets() {}

    /**
     * Returns {@code raw} trimmed, stripped of any query string and fragment, with embedded user-info and
     * credential parameters replaced by {@link SecretMasker#MASKED_VALUE}, and bounded in length. Returns
     * {@code null} for a blank input.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        value = stripAfter(value, '#');
        value = stripAfter(value, '?');
        value = redactUserInfo(value);
        value = CredentialRedaction.redact(value);
        value = EMBEDDED_CREDENTIALS.matcher(value).replaceAll(SecretMasker.MASKED_VALUE + "@");
        if (value.isEmpty()) {
            return null;
        }
        return value.length() <= MAX_TARGET_LENGTH ? value : value.substring(0, MAX_TARGET_LENGTH) + "…";
    }

    /**
     * Returns the authority (host and optional port) of an already-{@link #normalize(String) normalized}
     * target, or {@code null} when the target carries no authority (a Unix socket or in-process name) or
     * cannot be parsed. A malformed target yields {@code null} rather than an exception: the panel reports
     * what it can and stays honest about the rest.
     *
     * <p>A bracketed IPv6 literal keeps its authority. A name-resolver target such as
     * {@code stork:///inventory} deliberately reports {@code inventory}: it is the authority the channel would
     * be built with, and BootUI resolves nothing, so no host is claimed for it.</p>
     */
    public static String authority(String normalizedTarget) {
        if (normalizedTarget == null || isAuthorityLess(normalizedTarget)) {
            return null;
        }
        String rest = normalizedTarget;
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd >= 0) {
            rest = rest.substring(schemeEnd + 3);
        } else if (!rest.startsWith("[")) {
            int colon = rest.indexOf(':');
            // Without "://" the only authority-shaped form is "host:port". Anything else after a single colon
            // is a scheme-specific name that carries no authority at all (unix:/path, in-process:name), and a
            // malformed target falls into the same bucket rather than being echoed as if it were a host. An
            // IPv6 literal is always bracketed, so it is exempt from the rule.
            if (colon >= 0 && !isPort(rest.substring(colon + 1))) {
                return null;
            }
        }
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        rest = rest.trim();
        return rest.isEmpty() ? null : rest;
    }

    private static boolean isAuthorityLess(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String scheme : AUTHORITY_LESS_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPort(String value) {
        if (value.isEmpty() || value.length() > 5) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return Integer.parseInt(value) <= 65535;
    }

    private static String stripAfter(String value, char separator) {
        int index = value.indexOf(separator);
        return index < 0 ? value : value.substring(0, index).trim();
    }

    private static String redactUserInfo(String value) {
        if (isAuthorityLess(value)) {
            return value;
        }
        int authorityStart = 0;
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) {
            authorityStart = schemeEnd + 3;
        }
        int at = value.indexOf('@', authorityStart);
        if (at < 0) {
            return value;
        }
        int slash = value.indexOf('/', authorityStart);
        if (slash >= 0 && slash < at) {
            return value;
        }
        return value.substring(0, authorityStart) + SecretMasker.MASKED_VALUE + value.substring(at);
    }
}
