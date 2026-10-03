package io.github.jdubois.bootui.engine.sqltrace;

import java.util.Locale;

/**
 * Normalizes captured SQL so equivalent executions aggregate without exposing the values they carried.
 *
 * <p>SQL Trace retains statement text as executed. Two executions of the same prepared statement already
 * share that text, but two executions of a statement whose values were concatenated into the SQL do not —
 * {@code where id = 41} and {@code where id = 42} would rank as two unrelated statements and would put a
 * literal value on screen. Normalization solves both at once: every literal is replaced by {@code ?},
 * whitespace and comments collapse, and {@code IN} lists fold to a single placeholder, so the resulting
 * text is stable, groupable and literal-free.</p>
 *
 * <p>This is a deliberately small, dependency-free scanner rather than a SQL parser: BootUI must not add a
 * SQL grammar dependency, must never fail on a dialect it does not know, and must stay cheap enough to run
 * over the whole retained buffer on demand. It therefore recognizes only the lexical shapes every SQL
 * dialect shares — quoted strings (including PostgreSQL dollar quoting), quoted identifiers, numbers, and
 * comments — and leaves everything else untouched.</p>
 *
 * <p>Normalization never <em>adds</em> exposure: it only replaces literals with placeholders. The literal
 * evidence it reports back ({@link Result#literalCount()} and {@link Result#predicateLiteralCount()}) is
 * counts only, never the values themselves.</p>
 */
public final class SqlStatementNormalizer {

    /** Upper bound on the SQL length scanned, so a pathological statement cannot dominate a report. */
    private static final int MAX_SCAN_LENGTH = 20_000;

    private SqlStatementNormalizer() {}

    /**
     * The normalized form of one statement plus the literal evidence gathered while normalizing.
     *
     * @param sql the normalized, literal-free statement text
     * @param fingerprint a stable, case-insensitive key for {@code sql}, used to group and to deep-link
     * @param literalCount literals that were replaced anywhere in the statement
     * @param predicateLiteralCount literals that were replaced in a value position of a comparison,
     *     {@code IN} list or {@code LIKE} — the only ones that suggest a concatenated value rather than a
     *     projected constant
     */
    public record Result(String sql, String fingerprint, int literalCount, int predicateLiteralCount) {}

