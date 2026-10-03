package io.github.jdubois.bootui.engine.sqltrace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final String IDENTIFIER = "(?:[\\w$]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\])"
            + "(?:\\s*\\.\\s*(?:[\\w$]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]))*";

    private static final Pattern IDENTIFIERS = Pattern.compile(IDENTIFIER);
    private static final String DELETE_IDENTIFIER = IDENTIFIER + "(?:\\s*\\.\\s*\\*)?";

    private static final Pattern TABLE =
            Pattern.compile("\\b(?:from|join|into|update)\\s+(" + IDENTIFIER + ")", Pattern.CASE_INSENSITIVE);

    private static final Pattern WRITE_TARGET = Pattern.compile(
            "^\\s*(insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+(" + IDENTIFIER + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_SET =
            Pattern.compile("^\\s+(?:(?:as\\s+)?" + IDENTIFIER + "\\s+)?set\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DML_HEAD =
            Pattern.compile("^\\s*(?:insert|update|delete|merge|replace)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIALECT_TARGET = Pattern.compile(
            "^\\s*(?:insert|update|delete|merge|replace)\\s+"
                    + "(?:(?:into|from|ignore|low_priority|high_priority|delayed|quick|only)\\s+"
                    + "|top\\s*\\([^)]*\\)\\s+)*(" + IDENTIFIER + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_LIST = Pattern.compile(
            "\\b(?:from|into|update)\\s+(" + DELETE_IDENTIFIER + "(?:\\s*,\\s*" + DELETE_IDENTIFIER + ")*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DELETE_LIST = Pattern.compile(
            "^\\s*delete\\s+(" + DELETE_IDENTIFIER + "(?:\\s*,\\s*" + DELETE_IDENTIFIER + ")*)\\s+from\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> TARGET_MODIFIERS =
            Set.of("ignore", "low_priority", "high_priority", "delayed", "quick", "top", "all", "first");

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
            String table = tableName(matcher.group(1));
            if (!table.isEmpty() && !NOT_TABLES.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * The single target named at an INSERT INTO, UPDATE, DELETE FROM, or MERGE INTO head, or {@code null} when it
     * cannot be identified confidently. CTEs, dialect modifiers, multi-target updates, and targets resolved through
     * FROM aliases are not parsed. Read-side tables never substitute for a missing target.
     */
    public static String writeTarget(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String text = withoutLiteralsAndComments(sql, true);
        Matcher matcher = WRITE_TARGET.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String target = tableName(matcher.group(2));
        if (NOT_TABLES.contains(target) || TARGET_MODIFIERS.contains(target)) {
            return null;
        }
        boolean update = matcher.group(1).equalsIgnoreCase("update");
        boolean delete = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("delete");
        String rest = text.substring(matcher.end());
        if (delete
                && (rest.stripLeading().startsWith(",") || rest.stripLeading().startsWith(".*"))) {
            return null;
        }
        if (update && !UPDATE_SET.matcher(rest).find()) {
            return null;
        }
        if ((update || delete) && !target.contains(".")) {
            Matcher from = FROM.matcher(rest);
            if (from.find()) {
                // Double-quoted aliases are removed like values by the sanitizer, so their target is uncertain.
                if (matcher.group(2).startsWith("\"")) {
                    return null;
                }
                Pattern alias = Pattern.compile(
                        "[\\w$\"`\\]\\)]\\s+(?:as\\s+)?" + Pattern.quote(matcher.group(2)) + "(?=\\s|[,;)]|$)",
                        Pattern.CASE_INSENSITIVE);
                if (alias.matcher(rest.substring(from.end())).find()) {
                    return null;
                }
            }
        }
        return target;
    }

    /** Each captured DML statement's targets, including every statement of a JDBC batch preview. */
    public static List<WriteTargets> writes(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        String text = withoutLiteralsAndComments(sql, true);
        List<WriteTargets> writes = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '"' || c == '`' || c == '[') {
                index = skipQuoted(text, index, c == '[' ? ']' : c);
                continue;
            }
            if (c == ';') {
                addWrite(writes, text.substring(start, index));
                start = index + 1;
            }
            index++;
        }
        addWrite(writes, text.substring(start));
        return List.copyOf(writes);
    }

    /**
     * An exact lexical target for a simple DML head, or candidate names for ambiguous dialect forms. Candidates may
     * include read-side tables or aliases and must never be presented as proven writes.
     */
    public record WriteTargets(Set<String> tables, boolean exact) {
        public WriteTargets {
            tables = Set.copyOf(tables);
        }
    }

    private static void addWrite(List<WriteTargets> writes, String statement) {
        if (!DML_HEAD.matcher(statement).find()) {
            return;
        }
        String target = writeTarget(statement);
        if (target != null) {
            writes.add(new WriteTargets(Set.of(target), true));
            return;
        }
        Set<String> candidates = new LinkedHashSet<>(of(statement));
        candidates.removeAll(TARGET_MODIFIERS);
        addLists(candidates, DIALECT_TARGET.matcher(statement));
        addLists(candidates, TABLE_LIST.matcher(statement));
        addLists(candidates, DELETE_LIST.matcher(statement));
        candidates.remove("set");
        if (!candidates.isEmpty()) {
            writes.add(new WriteTargets(candidates, false));
        }
    }

    private static void addLists(Set<String> candidates, Matcher lists) {
        while (lists.find()) {
            Matcher names = IDENTIFIERS.matcher(lists.group(1));
            while (names.find() && candidates.size() < MAX_TABLES) {
                String name = tableName(names.group());
                if (!NOT_TABLES.contains(name) && !TARGET_MODIFIERS.contains(name)) {
                    candidates.add(name);
                }
            }
        }
    }

    private static String tableName(String identifier) {
        return identifier.replaceAll("[\"`\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Removes values and comments before the table pattern runs, so table-like words inside them cannot
     * become metadata. Quoted identifiers following a table keyword stay intact, including PostgreSQL
     * identifiers that contain punctuation or non-ASCII characters.
     */
    private static String withoutLiteralsAndComments(String sql) {
        return withoutLiteralsAndComments(sql, false);
    }

    private static String withoutLiteralsAndComments(String sql, boolean targetLists) {
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
                if (isTableKeyword(word)
                        || (targetLists && "delete".equalsIgnoreCase(word))
                        || (targetLists
                                && tableIdentifierExpected
                                && (TARGET_MODIFIERS.contains(word.toLowerCase(Locale.ROOT))
                                        || "only".equalsIgnoreCase(word)))) {
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
            if (targetLists && c == ',' && tableIdentifierContinues) {
                tableIdentifierExpected = true;
                tableIdentifierContinues = false;
                index++;
                continue;
            }
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
