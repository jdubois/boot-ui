package io.github.jdubois.bootui.engine.javaagent;

import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import java.util.Arrays;

/**
 * An SQL statement's target for a {@code security-sinks} row ({@code docs/PLAN-v2.md} §5.16, M5-6b): the statement with
 * every literal masked by SQL Trace's own lexer ({@link SqlStatementNormalizer#ranges}: quoted strings with either
 * escaping, MySQL's double-quoted strings, PostgreSQL's dollar quoting, prefixed and typed literals, and numbers), whatever
 * the exposure policy, except that a literal holding a matched value shows {@code {name}} in its place; comments are
 * dropped; whitespace runs are collapsed. And, for each matched value, whether it sat inside a literal or outside one,
 * where an identifier, a keyword, or an operator stands. Never a value or a literal's text.
 */
final class SqlSinkText {

    /** The characters of a statement this masks at most: the holder scans no more of a sink's text. */
    static final int MAX_LENGTH = 16 * 1024;

    private SqlSinkText() {}

    /**
     * The masked statement, {@code positions} filled per value index with {@link AgentRequestValues#POSITION_IN_LITERAL}
     * or {@link AgentRequestValues#POSITION_OUTSIDE_LITERAL}, from the first span of each value, with
     * {@link AgentRequestValues#FLAG_BARE_LITERAL} for a value inside a number or {@code true}/{@code false}, and
     * {@link AgentRequestValues#FLAG_CROSSES_LITERAL} for one that crosses a literal's or a comment's bounds.
     *
     * <p>Every character is classified first (the literal or comment holding it, or none), then emitted from that alone:
     * a character inside a literal or a comment is never copied, whatever a value's span did beside it.
     */
    static String mask(String sql, int[] spans, String[] names, int[] positions) {
        int length = Math.min(sql.length(), MAX_LENGTH);
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        int[] ranges = SqlStatementNormalizer.ranges(sql.substring(0, length));
        // The range (its offset in ranges) holding each character, -1 for none.
        int[] region = new int[length];
        Arrays.fill(region, -1);
        for (int r = 0; r + 2 < ranges.length; r += 3) {
            for (int c = Math.max(0, ranges[r]); c < ranges[r + 1] && c < length; c++) {
                region[c] = r;
            }
        }
        // Which value covers each character: inside one literal or comment (value), or anywhere else (outside), -1 for
        // none. A span crossing a literal's or a comment's bounds is outside as a whole.
        int[] value = new int[length];
        int[] outside = new int[length];
        Arrays.fill(value, -1);
        Arrays.fill(outside, -1);
        boolean[] crosses = new boolean[positions.length];
        // Per value: whether every span crossing a bound is only a sign or spaces beside one number, as -33.8688, whose
        // minus SQL Trace's lexer leaves outside the literal.
        boolean[] signed = new boolean[positions.length];
        boolean[] other = new boolean[positions.length];
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            int index = spans[slot];
            int from = Math.max(0, spans[slot + 1]);
            int to = Math.min(spans[slot + 2], length);
            if (from >= to) {
                continue;
            }
            int first = region[from];
            boolean same = true;
            for (int c = from; c < to; c++) {
                same &= region[c] == first;
            }
            boolean contained = same && first >= 0;
            if (!same && index >= 0 && index < crosses.length) {
                crosses[index] = true;
                if (signedNumber(sql, region, ranges, from, to)) {
                    signed[index] = true;
                } else {
                    other[index] = true;
                }
            }
            for (int c = from; c < to; c++) {
                if (value[c] < 0 && outside[c] < 0) {
                    (contained ? value : outside)[c] = index;
                }
            }
        }
        boolean[] decided = new boolean[positions.length];
        StringBuilder out = new StringBuilder(Math.min(length, 2_048));
        int i = 0;
        while (i < length) {
            if (outside[i] >= 0) {
                i = outsideSpan(out, outside, i, positions, decided, names);
                continue;
            }
            int r = region[i];
            if (r >= 0) {
                // The rest of this literal or comment up to its end, or to a value outside it that cut it.
                int end = i;
                while (end < length && region[end] == r && outside[end] < 0) {
                    end++;
                }
                if (ranges[r + 2] != SqlStatementNormalizer.RANGE_LITERAL) {
                    // A comment: dropped, a value inside it being outside any literal.
                    mark(value, i, end, positions, decided, AgentRequestValues.POSITION_OUTSIDE_LITERAL);
                    space(out);
                } else {
                    boolean quoted = quoted(sql, ranges[r], Math.min(ranges[r + 1], length));
                    if (i == ranges[r] && end == Math.min(ranges[r + 1], length)) {
                        literal(out, sql, value, i, end, quoted, positions, decided, names);
                    } else {
                        piece(out, sql, value, i, end, quoted, positions, decided, names);
                    }
                }
                i = end;
                continue;
            }
            // Neither a literal, a comment, nor a value: SQL's own text.
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                space(out);
            } else {
                out.append(c);
            }
            i++;
        }
        for (int index = 0; index < positions.length; index++) {
            if (crosses[index] && decided[index]) {
                positions[index] = AgentRequestValues.POSITION_OUTSIDE_LITERAL
                        | AgentRequestValues.FLAG_CROSSES_LITERAL
                        | (signed[index] && !other[index] ? AgentRequestValues.FLAG_BARE_LITERAL : 0);
            }
        }
        if (sql.length() > length) {
            out.append(" …");
        }
        return out.toString().trim();
    }

    /**
     * Whether characters {@code from} to {@code to} are a sign or spaces outside any literal and comment, and the rest one
     * unquoted literal (a number, true, or false): a signed number such as {@code -33.8688}, never a fact on its own.
     */
    private static boolean signedNumber(String sql, int[] region, int[] ranges, int from, int to) {
        int literal = -1;
        for (int c = from; c < to; c++) {
            int r = region[c];
            if (r < 0) {
                char ch = sql.charAt(c);
                if (ch != '-' && ch != '+' && !Character.isWhitespace(ch)) {
                    return false;
                }
            } else if (literal < 0) {
                literal = r;
            } else if (literal != r) {
                return false;
            }
        }
        return literal >= 0
                && ranges[literal + 2] == SqlStatementNormalizer.RANGE_LITERAL
                && !quoted(sql, ranges[literal], Math.min(ranges[literal + 1], region.length));
    }

    /**
     * Writes the part of a literal a value outside it cut off: nothing when it is only delimiters, such as the quote a
     * value closed; otherwise {@code ?} or its own values' names, as a whole literal's.
     */
    private static void piece(
            StringBuilder out,
            String sql,
            int[] value,
            int start,
            int end,
            boolean quoted,
            int[] positions,
            boolean[] decided,
            String[] names) {
        boolean delimiters = true;
        for (int c = start; c < end && delimiters; c++) {
            char ch = sql.charAt(c);
            delimiters = value[c] < 0 && (ch == '\'' || ch == '"' || ch == '$' || Character.isWhitespace(ch));
        }
        if (!delimiters) {
            literal(out, sql, value, start, end, quoted, positions, decided, names);
        }
    }

    /**
     * Writes a literal from {@code start} to {@code end}: {@code ?}, or its matched values' names in its place. Never a
     * character of it: {@code quoted} says whether the literal is a string, rather than a number, true, or false.
     */
    private static void literal(
            StringBuilder out,
            String sql,
            int[] value,
            int start,
            int end,
            boolean quoted,
            int[] positions,
            boolean[] decided,
            String[] names) {
        StringBuilder inside = new StringBuilder();
        int last = -1;
        boolean gap = false;
        int[] body = body(sql, start, end);
        for (int c = start; c < end; c++) {
            if ((c < body[0] || c >= body[1]) && value[c] < 0) {
                // A delimiter, as a quote, a prefix, or a dollar tag: not content beside the value.
                continue;
            }
            int index = value[c];
            if (index < 0) {
                gap = true;
                last = -1;
                continue;
            }
            if (index < positions.length && !decided[index]) {
                // A number, true, or false: a value matching one is not a fact on its own.
                positions[index] =
                        AgentRequestValues.POSITION_IN_LITERAL | (quoted ? 0 : AgentRequestValues.FLAG_BARE_LITERAL);
                decided[index] = true;
            }
            if (index != last) {
                if (gap) {
                    inside.append('…');
                    gap = false;
                }
                inside.append('{').append(name(names, index)).append('}');
                last = index;
            }
        }
        if (inside.length() == 0) {
            out.append('?');
            return;
        }
        if (gap) {
            inside.append('…');
        }
        if (quoted) {
            out.append('\'').append(inside).append('\'');
        } else {
            out.append(inside);
        }
    }

    /**
     * A literal's content between its delimiters, {@code {from, to}}: inside the quotes of {@code '…'}, {@code "…"}, and
     * a prefixed {@code E'…'}, inside the tags of {@code $tag$…$tag$}; the whole literal for a number.
     */
    private static int[] body(String sql, int start, int end) {
        for (int c = start; c < end; c++) {
            char ch = sql.charAt(c);
            if (ch == '\'' || ch == '"') {
                int to = end - 1 > c && sql.charAt(end - 1) == ch ? end - 1 : end;
                return new int[] {c + 1, to};
            }
            if (ch == '$') {
                int tag = sql.indexOf('$', c + 1);
                if (tag < 0 || tag >= end) {
                    return new int[] {start, end};
                }
                int width = tag - c + 1;
                return new int[] {tag + 1, Math.max(tag + 1, end - width)};
            }
            if (!Character.isLetter(ch)) {
                break;
            }
        }
        return new int[] {start, end};
    }

    /** Whether a literal is a string (quoted, dollar-quoted, or prefixed), rather than a number. */
    private static boolean quoted(String sql, int start, int end) {
        for (int c = start; c < end; c++) {
            char ch = sql.charAt(c);
            if (ch == '\'' || ch == '"' || ch == '$') {
                return true;
            }
            if (!Character.isLetter(ch)) {
                return false;
            }
        }
        return false;
    }

    /** Writes a matched value outside any literal as {@code {name}}, returning the index past its span. */
    private static int outsideSpan(
            StringBuilder out, int[] value, int start, int[] positions, boolean[] decided, String[] names) {
        int index = value[start];
        int end = start;
        while (end < value.length && value[end] == index) {
            end++;
        }
        mark(value, start, end, positions, decided, AgentRequestValues.POSITION_OUTSIDE_LITERAL);
        out.append('{').append(name(names, index)).append('}');
        return end;
    }

    private static void mark(int[] value, int start, int end, int[] positions, boolean[] decided, int position) {
        for (int c = start; c < end && c < value.length; c++) {
            int index = value[c];
            if (index >= 0 && index < positions.length && !decided[index]) {
                positions[index] = position;
                decided[index] = true;
            }
        }
    }

    private static void space(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }

    private static String name(String[] names, int index) {
        String name = names != null && index >= 0 && index < names.length ? names[index] : null;
        return name == null ? "param" : name;
    }
}
