package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.Pattern;

/**
 * Sanitizes the statement text {@code pg_stat_statements} reports.
 *
 * <p>PostgreSQL normalizes the text itself, so literals are already placeholders before BootUI sees them.
 * That is not treated as sufficient: utility statements and non-normalizable constructs still reach the view
 * verbatim, so the text is credential-redacted, string literals are masked under the default
 * {@link ValueExposure#MASKED} policy, whitespace is collapsed, and the result is truncated.</p>
 */
final class PostgresQueryText {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
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
        String text = WHITESPACE.matcher(CredentialRedaction.redact(query)).replaceAll(" ").strip();
        if (valueExposure != ValueExposure.FULL && (exposure == null || exposure.maskSecrets())) {
            text = STRING_LITERAL.matcher(text).replaceAll("'" + SecretMasker.MASKED_VALUE + "'");
        }
        return truncate(text, maxLength);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)) + "…";
    }
}
