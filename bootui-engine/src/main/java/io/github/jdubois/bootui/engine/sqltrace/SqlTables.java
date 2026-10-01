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
        Matcher matcher = TABLE.matcher(sql);
        while (matcher.find() && tables.size() < MAX_TABLES) {
            String table = matcher.group(1).replaceAll("[\"`\\[\\]\\s]", "").toLowerCase(Locale.ROOT);
            if (!table.isEmpty() && !NOT_TABLES.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }
}
