package io.github.jdubois.bootui.engine.sqltrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads whether one captured statement limits its rows and whether its outermost query block orders them: the lexical
 * facts the {@code paged-without-order} observation needs, without a SQL parser.
 *
 * <p>It does not lex SQL itself. {@link SqlStatementNormalizer#tokens} classifies the text exactly as the
 * normalizer does, so strings, dollar quoting, quoted identifiers, and comments (including MySQL and MariaDB {@code #}
 * comments) are never mistaken for keywords. What this class adds is only the keyword reading of the outermost query
 * block, at parenthesis depth zero: a {@code WITH ... SELECT}'s final {@code SELECT}, never a subquery or a window.</p>
 *
 * <p>Precision comes before recall. A statement whose text cannot be trusted, because SQL Trace shortened it, it is
 * longer than the scan limit, it holds a quoted run or comment that never closes, a string with a backslash whose escape
 * rules depend on the dialect, a {@code #} that is not a comment, several statements, or a parenthesis that does not
 * balance, is {@linkplain Analysis#complete() incomplete}, with a reason, and never read as lacking an {@code ORDER BY}.
 * Dialects read: PostgreSQL, MySQL and MariaDB, H2, Oracle 12c and later ({@code FETCH FIRST}), and SQL Server ({@code
 * OFFSET ... FETCH}, {@code TOP}). Oracle {@code ROWNUM} wrappers are not read.</p>
 */
public final class SqlRowLimits {

    private static final Set<String> MAIN_KEYWORDS = Set.of("select", "insert", "update", "delete", "merge", "values");

    private static final Set<String> SET_OPERATORS = Set.of("union", "intersect", "except");

    /** The depth-zero words that end a {@code WHERE} clause. */
    private static final Set<String> AFTER_WHERE = Set.of(
            "group",
            "having",
            "order",
            "limit",
            "offset",
            "fetch",
            "window",
            "for",
            "qualify",
            "returning",
            "option",
            "union",
            "intersect",
            "except");

    private SqlRowLimits() {}

    /**
     * What one statement's outermost query block says about its rows.
     *
     * @param complete whether the statement could be read; {@code false} for truncated or lexically unsupported SQL,
     *     whose other flags are then all {@code false} and which is never counted as lacking an {@code ORDER BY}
     * @param incompleteReason why it could not be read, or {@code null} when it could
     * @param select whether the outermost statement is a {@code SELECT}; for an incomplete statement, whether it
     *     looks like a query
     * @param rowLimit whether it limits its rows to more than a single row: {@code LIMIT}, {@code FETCH FIRST|NEXT ...
     *     ROWS ONLY|WITH TIES}, or {@code TOP}, but not a literal limit of one, which is a single-row lookup
     * @param offset whether it skips rows: {@code OFFSET}, or the MySQL and MariaDB {@code LIMIT offset, count}, with
     *     an offset that is not the literal zero
     * @param orderBy whether the outermost query block has an {@code ORDER BY}; one inside a subquery or a window does
     *     not count
     * @param equalityOnlyWhere whether its {@code WHERE} consists only of {@code column = ?|literal} terms joined by
     *     {@code AND}, the shape of a key lookup
     * @param constantOrAggregateProjection whether its first projected item is a literal, {@code count(...)}, or
     *     {@code exists(...)}
     * @param setOperation whether the outermost block is a {@code UNION}, {@code INTERSECT}, or {@code EXCEPT}, which
     *     is not read
     */
    public record Analysis(
            boolean complete,
            String incompleteReason,
            boolean select,
            boolean rowLimit,
            boolean offset,
            boolean orderBy,
            boolean equalityOnlyWhere,
            boolean constantOrAggregateProjection,
            boolean setOperation) {

        static Analysis incomplete(String reason, boolean looksLikeQuery) {
            return new Analysis(false, reason, looksLikeQuery, false, false, false, false, false, false);
        }

        static Analysis notSelect() {
            return new Analysis(true, null, false, false, false, false, false, false, false);
        }
    }

    /** Analyses {@code sql}; a {@code null} or blank statement is incomplete. */
    public static Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return Analysis.incomplete("There is no statement text.", false);
        }
        if (sql.indexOf('…') >= 0) {
            return Analysis.incomplete("SQL Trace shortened the statement text.", looksLikeQuery(sql));
        }
        if (sql.length() > SqlStatementNormalizer.MAX_SCAN_LENGTH) {
            return Analysis.incomplete(
                    "The statement is longer than the " + SqlStatementNormalizer.MAX_SCAN_LENGTH
                            + " characters BootUI reads.",
                    looksLikeQuery(sql));
        }
        List<Token> tokens = new ArrayList<>();
        int[] raw = SqlStatementNormalizer.tokens(sql);
        for (int i = 0; i < raw.length; i += 3) {
            String text = sql.substring(raw[i], raw[i + 1]);
            String problem = problem(raw[i + 2], text);
            if (problem == null
                    && !tokens.isEmpty()
                    && quotedStringPrefix(tokens.get(tokens.size() - 1), raw[i], text)) {
                problem = "An Oracle alternative-quoted string, whose quotes this reader does not follow.";
            }
            if (problem != null) {
                return Analysis.incomplete(problem, looksLikeQuery(sql));
            }
            if (raw[i + 2] != SqlStatementNormalizer.TOKEN_COMMENT) {
                tokens.add(new Token(raw[i + 2], text, raw[i], raw[i + 1]));
            }
        }
        try {
            return new Reader(tokens).read();
        } catch (Unreadable ex) {
            return Analysis.incomplete(ex.getMessage(), true);
        } catch (RuntimeException ex) {
            // A lexical edge case must leave this statement uncounted, never fail every observation that reads SQL.
            return Analysis.incomplete("The statement could not be analysed.", looksLikeQuery(sql));
        }
    }

    /** What makes a token's text untrustworthy, or {@code null}. */
    private static String problem(int kind, String text) {
        char first = text.charAt(0);
        switch (kind) {
            case SqlStatementNormalizer.TOKEN_LITERAL:
                if (first == '\'' || first == '"') {
                    if (count(text, first) % 2 != 0 || text.length() < 2) {
                        return "A string in the statement is never closed.";
                    }
                    if (text.indexOf('\\') >= 0) {
                        return "A string holds a backslash, whose escape rules depend on the dialect.";
                    }
                } else if (first == '$' && !closedDollarQuote(text)) {
                    return "A dollar-quoted string in the statement is never closed.";
                }
                return null;
            case SqlStatementNormalizer.TOKEN_QUOTED_IDENTIFIER:
                boolean closed = first == '['
                        ? text.length() >= 2 && text.endsWith("]")
                        : text.length() >= 2 && count(text, first) % 2 == 0;
                if (!closed) {
                    return "A quoted name in the statement is never closed.";
                }
                return text.indexOf('\\') >= 0
                        ? "A quoted name holds a backslash, whose escape rules depend on the dialect."
                        : null;
            case SqlStatementNormalizer.TOKEN_COMMENT:
                if (text.startsWith("/*")) {
                    if (text.length() < 4 || !text.endsWith("*/")) {
                        return "A comment in the statement is never closed.";
                    }
                    if (text.startsWith("/*!") || text.startsWith("/*M!")) {
                        return "A MySQL or MariaDB executable comment may hold statement text.";
                    }
                    if (text.indexOf("/*", 2) >= 0) {
                        return "A comment in the statement is nested.";
                    }
                }
                if (first == '#' && mayBeAnOperator(text)) {
                    return "A # in the statement may be PostgreSQL's operator rather than a MySQL comment.";
                }
                return null;
            case SqlStatementNormalizer.TOKEN_SYMBOL:
                return first == '#' ? "A # in the statement is not a comment." : null;
            default:
                return null;
        }
    }

    /**
     * Whether a {@code #} line comment may be PostgreSQL's bitwise {@code #} operator and the operands after it: it
     * starts like an operand, or compares, which a comment rarely does. Such a statement is not read, since the text
     * the comment would hide, an {@code ORDER BY} among it, is then part of the statement.
     */
    private static boolean mayBeAnOperator(String comment) {
        String body = comment.substring(1).stripLeading();
        if (body.isEmpty()) {
            return false;
        }
        char first = body.charAt(0);
        return Character.isDigit(first)
                || "?(:@$'\"-+~".indexOf(first) >= 0
                || body.indexOf('=') >= 0
                || body.indexOf('<') >= 0
                || body.indexOf('>') >= 0;
    }

    private static boolean closedDollarQuote(String text) {
        int tagEnd = text.indexOf('$', 1);
        if (tagEnd < 0) {
            return false;
        }
        String tag = text.substring(0, tagEnd + 1);
        return text.length() >= 2 * tag.length() && text.endsWith(tag);
    }

    private static int count(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /** Whether a statement that could not be read starts as a query, so it is counted as one that was left out. */
    private static boolean looksLikeQuery(String sql) {
        String head = withoutLeadingComments(sql).toLowerCase(Locale.ROOT);
        return head.startsWith("select") || head.startsWith("with") || head.startsWith("(");
    }

    /** A statement part this reader recognises but cannot judge, which makes the whole statement incomplete. */
    private static final class Unreadable extends RuntimeException {

        Unreadable(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Whether {@code text} opens a string that directly follows a {@code q} or {@code nq} word, as {@code q'[...]'}. */
    private static boolean quotedStringPrefix(Token previous, int start, String text) {
        return text.charAt(0) == '\''
                && previous.end() == start
                && previous.kind() == SqlStatementNormalizer.TOKEN_WORD
                && (previous.text().equalsIgnoreCase("q") || previous.text().equalsIgnoreCase("nq"));
    }

    private static String withoutLeadingComments(String sql) {
        String rest = sql.stripLeading();
        while (true) {
            int end;
            if (rest.startsWith("--") || rest.startsWith("#")) {
                end = rest.indexOf('\n');
                end = end < 0 ? rest.length() : end + 1;
            } else if (rest.startsWith("/*")) {
                end = rest.indexOf("*/", 2);
                end = end < 0 ? rest.length() : end + 2;
            } else {
                return rest;
            }
            rest = rest.substring(end).stripLeading();
        }
    }

    private record Token(int kind, String text, int start, int end) {

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        boolean word(String keyword) {
            return kind == SqlStatementNormalizer.TOKEN_WORD && text.equalsIgnoreCase(keyword);
        }

        boolean symbol(char c) {
            return kind == SqlStatementNormalizer.TOKEN_SYMBOL && text.charAt(0) == c;
        }

        boolean literal() {
            return kind == SqlStatementNormalizer.TOKEN_LITERAL;
        }

        /** Whether it is the numeric literal {@code value}. */
        boolean number(long value) {
            if (!literal() || !Character.isDigit(text.charAt(0))) {
                return false;
            }
            try {
                return Long.parseLong(text) == value;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
    }

    /** Reads the keywords of one statement's outermost query block. */
    private static final class Reader {

        private final List<Token> tokens;
        private final int[] depth;
        private final int size;

        Reader(List<Token> tokens) {
            this.tokens = tokens;
            this.size = tokens.size();
            this.depth = new int[size];
        }

        Analysis read() {
            if (size == 0) {
                return Analysis.incomplete("There is no statement text.", false);
            }
            int open = 0;
            int end = size;
            for (int i = 0; i < size; i++) {
                Token token = tokens.get(i);
                if (token.symbol(')')) {
                    open--;
                    if (open < 0) {
                        return Analysis.incomplete("The statement's parentheses do not balance.", true);
                    }
                }
                depth[i] = open;
                if (token.symbol('(')) {
                    open++;
                }
                if (token.symbol(';') && open == 0) {
                    if (i + 1 < size) {
                        return Analysis.incomplete("The text holds more than one statement.", true);
                    }
                    end = i;
                }
            }
            if (open != 0) {
                return Analysis.incomplete("The statement's parentheses do not balance.", true);
            }
            int last = end;
            Token head = tokens.get(0);
            if (head.symbol('(')) {
                return Analysis.incomplete("The statement starts with a parenthesis.", true);
            }
            int main = -1;
            if (head.word("select")) {
                main = 0;
            } else if (head.word("with")) {
                for (int i = 1; i < last; i++) {
                    if (depth[i] == 0
                            && tokens.get(i).kind() == SqlStatementNormalizer.TOKEN_WORD
                            && MAIN_KEYWORDS.contains(tokens.get(i).lower())) {
                        main = i;
                        break;
                    }
                }
                if (main < 0) {
                    return Analysis.incomplete("The statement after its WITH clause was not found.", true);
                }
            }
            if (main < 0 || !tokens.get(main).word("select")) {
                return Analysis.notSelect();
            }
            return block(main, last);
        }

        private Analysis block(int main, int last) {
            boolean setOperation = false;
            boolean orderBy = false;
            boolean rowLimit = false;
            boolean offset = false;
            int where = -1;
            for (int i = main + 1; i < last; i++) {
                if (depth[i] != 0 || tokens.get(i).kind() != SqlStatementNormalizer.TOKEN_WORD) {
                    continue;
                }
                Token token = tokens.get(i);
                String word = token.lower();
                boolean qualified = i > 0 && tokens.get(i - 1).symbol('.');
                if (qualified) {
                    continue;
                }
                if (SET_OPERATORS.contains(word)) {
                    setOperation = true;
                } else if (word.equals("where") && where < 0) {
                    where = i;
                } else if (word.equals("order")
                        && i + 1 < last
                        && (tokens.get(i + 1).word("by")
                                || i + 2 < last
                                        && tokens.get(i + 1).word("siblings")
                                        && tokens.get(i + 2).word("by"))) {
                    orderBy = true;
                } else if (word.equals("limit")) {
                    Limit limit = limit(i, last);
                    if (limit != null) {
                        rowLimit |= !limit.single();
                        offset |= limit.offset();
                    }
                } else if (word.equals("offset")) {
                    int valueEnd = value(i + 1, last);
                    if (valueEnd > 0) {
                        offset |= !literalIs(i + 1, valueEnd, 0);
                    }
                } else if (word.equals("fetch")) {
                    Boolean single = fetch(i, last);
                    if (single != null) {
                        rowLimit |= !single;
                    }
                }
            }
            int projection = main + 1;
            while (projection < last
                    && (tokens.get(projection).word("distinct")
                            || tokens.get(projection).word("all"))) {
                projection++;
            }
            if (projection < last && tokens.get(projection).word("top")) {
                int valueEnd = plainCount(projection + 1, last, "TOP");
                rowLimit |= !literalIs(projection + 1, valueEnd, 1);
                projection = valueEnd;
                if (projection < last && tokens.get(projection).word("percent")) {
                    throw new Unreadable("TOP ... PERCENT is not a row count.");
                }
                if (projection + 1 < last
                        && tokens.get(projection).word("with")
                        && tokens.get(projection + 1).word("ties")) {
                    projection += 2;
                }
            }
            boolean constantOrAggregate = projection < last && constantOrAggregate(projection, last);
            boolean equalityOnly = where >= 0 && equalityOnlyWhere(where + 1, last);
            return new Analysis(
                    true, null, true, rowLimit, offset, orderBy, equalityOnly, constantOrAggregate, setOperation);
        }

        /** Whether the first projected item is a literal, {@code null}, {@code count(...)}, or {@code exists(...)}. */
        private boolean constantOrAggregate(int index, int last) {
            int cursor = index;
            while (cursor < last && tokens.get(cursor).symbol('(')) {
                cursor++;
            }
            if (cursor >= last) {
                return false;
            }
            Token first = tokens.get(cursor);
            if (first.literal() || first.word("null")) {
                return true;
            }
            return (first.word("count") || first.word("exists"))
                    && cursor + 1 < last
                    && tokens.get(cursor + 1).symbol('(');
        }

        /** One reading of {@code LIMIT}: whether it limits to a single row, and whether it skips rows. */
        private record Limit(boolean single, boolean offset) {}

        /**
         * The reading of the {@code LIMIT} at {@code index}, or {@code null} when it does not limit ({@code ALL} or
         * {@code NULL}); a count that is not a plain number or bind is unreadable.
         */
        private Limit limit(int index, int last) {
            int next = index + 1;
            if (next < last && (tokens.get(next).word("all") || tokens.get(next).word("null"))) {
                return null;
            }
            int firstEnd = plainCount(next, last, "LIMIT");
            if (firstEnd < last && tokens.get(firstEnd).symbol(',')) {
                int secondEnd = plainCount(firstEnd + 1, last, "LIMIT");
                return new Limit(literalIs(firstEnd + 1, secondEnd, 1), !literalIs(next, firstEnd, 0));
            }
            return new Limit(literalIs(next, firstEnd, 1), false);
        }

        /**
         * Whether {@code FETCH FIRST|NEXT ... ROW|ROWS ONLY|WITH TIES} starts at {@code index}, and then whether it
         * fetches a single row; {@code null} when it is not that clause.
         */
        private Boolean fetch(int index, int last) {
            int next = index + 1;
            if (next >= last
                    || !(tokens.get(next).word("first") || tokens.get(next).word("next"))) {
                return null;
            }
            int cursor = next + 1;
            boolean single = false;
            int valueEnd = value(cursor, last);
            if (valueEnd > 0) {
                single = literalIs(cursor, valueEnd, 1);
                cursor = valueEnd;
            } else if (cursor < last
                    && !tokens.get(cursor).word("row")
                    && !tokens.get(cursor).word("rows")) {
                throw new Unreadable("The FETCH row count is not a plain number or bind.");
            } else {
                single = true;
            }
            if (cursor + 1 < last
                    && (tokens.get(cursor).word("row") || tokens.get(cursor).word("rows"))
                    && (tokens.get(cursor + 1).word("only")
                            || tokens.get(cursor + 1).word("with"))) {
                return single;
            }
            throw new Unreadable("The FETCH clause is not FETCH FIRST|NEXT n ROWS ONLY or WITH TIES.");
        }

        /**
         * The end of the plain count at {@code index}; a count that is not a plain number or bind, or that an operator
         * continues after {@code LIMIT}, makes the statement unreadable. {@code TOP} is followed by the projection, which
         * may start with a symbol.
         */
        private int plainCount(int index, int last, String clause) {
            int end = value(index, last);
            boolean continued = clause.equals("LIMIT")
                    && end > 0
                    && end < last
                    && tokens.get(end).kind() == SqlStatementNormalizer.TOKEN_SYMBOL
                    && !tokens.get(end).symbol(',');
            if (end < 0 || continued) {
                throw new Unreadable("The " + clause + " count is not a plain number or bind.");
            }
            return end;
        }

        /** Whether {@code [from, to)} is the number {@code expected}, possibly in parentheses. */
        private boolean literalIs(int from, int to, long expected) {
            int start = from;
            int end = to;
            while (end - start >= 3
                    && tokens.get(start).symbol('(')
                    && tokens.get(end - 1).symbol(')')) {
                start++;
                end--;
            }
            return end - start == 1 && tokens.get(start).number(expected);
        }

        /**
         * The index just past the plain value starting at {@code index}, or {@code -1}: {@code ?}, {@code ?1},
         * {@code $1}, {@code :name}, {@code @name}, a literal, or one of them in parentheses.
         */
        private int value(int index, int last) {
            if (index >= last) {
                return -1;
            }
            Token token = tokens.get(index);
            if (token.literal()) {
                return index + 1;
            }
            if (token.symbol('?')) {
                return index + 1 < last
                                && tokens.get(index + 1).literal()
                                && onlyDigits(tokens.get(index + 1).text())
                        ? index + 2
                        : index + 1;
            }
            if (token.kind() == SqlStatementNormalizer.TOKEN_WORD
                    && token.text().length() > 1
                    && token.text().charAt(0) == '$'
                    && onlyDigits(token.text().substring(1))) {
                return index + 1;
            }
            if ((token.symbol(':') || token.symbol('@'))
                    && index + 1 < last
                    && tokens.get(index + 1).kind() == SqlStatementNormalizer.TOKEN_WORD) {
                return index + 2;
            }
            if (token.symbol('(')) {
                int inner = value(index + 1, last);
                return inner > 0 && inner < last && tokens.get(inner).symbol(')') ? inner + 1 : -1;
            }
            return -1;
        }

        private static boolean onlyDigits(String text) {
            if (text.isEmpty()) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether {@code [from, last)} up to the end of the {@code WHERE} clause is {@code column = value} terms joined
         * by {@code AND}, possibly grouped in parentheses, with a possibly signed value.
         */
        private boolean equalityOnlyWhere(int from, int last) {
            int cursor = from;
            int open = 0;
            while (true) {
                while (cursor < last && tokens.get(cursor).symbol('(')) {
                    open++;
                    cursor++;
                }
                int column = column(cursor, last);
                if (column < 0 || column >= last || !tokens.get(column).symbol('=')) {
                    return false;
                }
                int valueStart = column + 1;
                boolean signed = valueStart < last
                        && (tokens.get(valueStart).symbol('-')
                                || tokens.get(valueStart).symbol('+'));
                if (signed) {
                    valueStart++;
                }
                int valueEnd = value(valueStart, last);
                if (valueEnd < 0 || signed && !tokens.get(valueStart).literal()) {
                    return false;
                }
                cursor = valueEnd;
                if (cursor + 2 < last
                        && tokens.get(cursor).symbol(':')
                        && tokens.get(cursor + 1).symbol(':')
                        && tokens.get(cursor + 2).kind() == SqlStatementNormalizer.TOKEN_WORD) {
                    cursor += 3;
                }
                while (open > 0 && cursor < last && tokens.get(cursor).symbol(')')) {
                    open--;
                    cursor++;
                }
                if (cursor >= last || endsWhere(cursor)) {
                    return open == 0;
                }
                if (!tokens.get(cursor).word("and")) {
                    return false;
                }
                cursor++;
            }
        }

        private boolean endsWhere(int index) {
            Token token = tokens.get(index);
            return depth[index] == 0
                    && token.kind() == SqlStatementNormalizer.TOKEN_WORD
                    && AFTER_WHERE.contains(token.lower());
        }

        /** The index just past a possibly qualified column name at {@code index}, or {@code -1}. */
        private int column(int index, int last) {
            int cursor = index;
            while (true) {
                if (cursor >= last) {
                    return -1;
                }
                Token token = tokens.get(cursor);
                if (token.kind() != SqlStatementNormalizer.TOKEN_WORD
                        && token.kind() != SqlStatementNormalizer.TOKEN_QUOTED_IDENTIFIER) {
                    return -1;
                }
                cursor++;
                if (cursor + 1 < last && tokens.get(cursor).symbol('.')) {
                    cursor++;
                    continue;
                }
                return cursor;
            }
        }
    }
}
