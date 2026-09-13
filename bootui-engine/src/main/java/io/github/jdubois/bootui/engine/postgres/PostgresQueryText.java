package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes the statement text {@code pg_stat_statements} reports.
 *
 * <p>PostgreSQL normalizes the text itself, so literals are already placeholders before BootUI sees them.
 * That is not treated as sufficient: utility statements and non-normalizable constructs still reach the view
 * verbatim, so the text is credential-redacted, string literals are masked under the default
 * {@link ValueExposure#MASKED} policy, whitespace is collapsed, and the result is truncated.</p>
 *
 * <p>Masking covers dollar-quoted bodies as well as ordinary quoted literals. Utility statements are the
 * ones that arrive verbatim, and they are exactly the ones that carry {@code $$ ... $$} bodies — a
 * {@code CREATE FUNCTION} or {@code DO} block would otherwise publish its whole body while the harmless
 * literal next to it was masked.</p>
 */
final class PostgresQueryText {

    /**
     * A quoted literal in either of PostgreSQL's two flavours, matched escape-string-first.
     *
     * <p>In a standard literal a backslash is an ordinary character and the only escape for a quote is
     * {@code ''}. In an {@code E'...'} escape string {@code \'} also ends nothing, so matching one with the
     * standard rule stops at the escaped quote and leaves the rest of the statement — including whatever
     * followed it — unmasked. The escape-string alternative is therefore tried first.</p>
     *
     * <p>The {@code E} prefix is only recognised when it does not continue a word, so the trailing letter of
     * an identifier such as {@code like'x'} is not mistaken for one and eaten by the replacement.</p>
     *
     * <p>Both alternatives also accept the end of input as a terminator. {@code pg_stat_activity} truncates
     * its query text at {@code track_activity_query_size}, which can cut a statement mid-literal; an
     * unterminated literal must mask to the end rather than fail to match and publish its opening.</p>
     */
    private static final Pattern STRING_LITERAL = Pattern.compile(
            "(?<![A-Za-z0-9_$])[eE]'(?:[^'\\\\]|''|\\\\.)*(?:'|\\z)|'(?:[^']|'')*(?:'|\\z)", Pattern.DOTALL);

    /**
     * {@code $$ ... $$} and {@code $tag$ ... $tag$}. The two alternatives are spelled out rather than made
     * one optional group, because a back-reference to a group that did not participate never matches, which
     * would silently turn the untagged form into a match-to-end. An unterminated body still matches to the
     * end: a truncated statement must not leak the tail it was cut off from.
     */
    private static final Pattern DOLLAR_QUOTED = Pattern.compile(
            "\\$\\$.*?(?:\\$\\$|\\z)|\\$([A-Za-z_][A-Za-z_0-9]*)\\$.*?(?:\\$\\1\\$|\\z)", Pattern.DOTALL);

    private static final String MASKED_BODY = Matcher.quoteReplacement("$$" + SecretMasker.MASKED_VALUE + "$$");

    private static final String MASKED_LITERAL = Matcher.quoteReplacement("'" + SecretMasker.MASKED_VALUE + "'");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PostgresQueryText() {}

    static String sanitize(String query, ExposurePolicy exposure, int maxLength) {
        if (query == null || query.isBlank()) {
            return null;
        }
        ValueExposure valueExposure = exposure == null ? ValueExposure.MASKED : exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY) {
            return SecretMasker.MASKED_VALUE;
        }
        String text = CredentialRedaction.redact(query);
        if (valueExposure != ValueExposure.FULL && (exposure == null || exposure.maskSecrets())) {
            text = DOLLAR_QUOTED.matcher(text).replaceAll(MASKED_BODY);
            text = STRING_LITERAL.matcher(text).replaceAll(MASKED_LITERAL);
        }
        return truncate(WHITESPACE.matcher(text).replaceAll(" ").strip(), maxLength);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)) + "…";
    }
}