    /** Normalizes {@code sql}, returning an empty result for {@code null} or blank input. */
    public static Result normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result("", "", 0, 0);
        }
        String source = sql.length() > MAX_SCAN_LENGTH ? sql.substring(0, MAX_SCAN_LENGTH) : sql;
        StringBuilder out = new StringBuilder(source.length());
        int literals = 0;
        int predicateLiterals = 0;
        int index = 0;
        int length = source.length();
        while (index < length) {
            char c = source.charAt(index);
            int dollarEnd = c == '$' ? dollarQuoteEnd(source, index) : -1;
            if (isLineCommentStart(source, index)) {
                index = skipLineComment(source, index);
                appendSpace(out);
            } else if (isBlockCommentStart(source, index)) {
                index = skipBlockComment(source, index);
                appendSpace(out);
            } else if (c == '\'') {
                index = skipQuoted(source, index, '\'');
                literals++;
                if (isPredicatePosition(out)) {
                    predicateLiterals++;
                }
                out.append('?');
            } else if (dollarEnd > 0) {
                // PostgreSQL dollar quoting ($$value$$, $tag$value$tag$) is a string literal that ignores
                // the usual escaping rules, so it must be replaced rather than emitted verbatim.
                index = dollarEnd;
                literals++;
                if (isPredicatePosition(out)) {
                    predicateLiterals++;
                }
                out.append('?');
            } else if (c == '"' || c == '`' || c == '[') {
                int end = skipQuoted(source, index, c == '[' ? ']' : c);
                if (c == '"' && !isIdentifierLike(source, index, end)) {
                    // MySQL and MariaDB treat "..." as a string literal unless ANSI_QUOTES is set, so a
                    // run that does not read like a column name is masked rather than shown.
                    literals++;
                    if (isPredicatePosition(out)) {
                        predicateLiterals++;
                    }
                    out.append('?');
                } else {
                    // A quoted identifier is a name, not a value: keep it verbatim so two different columns
                    // never collapse into the same normalized statement.
                    out.append(source, index, end);
                }
                index = end;
            } else if (isKeywordLiteralStart(source, index, out)) {
                int end = skipKeywordLiteral(source, index);
                literals++;
                if (isPredicatePosition(out)) {
                    predicateLiterals++;
                }
                out.append('?');
                index = end;
            } else if (Character.isWhitespace(c)) {
                appendSpace(out);
                index++;
            } else if (isNumberStart(source, index, out)) {
                index = skipNumber(source, index);
                literals++;
                if (isPredicatePosition(out)) {
                    predicateLiterals++;
                }
                out.append('?');
            } else {
                out.append(c);
                index++;
            }
        }
        String collapsed = collapsePlaceholderLists(out.toString().trim());
        return new Result(collapsed, fingerprint(collapsed), literals, predicateLiterals);
    }

    /** Convenience for callers that only need the grouping key. */
    public static String fingerprintOf(String sql) {
        return normalize(sql).fingerprint();
    }

    /**
     * A case-insensitive key for the normalized text. Keeping the key separate from the displayed text
     * means {@code SELECT * FROM users} and {@code select * from users} rank as one statement while the
     * panel still shows the SQL the way the application wrote it.
     */
    private static String fingerprint(String normalized) {
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * Folds an {@code IN (?, ?, ?)} list down to {@code IN (?)}, so the same query executed with three
     * values and with thirty is one statement rather than two unrelated rankings.
     *
     * <p>Only {@code IN} lists fold. A run of placeholders anywhere else — a projection of two constants,
     * a function call, a multi-row {@code VALUES} tuple — carries the statement's arity, and collapsing it
     * would merge materially different statements into one ranked group.</p>
     */
    private static String collapsePlaceholderLists(String normalized) {
        if (normalized.indexOf('?') < 0) {
            return normalized;
        }
        StringBuilder out = new StringBuilder(normalized.length());
        int index = 0;
        int length = normalized.length();
        while (index < length) {
            char c = normalized.charAt(index);
            if (c != '?' || !insideInList(out)) {
                out.append(c);
                index++;
                continue;
            }
            int cursor = index;
            int lastPlaceholder = index;
            while (true) {
                int next = cursor + 1;
                while (next < length && normalized.charAt(next) == ' ') {
                    next++;
                }
                if (next < length && normalized.charAt(next) == ',') {
                    next++;
                    while (next < length && normalized.charAt(next) == ' ') {
                        next++;
                    }
                    if (next < length && normalized.charAt(next) == '?') {
                        cursor = next;
                        lastPlaceholder = next;
                        continue;
                    }
                }
                break;
            }
            out.append('?');
            index = lastPlaceholder + 1;
        }
        return out.toString();
    }

    /** Whether the text emitted so far ends inside an {@code IN (...)} list. */
    private static boolean insideInList(StringBuilder out) {
        int cursor = out.length() - 1;
        while (cursor >= 0 && out.charAt(cursor) == ' ') {
            cursor--;
        }
        if (cursor < 0) {
            return false;
        }
        char last = out.charAt(cursor);
        return (last == '(' || last == ',') && endsWithInList(out, cursor);
    }

    private static void appendSpace(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }

    private static boolean isLineCommentStart(String sql, int index) {
        return sql.charAt(index) == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-';
    }

    private static int skipLineComment(String sql, int index) {
        int cursor = index + 2;
        while (cursor < sql.length() && sql.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor;
    }

    private static boolean isBlockCommentStart(String sql, int index) {
        return sql.charAt(index) == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*';
    }

    private static int skipBlockComment(String sql, int index) {
        int cursor = index + 2;
        while (cursor + 1 < sql.length() && !(sql.charAt(cursor) == '*' && sql.charAt(cursor + 1) == '/')) {
            cursor++;
        }
        return Math.min(sql.length(), cursor + 2);
    }

    /**
     * Returns the index just past a quoted run starting at {@code index}, honoring the doubled-quote
     * escape every SQL dialect uses ({@code 'it''s'}). An unterminated quote — which a truncated captured
     * statement can easily produce — consumes the remainder rather than throwing.
     */
    private static int skipQuoted(String sql, int index, char closing) {
        int cursor = index + 1;
        while (cursor < sql.length()) {
            char c = sql.charAt(cursor);
            if (c == '\\' && closing == '\'' && cursor + 1 < sql.length()) {
                cursor += 2;
                continue;
            }
            if (c == closing) {
                if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == closing) {
                    cursor += 2;
                    continue;
                }
                return cursor + 1;
            }
            cursor++;
        }
        return sql.length();
    }

    /**
     * The index just past a complete PostgreSQL dollar-quoted string starting at {@code index}, or
     * {@code -1} when this {@code $} does not open one. The tag is empty ({@code $$}) or a SQL identifier
     * ({@code $body$}), which keeps positional bind parameters such as {@code $1} out of this branch. A
     * recognized opener without its closing delimiter consumes the rest of the captured text, because SQL
     * capture can truncate a valid literal. A dollar sign within an identifier is never an opener.
     */
    private static int dollarQuoteEnd(String sql, int index) {
        if (index > 0 && isIdentifierCharacter(sql.charAt(index - 1))) {
            return -1;
        }
        int cursor = index + 1;
        int length = sql.length();
        int bodyStart = -1;
        while (cursor < length) {
            char c = sql.charAt(cursor);
            if (c == '$') {
                bodyStart = cursor + 1;
                break;
            }
            boolean tagChar = Character.isLetter(c) || c == '_' || (cursor > index + 1 && Character.isDigit(c));
            if (!tagChar) {
                return -1;
            }
            cursor++;
        }
        if (bodyStart < 0) {
            return -1;
        }
        String tag = sql.substring(index, bodyStart);
        int end = sql.indexOf(tag, bodyStart);
        return end < 0 ? length : end + tag.length();
    }

    private static boolean isIdentifierCharacter(char c) {
        return c >= 128 || Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Whether a double-quoted run reads like a quoted identifier rather than a string literal. MySQL and
     * MariaDB accept {@code "..."} as a string literal under their default {@code sql_mode}, so a run
     * containing anything a column name would not — whitespace, punctuation, a wildcard — is treated as a
     * value. Under-masking here would put a literal on screen, so the doubt resolves towards masking.
     */
    private static boolean isIdentifierLike(String sql, int index, int end) {
        int contentStart = index + 1;
        int contentEnd = end > contentStart && sql.charAt(end - 1) == '"' ? end - 1 : end;
        if (contentEnd <= contentStart) {
            return false;
        }
        for (int i = contentStart; i < contentEnd; i++) {
            char c = sql.charAt(i);
            boolean identifierChar = (c < 128 && (Character.isLetterOrDigit(c))) || c == '_' || c == '$' || c == '.';
            if (!identifierChar) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a boolean keyword literal starts at {@code index}. {@code true} and {@code false} are values
     * that a concatenating caller can vary exactly like a number, so they normalize like one. {@code null}
     * is deliberately left alone: it carries no data, and folding it would make {@code is null} and
     * {@code is ?} indistinguishable in the displayed statement.
     */
    private static boolean isKeywordLiteralStart(String sql, int index, StringBuilder out) {
        char c = sql.charAt(index);
        if (c != 't' && c != 'T' && c != 'f' && c != 'F') {
            return false;
        }
        if (out.length() > 0) {
            char previous = out.charAt(out.length() - 1);
            if (Character.isLetterOrDigit(previous) || previous == '_' || previous == '$' || previous == '.') {
                return false;
            }
        }
        return matchesKeyword(sql, index, "true") || matchesKeyword(sql, index, "false");
    }

    private static int skipKeywordLiteral(String sql, int index) {
        return index + (matchesKeyword(sql, index, "true") ? 4 : 5);
    }

    private static boolean matchesKeyword(String sql, int index, String keyword) {
        if (index + keyword.length() > sql.length()) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.toLowerCase(sql.charAt(index + i)) != keyword.charAt(i)) {
                return false;
            }
        }
        int after = index + keyword.length();
        if (after >= sql.length()) {
            return true;
        }
        char next = sql.charAt(after);
        return !(Character.isLetterOrDigit(next) || next == '_' || next == '$');
    }

    /**
     * Whether a digit at {@code index} starts a numeric literal rather than being part of an identifier.
     * {@code column1} and {@code t2.id} must survive normalization untouched, so a digit only starts a
     * literal when the preceding emitted character is not an identifier character.
     */
    private static boolean isNumberStart(String sql, int index, StringBuilder out) {
        char c = sql.charAt(index);
        if (!Character.isDigit(c)) {
            return false;
        }
        if (out.length() == 0) {
            return true;
        }
        char previous = out.charAt(out.length() - 1);
        return !(Character.isLetterOrDigit(previous) || previous == '_' || previous == '$' || previous == '.');
    }

    private static int skipNumber(String sql, int index) {
        int cursor = index;
        int length = sql.length();
        while (cursor < length && (Character.isDigit(sql.charAt(cursor)) || sql.charAt(cursor) == '.')) {
            cursor++;
        }
        if (cursor < length && (sql.charAt(cursor) == 'e' || sql.charAt(cursor) == 'E')) {
            int exponent = cursor + 1;
            if (exponent < length && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < length && Character.isDigit(sql.charAt(exponent))) {
                cursor = exponent;
                while (cursor < length && Character.isDigit(sql.charAt(cursor))) {
                    cursor++;
                }
            }
        }
        return cursor;
    }

    /**
     * Whether the literal about to be replaced sits in a value position — directly after a comparison
     * operator, a {@code LIKE}, or inside an {@code IN} list. A literal anywhere else (a projected
     * constant, a {@code LIMIT}, a {@code CAST} precision) is normal in generated SQL and must not become
     * evidence of a concatenated value.
     */
    private static boolean isPredicatePosition(StringBuilder out) {
        int cursor = out.length() - 1;
        while (cursor >= 0 && out.charAt(cursor) == ' ') {
            cursor--;
        }
        if (cursor < 0) {
            return false;
        }
        char last = out.charAt(cursor);
        if (last == '=' || last == '<' || last == '>' || last == '!') {
            return true;
        }
        if (last == ',' || last == '(') {
            return endsWithInList(out, cursor);
        }
        return endsWithKeyword(out, cursor, "like") || endsWithKeyword(out, cursor, "ilike");
    }

    /** Whether the {@code (} or {@code ,} at {@code cursor} belongs to an {@code IN (...)} list. */
    private static boolean endsWithInList(StringBuilder out, int cursor) {
        int depth = 0;
        for (int i = cursor; i >= 0; i--) {
            char c = out.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    int before = i - 1;
                    while (before >= 0 && out.charAt(before) == ' ') {
                        before--;
                    }
                    return endsWithKeyword(out, before, "in");
                }
                depth--;
            }
        }
        return false;
    }

    private static boolean endsWithKeyword(StringBuilder out, int endInclusive, String keyword) {
        int start = endInclusive - keyword.length() + 1;
        if (start < 0) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.toLowerCase(out.charAt(start + i)) != keyword.charAt(i)) {
                return false;
            }
        }
        return start == 0 || !Character.isLetterOrDigit(out.charAt(start - 1));
    }
}
