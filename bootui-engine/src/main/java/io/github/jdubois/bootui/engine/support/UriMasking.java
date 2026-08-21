package io.github.jdubois.bootui.engine.support;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;

/**
 * Shared display-time masking for a captured request URI, used identically by the HTTP Exchanges panel
 * (inbound) and the REST Client panel (outbound) so the two never drift.
 *
 * <p>Two different rules apply, deliberately:</p>
 *
 * <ul>
 *   <li><strong>Authority user-info is removed unconditionally.</strong> A {@code user:secret@host} credential is
 *       never a value the developer asked BootUI to display, so — like {@link CredentialRedaction} — it is
 *       stripped regardless of {@code bootui.mask-secrets} and even under {@link ValueExposure#FULL}. Host and
 *       port survive, because they are the useful part. A parameter value that itself carries a nested URL with
 *       credentials (commonly a percent-encoded {@code redirect=} target) is masked on the same unconditional
 *       footing.</li>
 *   <li><strong>Query, matrix, and fragment parameters follow the live exposure policy.</strong> Values whose name
 *       looks sensitive ({@link SensitiveNames}) are masked under {@link ValueExposure#MASKED}, every query and
 *       fragment value is dropped under {@link ValueExposure#METADATA_ONLY}, and nothing is masked under
 *       {@link ValueExposure#FULL}. Parameter names are always preserved, so the shape of the request stays
 *       diagnosable. Both {@code &} and the legacy {@code ;} separate parameters, and {@code ;name=value} matrix
 *       parameters inside path segments are masked the same way.</li>
 * </ul>
 *
 * <p>Everything works on the raw (still percent-encoded) URI text: captured URIs may be truncated, relative, or
 * malformed, and re-parsing them is not a precondition for masking them safely. Percent-decoding is used only to
 * decide whether something is sensitive, never to produce the displayed value.</p>
 */
public final class UriMasking {

    private UriMasking() {}

