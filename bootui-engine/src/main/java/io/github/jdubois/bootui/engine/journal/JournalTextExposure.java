package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import io.github.jdubois.bootui.engine.support.UriMasking;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.regex.Pattern;

/**
 * The live exposure rule for text the runtime journal renders: SQL statements, log templates, and request paths
 * ({@code PLAN-v2} §8). The journal stores bounded raw evidence in memory; this rule is resolved once per read, so a
 * change of {@code bootui.expose-values} or {@code bootui.mask-secrets} applies to the next read, and its value
 * equality makes it a cache key for a projection built under it.
 *
 * <ul>
 *   <li>{@link ValueExposure#FULL}, or {@link ValueExposure#MASKED} with {@code bootui.mask-secrets=false}, shows the
 *       text as recorded.</li>
 *   <li>{@link ValueExposure#MASKED} replaces SQL literals — and double-quoted runs, which may be MySQL string
 *       literals — with {@code ?}, masks secret-like assignments in SQL and
 *       log text as {@link MessageExposure} does, and masks {@code ;name=value} path parameters.</li>
 *   <li>{@link ValueExposure#METADATA_ONLY} omits log text, keeps only a SQL statement's literal-free shape (the
 *       metadata the on-disk baseline file already holds as fingerprints), and masks every path parameter.</li>
 * </ul>
 *
 * <p>A missing policy or mode resolves to {@link #masked()}, so an unresolved policy never reveals text.</p>
 *
 * @param exposure the value exposure mode
 * @param maskSecrets whether {@link ValueExposure#MASKED} masks secret-like values
 */
public record JournalTextExposure(ValueExposure exposure, boolean maskSecrets) {

