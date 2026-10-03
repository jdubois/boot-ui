package io.github.jdubois.bootui.engine.sqltrace;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tables a statement names after {@code FROM}, {@code JOIN}, {@code INTO}, or {@code UPDATE}, read from its text
 * ({@code docs/PLAN-v2.md} §5.3). It never parses SQL: a subquery, a table function, or a table a view reaches is not
 * listed, so callers present the result as the tables a statement names.
 */
public final class SqlTables {

    /** The most tables read from one statement. */
    public static final int MAX_TABLES = 16;

    private static final Pattern TABLE = Pattern.compile(
            "\\b(?:from|join|into|update)\\s+((?:[\\w$]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\])"
                    + "(?:\\s*\\.\\s*(?:[\\w$]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]))*)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> NOT_TABLES = Set.of("select", "lateral", "unnest", "values", "only", "dual");

    private SqlTables() {}

    /** The tables {@code sql} names, lower-cased and unquoted, in first-seen order. */
    public static Set<String> of(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return tables;
        }
        Matcher matcher = TABLE.matcher(withoutLiteralsAndComments(sql));
        while (matcher.find() && tables.size() < MAX_TABLES) {
            String table = matcher.group(1).replaceAll("[\"`\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
            if (!table.isEmpty() && !NOT_TABLES.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * Removes values and comments before the table pattern runs, so table-like words inside them cannot
     * become metadata. Quoted identifiers following a table keyword stay intact, including PostgreSQL
     * identifiers that contain punctuation or non-ASCII characters.
     */
    private static String withoutLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        boolean tableIdentifierExpected = false;
        boolean tableIdentifierContinues = false;
        while (index < sql.length()) {
            char c = sql.charAt(index);
            if (lineCommentAt(sql, index)) {
                index = skipLineComment(sql, index);
                appendSpace(out);
                continue;
            }
            if (blockCommentAt(sql, index)) {
                index = skipBlockComment(sql, index);
                appendSpace(out);
                continue;
            }
            if (c == '\'') {
                index = skipQuoted(sql, index, '\'');
                out.append('?');
                tableIdentifierExpected = false;
                tableIdentifierContinues = false;
                continue;
            }
            if (c == '$') {
                int dollarEnd = dollarQuoteEnd(sql, index);
                if (dollarEnd >= 0) {
                    index = dollarEnd;
                    out.append('?');
                    tableIdentifierExpected = false;
                    tableIdentifierContinues = false;
                    continue;
                }
            }
            if (c == '"') {
                int end = skipQuoted(sql, index, '"');
                boolean tableIdentifier =
                        tableIdentifierExpected || (tableIdentifierContinues && previousNonWhitespace(out) == '.');
                out.append(tableIdentifier ? sql.substring(index, end) : "?");
                index = end;
                tableIdentifierExpected = false;
                tableIdentifierContinues = tableIdentifier;
                continue;
            }
            if (c == '`' || c == '[') {
                int end = skipQuoted(sql, index, c == '[' ? ']' : c);
                out.append(sql, index, end);
                index = end;
                tableIdentifierExpected = false;
                tableIdentifierContinues = true;
                continue;
            }
            if (isIdentifierCharacter(c)) {
                int end = index + 1;
                while (end < sql.length() && isIdentifierCharacter(sql.charAt(end))) {
                    end++;
                }
                String word = sql.substring(index, end);
                out.append(word);
                if (isTableKeyword(word)) {
                    tableIdentifierExpected = true;
                    tableIdentifierContinues = false;
                } else {
                    tableIdentifierContinues = tableIdentifierExpected;
                    tableIdentifierExpected = false;
                }
                index = end;
                continue;
            }
            out.append(c);
            if (!Character.isWhitespace(c) && c != '.') {
                tableIdentifierExpected = false;
                tableIdentifierContinues = false;
            }
            index++;
        }
        return out.toString();
    }

    private static boolean isTableKeyword(String word) {
        return "from".equalsIgnoreCase(word)
                || "join".equalsIgnoreCase(word)
                || "into".equalsIgnoreCase(word)
                || "update".equalsIgnoreCase(word);
    }

    private static boolean lineCommentAt(String sql, int index) {
        return sql.charAt(index) == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-';
    }

    private static boolean blockCommentAt(String sql, int index) {
        return sql.charAt(index) == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*';
    }

    private static int skipLineComment(String sql, int index) {
        int cursor = index + 2;
        while (cursor < sql.length() && sql.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor;
    }

    private static int skipBlockComment(String sql, int index) {
        int cursor = index + 2;
        while (cursor + 1 < sql.length() && !(sql.charAt(cursor) == '*' && sql.charAt(cursor + 1) == '/')) {
            cursor++;
        }
        return Math.min(sql.length(), cursor + 2);
    }

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

    private static int dollarQuoteEnd(String sql, int index) {
        if (index > 0 && isIdentifierCharacter(sql.charAt(index - 1))) {
            return -1;
        }
        int cursor = index + 1;
        int bodyStart = -1;
        while (cursor < sql.length()) {
            char c = sql.charAt(cursor);
            if (c == '$') {
                bodyStart = cursor + 1;
                break;
            }
            if (!isDollarTagCharacter(c, cursor > index + 1)) {
                return -1;
            }
            cursor++;
        }
        if (bodyStart < 0) {
            return -1;
        }
        String tag = sql.substring(index, bodyStart);
        int end = sql.indexOf(tag, bodyStart);
        return end < 0 ? sql.length() : end + tag.length();
    }

    private static boolean isDollarTagCharacter(char c, boolean afterFirst) {
        return Character.isLetter(c) || c == '_' || (afterFirst && Character.isDigit(c));
    }

    private static boolean isIdentifierCharacter(char c) {
        return c >= 128 || Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static char previousNonWhitespace(StringBuilder text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            char c = text.charAt(index);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return '\0';
    }

    private static void appendSpace(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }
}
