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

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

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