    private static final JournalTextExposure MASKED = new JournalTextExposure(ValueExposure.MASKED, true);
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"(?:[^\"]|\"\")*+\"?");
    private static final Pattern UNTERMINATED_DOLLAR_QUOTE =
            Pattern.compile("\\$[A-Za-z_]\\w*+\\$.*+|\\$\\$.*+", Pattern.DOTALL);

    public JournalTextExposure {
        if (exposure == null) {
            exposure = ValueExposure.MASKED;
            maskSecrets = true;
        }
    }

    /** The rule {@code policy} prescribes right now; {@link #masked()} when there is no policy. */
    public static JournalTextExposure of(ExposurePolicy policy) {
        if (policy == null) {
            return MASKED;
        }
        ValueExposure exposure = policy.valueExposure();
        return exposure == null ? MASKED : new JournalTextExposure(exposure, policy.maskSecrets());
    }

    /**
     * {@link ValueExposure#MASKED} with secrets masked: the default, and the rule persisted rows are always rendered
     * under, so nothing reaches disk less masked than {@code MASKED} whatever the live policy (D12).
     */
    public static JournalTextExposure masked() {
        return MASKED;
    }

    /** Whether this rule shows text as recorded. */
    private boolean verbatim() {
        return exposure == ValueExposure.FULL || (exposure == ValueExposure.MASKED && !maskSecrets);
    }

    /** Whether this rule withholds free text: log messages and the summaries and details of persisted rows. */
    public boolean omitsText() {
        return exposure == ValueExposure.METADATA_ONLY;
    }

    /** {@code path} with its {@code ;name=value} parameters masked as this rule requires; {@code null} stays null. */
    public String path(String path) {
        if (omitsText() && path != null && path.indexOf(';') >= 0) {
            return UriMasking.maskPathMatrixParameters(path, JournalTextExposure::maskParameterValue);
        }
        return UriMasking.maskPath(path, maskSecrets, exposure);
    }

    /** Every matrix parameter's value masked, whatever its name: {@code METADATA_ONLY} shows no value. */
    private static String maskParameterValue(String parameter) {
        if (parameter.isEmpty()) {
            return parameter;
        }
        int equalsIndex = parameter.indexOf('=');
        return equalsIndex < 0
                ? SecretMasker.MASKED_VALUE
                : parameter.substring(0, equalsIndex + 1) + SecretMasker.MASKED_VALUE;
    }

    /**
     * A SQL statement, whitespace collapsed: as recorded when this rule is verbatim, otherwise its literal-free shape
     * with any remaining secret-like assignment masked. Never {@code null}.
     */
    public String sql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        if (verbatim()) {
            return whitespaceNormalized(sql);
        }
        int firstQuote = sql.indexOf('\'');
        if (firstQuote >= 0 && sql.indexOf('\\') >= 0) {
            // A backslash escapes a quote in MySQL but not in standard SQL ('C:\'), and the recorded text does not
            // say which dialect wrote it. The two readings are compared on the recorded text, before any masking can
            // consume a backslash; when they differ, nothing after the first quote is shown, so neither reading can
            // turn a literal into visible text.
            String mysql = SqlStatementNormalizer.normalize(sql).sql();
            String standard =
                    SqlStatementNormalizer.normalize(sql.replace("\\", "")).sql();
            if (!standard.equals(mysql)) {
                return SqlStatementNormalizer.normalize(
                                        MessageExposure.maskSecretAssignments(sql.substring(0, firstQuote)))
                                .sql()
                        + " ?";
            }
        }
        // Secret-like assignments are masked before the shape is taken, so a quoted secret becomes a plain placeholder.
        String shape = SqlStatementNormalizer.normalize(MessageExposure.maskSecretAssignments(sql))
                .sql();
        // The normalizer keeps identifier-like "..." runs, which MySQL reads as string literals, and an unterminated
        // dollar quote of a truncated statement; neither can be told apart from a value, so neither is shown.
        shape = DOUBLE_QUOTED.matcher(shape).replaceAll("?");
        shape = UNTERMINATED_DOLLAR_QUOTE.matcher(shape).replaceAll("?");
        // A quote the normalizer left open is a literal it could not close: nothing after it is shown.
        int strayQuote = shape.indexOf('\'');
        return strayQuote < 0 ? shape : shape.substring(0, strayQuote) + "?";
    }

    /** Log text as {@link MessageExposure} shows it: {@code null} when this rule omits text or {@code text} is null. */
    public String message(String text) {
        if (text == null || omitsText()) {
            return null;
        }
        return verbatim() ? text : MessageExposure.maskSecretAssignments(text);
    }

    /**
     * Re-applies this rule to a row read back from the durable Live Activity store, which was written under
     * {@link #masked()} — or raw, by a build before this rule existed — so the live policy holds for it too
     * ({@code PLAN-v2} §8). A stored row is never shown less masked than {@code MASKED}, even under {@code FULL}.
     * {@link ValueExposure#METADATA_ONLY} omits its summary and detail, keeping the structured columns.
     */
    public ActivityEntryDto reapply(ActivityEntryDto row) {
        if (row == null) {
            return null;
        }
        JournalTextExposure rule = verbatim() ? MASKED : this;
        String maskedPath = rule.path(row.path());
        String summary;
        String detail = row.detail();
        if (rule.omitsText()) {
            summary = "";
            detail = null;
        } else {
            summary = row.summary();
            if (summary != null && row.path() != null && !row.path().equals(maskedPath)) {
                summary = summary.replace(row.path(), maskedPath);
            }
            if (JournalActivityFeed.TYPE_SQL.equals(row.type())) {
                summary = rule.sql(summary);
            } else if (summary != null) {
                summary = MessageExposure.maskSecretAssignments(summary);
            }
        }
        return new ActivityEntryDto(
                row.id(),
                row.type(),
                row.timestamp(),
                row.severity(),
                summary,
                detail,
                row.durationMs(),
                row.correlationId(),
                row.method(),
                maskedPath,
                row.status(),
                row.thread(),
                row.profileable(),
                row.parentId(),
                row.securedPrincipal(),
                row.sqlNPlusOneSuspected(),
                row.badges());
    }

    static String whitespaceNormalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
