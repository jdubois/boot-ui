package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import io.github.jdubois.bootui.engine.support.MessageExposure;
import io.github.jdubois.bootui.engine.support.UriMasking;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern GENERATED_MAIL_SUMMARY = Pattern.compile("Email to \\d+ recipients?");
    private static final Pattern GENERATED_MAIL_DETAIL =
            Pattern.compile("(?:\\d+ attachments?(?: · dev-trap: not sent)?|dev-trap: not sent)");
    // A semicolon ends a secret-like assignment's value, so a placeholder spelled with one is never masked; the mark,
    // removed from every statement first, keeps the spelling from being forged.
    private static final char PLACEHOLDER_MARK = '\u0001';
    private static final String PLACEHOLDER = ";" + PLACEHOLDER_MARK + ";";
    private static final Pattern PLACEHOLDER_TOKEN = Pattern.compile("\\?(?=[\\s,)]|$)");
    // An identifier quoted as MySQL or SQL Server do, as the key of an assignment the masking should recognize.
    private static final Pattern QUOTED_KEY = Pattern.compile("`(\\w++)`(?=\\s*+[:=])|\\[(\\w++)](?=\\s*+[:=])");
    private static final Pattern ALTERNATIVE_QUOTE = Pattern.compile("(?i)(?<![\\w$])n?q'");
    // Literals the normalizer reads as something else: a leading-dot number (.5) and a hexadecimal or bit literal
    // (0x6869, 0b101) or one with digit separators (1_000), whose first digits it replaces and whose rest it keeps.
    private static final Pattern UNREAD_NUMBER = Pattern.compile("(?<![\\w$.?])\\.\\d\\w*+|\\?[xXbB_]\\w*+");
    private static final Set<String> CLIENT_TYPES =
            Set.of("RestClient", "RestTemplate", "WebClient", "Quarkus REST Client Reactive");
    private static final Pattern UNTERMINATED_DOLLAR_QUOTE =
            Pattern.compile("\\$[\\p{L}_][\\p{L}\\p{N}_]*+\\$.*+|\\$\\$.*+", Pattern.DOTALL);

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

    /** Whether this rule withholds free text: log messages, live or persisted. */
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
        // The sentinel that keeps a placeholder out of assignment masking must not be forgeable by the statement.
        String text = sql.replace(PLACEHOLDER_MARK, ' ');
        int singleQuote = text.indexOf('\'');
        int doubleQuote = text.indexOf('"');
        int firstQuote =
                singleQuote < 0 ? doubleQuote : doubleQuote < 0 ? singleQuote : Math.min(singleQuote, doubleQuote);
        // A # starts a comment in MySQL but is an operator in PostgreSQL, and the normalizer does not know it: a quote
        // inside one opens a literal a real quote closes, and its free text is no statement. Nothing after it is shown.
        int cut = text.indexOf('#');
        if (firstQuote >= 0 && (cut < 0 || firstQuote < cut) && isAmbiguous(text, doubleQuote)) {
            cut = firstQuote;
        }
        return cut < 0 ? literalFree(text) : literalFree(text.substring(0, cut)) + " ?";
    }

    /**
     * Whether a quoted statement reads differently by dialect, so nothing after its first quote may be shown. The
     * recorded text does not say which dialect wrote it. A backslash escapes a quote in MySQL but not in standard SQL
     * ({@code 'C:\'}), and the normalizer honors one only inside {@code '...'}, so a double-quoted run with a backslash
     * ({@code "x\"secret"}) is always ambiguous; a single-quoted one is when the two readings differ. An Oracle
     * alternative quote ({@code q'[it's]'}) closes on a delimiter the normalizer does not know.
     */
    private static boolean isAmbiguous(String sql, int doubleQuote) {
        if (ALTERNATIVE_QUOTE.matcher(sql).find() || hasNestedComment(sql)) {
            return true;
        }
        if (sql.indexOf('\\') < 0) {
            return false;
        }
        return doubleQuote >= 0
                || !SqlStatementNormalizer.normalize(sql.replace("\\", ""))
                        .sql()
                        .equals(SqlStatementNormalizer.normalize(sql).sql());
    }

    /**
     * Whether a block comment opens inside another, which PostgreSQL nests but the normalizer closes at the first
     * {@code *}{@code /}, reading the rest of the outer comment as statement text.
     */
    private static boolean hasNestedComment(String sql) {
        int open = sql.indexOf("/*");
        while (open >= 0) {
            int close = sql.indexOf("*/", open + 2);
            int next = sql.indexOf("/*", open + 2);
            if (next >= 0 && (close < 0 || next < close)) {
                return true;
            }
            open = close < 0 ? -1 : sql.indexOf("/*", close + 2);
        }
        return false;
    }

    /**
     * The shape of {@code sql} with any remaining secret-like assignment masked. Literals are replaced first: masking
     * an assignment first could consume a literal's opening delimiter ({@code = $$prefix secret$$}) and leave its
     * content outside any literal. A placeholder is no secret, so it is kept out of the masking and stays {@code ?}.
     */
    private static String literalFree(String sql) {
        String shape = shape(SqlStatementNormalizer.normalize(sql).sql());
        shape = QUOTED_KEY.matcher(shape).replaceAll(match -> match.group(1) != null ? "$1" : "$2");
        return MessageExposure.maskSecretAssignments(
                        PLACEHOLDER_TOKEN.matcher(shape).replaceAll(PLACEHOLDER))
                .replace(PLACEHOLDER, "?");
    }

    /**
     * The literal-free shape of a SQL statement whatever the live mode: what {@link #masked()} shows. Runtime Insights
     * quotes it in its sentences and evidence, where a statement's fingerprint — which keeps identifier-like
     * {@code "..."} runs and unterminated dollar quotes verbatim — only groups. Never {@code null}.
     */
    public static String displayShape(String sql) {
        return MASKED.sql(sql);
    }

    /**
     * Statement counts keyed only by their literal-free display shapes. Shapes that masking makes indistinguishable
     * share a count; no value or recoverable hash of one is kept. The bounded-map overflow sentinel stays distinct.
     */
    public static Map<String, Long> statementCounts(Map<String, Long> fingerprints) {
        Map<String, Long> shapes = new LinkedHashMap<>();
        fingerprints.forEach((fingerprint, count) -> shapes.merge(statementShape(fingerprint), count, Long::sum));
        return Collections.unmodifiableMap(shapes);
    }

    static String statementShape(String fingerprint) {
        return "Other".equals(fingerprint) ? fingerprint : displayShape(fingerprint);
    }

    /** Hardens a normalized shape: the runs the normalizer keeps but cannot tell apart from a value become {@code ?}. */
    private static String shape(String normalized) {
        // The normalizer keeps identifier-like "..." runs, which MySQL reads as string literals, and an unterminated
        // dollar quote of a truncated statement; neither can be told apart from a value, so neither is shown.
        String shape = DOUBLE_QUOTED.matcher(normalized).replaceAll("?");
        shape = UNREAD_NUMBER.matcher(shape).replaceAll("?");
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
     * {@link ValueExposure#METADATA_ONLY} omits a log row's message, as the live feed does, and keeps every other row's
     * structural summary ({@code GET /orders → 200}, a SQL shape, its path parameters masked) and its stored detail.
     */
    public ActivityEntryDto reapply(ActivityEntryDto row) {
        if (row == null) {
            return null;
        }
        JournalTextExposure rule = verbatim() ? MASKED : this;
        String maskedPath = rule.path(row.path());
        String summary = row.summary();
        String detail = row.detail();
        if (rule.omitsText()) {
            summary = metadataSummary(row.type(), summary);
            detail = metadataDetail(row.type(), detail);
        }
        if (summary != null && !summary.isEmpty()) {
            if (row.path() != null && !row.path().equals(maskedPath)) {
                summary = summary.replace(row.path(), maskedPath);
            }
            summary = JournalActivityFeed.TYPE_SQL.equals(row.type())
                    ? rule.sql(summary)
                    : MessageExposure.maskSecretAssignments(summary);
        }
        if (detail != null) {
            detail = MessageExposure.maskSecretAssignments(detail);
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

    /**
     * A stored summary reduced to the metadata the live feed shows under {@code METADATA_ONLY}. A log row's summary is
     * its message template, which the live feed omits. Rows a 1.x build stored carry free text the journal never
     * records — an exception's message, a security event's principal, an email's subject — so each of those keeps only
     * its structural prefix.
     */
    private static String metadataSummary(String type, String summary) {
        if (summary == null || type == null) {
            return summary;
        }
        return switch (type) {
            case JournalActivityFeed.TYPE_LOG -> "";
            case JournalActivityFeed.TYPE_EXCEPTION -> before(summary, ":");
            case JournalActivityFeed.TYPE_SECURITY -> before(summary, " · ");
            case JournalActivityFeed.TYPE_MAIL ->
                GENERATED_MAIL_SUMMARY.matcher(summary).matches() ? summary : "Email";
            default -> summary;
        };
    }

    /** A stored detail reduced as {@link #metadataSummary} reduces a summary; a 1.x row's free text is dropped. */
    private static String metadataDetail(String type, String detail) {
        if (detail == null || type == null) {
            return detail;
        }
        return switch (type) {
            case JournalActivityFeed.TYPE_SCHEDULED, JournalActivityFeed.TYPE_EXCEPTION, JournalActivityFeed.TYPE_LOG ->
                before(detail, ":");
            case JournalActivityFeed.TYPE_MAIL ->
                GENERATED_MAIL_DETAIL.matcher(detail).matches() ? detail : null;
            // The journal stores a datasource name or a client type where a 1.x row stored the failure's message, and
            // a datasource name cannot be told apart from one: only a known client type is kept.
            case JournalActivityFeed.TYPE_SQL -> null;
            case JournalActivityFeed.TYPE_REST_CLIENT -> CLIENT_TYPES.contains(detail) ? detail : null;
            default -> detail;
        };
    }

    private static String before(String text, String delimiter) {
        int index = text.indexOf(delimiter);
        return index < 0 ? text : text.substring(0, index).trim();
    }

    static String whitespaceNormalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
