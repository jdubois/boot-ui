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
     * or {@link AgentRequestValues#POSITION_OUTSIDE_LITERAL}, from the first span of each value.
     */
    static String mask(String sql, int[] spans, String[] names, int[] positions) {
        int length = Math.min(sql.length(), MAX_LENGTH);
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        // Which value covers each character, -1 for none.
        int[] value = new int[length];
        Arrays.fill(value, -1);
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            int index = spans[slot];
            for (int c = Math.max(0, spans[slot + 1]); c < spans[slot + 2] && c < length; c++) {
                if (value[c] < 0) {
                    value[c] = index;
                }
            }
        }
        int[] ranges = SqlStatementNormalizer.ranges(sql.substring(0, length));
        boolean[] decided = new boolean[positions.length];
        StringBuilder out = new StringBuilder(Math.min(length, 2_048));
        int i = 0;
        int r = 0;
        while (i < length) {
            if (r < ranges.length && ranges[r] == i) {
                int end = Math.min(ranges[r + 1], length);
                if (ranges[r + 2] == SqlStatementNormalizer.RANGE_LITERAL) {
                    literal(out, sql, value, i, end, positions, decided, names);
                } else {
                    // A comment: dropped, a value inside it being outside any literal.
                    mark(value, i, end, positions, decided, AgentRequestValues.POSITION_OUTSIDE_LITERAL);
                    space(out);
                }
                i = Math.max(end, i + 1);
                r += 3;
                continue;
            }
            char c = sql.charAt(i);
            if (value[i] >= 0) {
                i = outsideSpan(out, value, i, positions, decided, names);
            } else if (Character.isWhitespace(c)) {
                space(out);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        if (sql.length() > length) {
            out.append(" …");
        }
        return out.toString().trim();
    }

    /** Writes a literal from {@code start} to {@code end}: {@code ?}, or its matched values' names in its place. */
    private static void literal(
            StringBuilder out,
            String sql,
            int[] value,
            int start,
            int end,
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
                positions[index] = AgentRequestValues.POSITION_IN_LITERAL;
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
        if (quoted(sql, start, end)) {
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
