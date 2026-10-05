package io.github.jdubois.bootui.engine.javaagent;

/**
 * An SQL statement's target for a {@code security-sinks} row ({@code docs/PLAN-v2.md} §5.16, M5-6b): the statement with
 * every literal masked, as SQL Trace's fingerprint masks them, whatever the exposure policy, except that a literal
 * holding a matched value shows {@code {name}} in its place; and, for each matched value, whether it sat inside a
 * literal (a quoted string or a number) or outside one, where an identifier, a keyword, or an operator stands. Quoted
 * identifiers and comments are outside literals; a comment's text is dropped. Whitespace runs are collapsed. Never a
 * value or a literal's text.
 */
final class SqlSinkText {

    private SqlSinkText() {}

    /**
     * The masked statement, {@code positions} filled per value index with {@link AgentRequestValues#POSITION_IN_LITERAL}
     * or {@link AgentRequestValues#POSITION_OUTSIDE_LITERAL}, from the first span of each value.
     */
    static String mask(String sql, int[] spans, String[] names, int[] positions) {
        int length = sql.length();
        int count = Math.min(spans[AgentRequestValues.S_COUNT], AgentRequestValues.MAX_SPANS);
        // Which value covers each character, -1 for none.
        int[] value = new int[length];
        java.util.Arrays.fill(value, -1);
        for (int s = 0; s < count; s++) {
            int slot = AgentRequestValues.S_FIRST + 3 * s;
            int index = spans[slot];
            for (int c = Math.max(0, spans[slot + 1]); c < spans[slot + 2] && c < length; c++) {
                if (value[c] < 0) {
                    value[c] = index;
                }
            }
        }
        boolean[] decided = new boolean[positions.length];
        StringBuilder out = new StringBuilder(Math.min(length, 2_048));
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = stringEnd(sql, i);
                literal(out, value, i + 1, end, positions, decided, names, true);
                i = end + 1;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? length : end;
                outside(value, i, end, positions, decided);
                out.append("/* */");
                i = end;
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                outside(value, i, end, positions, decided);
                out.append("/* */");
                i = end;
            } else if (Character.isDigit(c) && (i == 0 || !identifierPart(sql.charAt(i - 1)))) {
                int end = i;
                while (end < length && (Character.isDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
                    end++;
                }
                literal(out, value, i, end, positions, decided, names, false);
                i = end;
            } else if (Character.isWhitespace(c)) {
                if (value[i] >= 0) {
                    i = outsideSpan(out, value, i, positions, decided, names);
                    continue;
                }
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                i++;
            } else if (value[i] >= 0) {
                i = outsideSpan(out, value, i, positions, decided, names);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().trim();
    }

    /** Writes a literal from {@code start} to {@code end}: {@code ?}, or its matched values' names in its place. */
    private static void literal(
            StringBuilder out,
            int[] value,
            int start,
            int end,
            int[] positions,
            boolean[] decided,
            String[] names,
            boolean quoted) {
        StringBuilder inside = new StringBuilder();
        int last = -1;
        boolean gap = false;
        for (int c = start; c < end && c < value.length; c++) {
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
        if (quoted) {
            out.append('\'').append(inside).append('\'');
        } else {
            out.append(inside);
        }
    }

    /** Writes a matched value outside any literal as {@code {name}}, returning the index past its span. */
    private static int outsideSpan(
            StringBuilder out, int[] value, int start, int[] positions, boolean[] decided, String[] names) {
        int index = value[start];
        int end = start;
        while (end < value.length && value[end] == index) {
            end++;
        }
        if (index < positions.length && !decided[index]) {
            positions[index] = AgentRequestValues.POSITION_OUTSIDE_LITERAL;
            decided[index] = true;
        }
        out.append('{').append(name(names, index)).append('}');
        return end;
    }

    private static void outside(int[] value, int start, int end, int[] positions, boolean[] decided) {
        for (int c = start; c < end && c < value.length; c++) {
            int index = value[c];
            if (index >= 0 && index < positions.length && !decided[index]) {
                positions[index] = AgentRequestValues.POSITION_OUTSIDE_LITERAL;
                decided[index] = true;
            }
        }
    }

    /** The index of the quote closing the string literal opening at {@code start}, {@code ''} escapes skipped. */
    private static int stringEnd(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return sql.length();
    }

    private static boolean identifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '"' || c == '`' || c == '.';
    }

    private static String name(String[] names, int index) {
        String name = names != null && index >= 0 && index < names.length ? names[index] : null;
        return name == null ? "param" : name;
    }
}