    /**
     * Removes the authority user-info from a raw URI or URI prefix, replacing {@code user:secret@} with
     * {@link SecretMasker#MASKED_VALUE}{@code @} and leaving host, port, path, query, and fragment untouched.
     * Returns the input unchanged when it has no authority component or no user-info.
     */
    public static String maskUserInfo(String uri) {
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        int separator = uri.indexOf("//");
        if (separator < 0 || (separator > 0 && uri.charAt(separator - 1) != ':')) {
            return uri;
        }
        int authorityStart = separator + 2;
        int authorityEnd = uri.length();
        for (int i = authorityStart; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = uri.substring(authorityStart, authorityEnd);
        int userInfoEnd = authority.lastIndexOf('@');
        if (userInfoEnd < 0) {
            return uri;
        }
        return uri.substring(0, authorityStart)
                + SecretMasker.MASKED_VALUE
                + '@'
                + authority.substring(userInfoEnd + 1)
                + uri.substring(authorityEnd);
    }

    /**
     * Masks a raw query string (or a query-shaped fragment) per the live exposure policy. Returns {@code null}
     * under {@link ValueExposure#METADATA_ONLY} so callers can drop the component entirely, and {@code null} for
     * a {@code null} input. Parameters are separated by {@code &} or the legacy {@code ;}; the original separators
     * are preserved.
     */
    public static String maskQueryString(String rawQuery, boolean maskSecrets, ValueExposure exposure) {
        if (rawQuery == null) {
            return null;
        }
        if (exposure == ValueExposure.METADATA_ONLY) {
            return null;
        }
        return maskParameterList(rawQuery, maskSecrets && exposure != ValueExposure.FULL);
    }

    /**
     * Masks {@code ;name=value} matrix parameters carried by a URI path per the live exposure policy. Path
     * segments themselves are preserved, since a path is the request's identity rather than one of its values —
     * including under {@link ValueExposure#METADATA_ONLY}, where the sensitive matrix values are still masked.
     */
    public static String maskPath(String path, boolean maskSecrets, ValueExposure exposure) {
        if (path == null || path.indexOf(';') < 0) {
            return path;
        }
        return maskMatrixParameters(path, maskSecrets && exposure != ValueExposure.FULL);
    }

    /**
     * Masks a whole raw URI: user-info unconditionally, query, matrix, and fragment parameters per the live
     * exposure policy. A value-less parameter whose name itself looks sensitive (for example a bare
     * {@code ?api_key}) is replaced wholesale, since there is no name/value split to preserve.
     */
    public static String maskUri(String uri, boolean maskSecrets, ValueExposure exposure) {
        if (uri == null) {
            return null;
        }
        boolean policyMasking = maskSecrets && exposure != ValueExposure.FULL;
        int fragmentIndex = uri.indexOf('#');
        String beforeFragment = fragmentIndex < 0 ? uri : uri.substring(0, fragmentIndex);
        String fragment = fragmentIndex < 0 ? null : uri.substring(fragmentIndex + 1);
        int queryIndex = beforeFragment.indexOf('?');
        String base = queryIndex < 0 ? beforeFragment : beforeFragment.substring(0, queryIndex);
        String rawQuery = queryIndex < 0 ? null : beforeFragment.substring(queryIndex + 1);

        String maskedBase = maskUserInfo(base);
        int pathStart = pathStart(maskedBase);
        StringBuilder display = new StringBuilder(maskedBase.substring(0, pathStart))
                .append(maskMatrixParameters(maskedBase.substring(pathStart), policyMasking));
        String maskedQuery = maskQueryString(rawQuery, maskSecrets, exposure);
        if (maskedQuery != null) {
            display.append('?').append(maskedQuery);
        }
        String maskedFragment = maskQueryString(fragment, maskSecrets, exposure);
        if (maskedFragment != null) {
            display.append('#').append(maskedFragment);
        }
        return display.toString();
    }

    /** Index at which the path begins, i.e. just past the {@code //authority} component when there is one. */
    private static int pathStart(String base) {
        int separator = base.indexOf("//");
        if (separator < 0 || (separator > 0 && base.charAt(separator - 1) != ':')) {
            return 0;
        }
        int index = separator + 2;
        while (index < base.length() && base.charAt(index) != '/') {
            index++;
        }
        return index;
    }

    private static String maskMatrixParameters(String path, boolean policyMasking) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = maskParameterList(segments[i], policyMasking);
        }
        return String.join("/", segments);
    }

    /** Masks each {@code &}/{@code ;}-separated {@code name=value} parameter, preserving the separators used. */
    private static String maskParameterList(String parameters, boolean policyMasking) {
        StringBuilder masked = new StringBuilder(parameters.length());
        int start = 0;
        for (int i = 0; i <= parameters.length(); i++) {
            if (i < parameters.length() && parameters.charAt(i) != '&' && parameters.charAt(i) != ';') {
                continue;
            }
            masked.append(maskParameter(parameters.substring(start, i), policyMasking));
            if (i < parameters.length()) {
                masked.append(parameters.charAt(i));
            }
            start = i + 1;
        }
        return masked.toString();
    }

    private static String maskParameter(String parameter, boolean policyMasking) {
        int equalsIndex = parameter.indexOf('=');
        String name = equalsIndex >= 0 ? parameter.substring(0, equalsIndex) : parameter;
        if (equalsIndex < 0) {
            return policyMasking && SensitiveNames.isSensitive(name) ? SecretMasker.MASKED_VALUE : parameter;
        }
        String value = parameter.substring(equalsIndex + 1);
        boolean sensitiveByName = policyMasking && SensitiveNames.isSensitive(name);
        if (sensitiveByName || carriesCredentials(value)) {
            return name + '=' + SecretMasker.MASKED_VALUE;
        }
        return parameter;
    }

    /**
     * True when a parameter value embeds a URL that itself carries credentials — a nested {@code user:secret@host}
     * or a sensitive nested parameter — as it commonly does in a percent-encoded {@code redirect=} target. Checked
     * both as captured and percent-decoded, and masked on the same unconditional footing as authority user-info,
     * because a nested credential is never the value the developer meant to inspect.
     */
    private static boolean carriesCredentials(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if (!CredentialRedaction.redactMessage(value).equals(value)) {
            return true;
        }
        String decoded = SensitiveNames.decodeQueryComponent(value);
        return !decoded.equals(value)
                && !CredentialRedaction.redactMessage(decoded).equals(decoded);
    }
}
