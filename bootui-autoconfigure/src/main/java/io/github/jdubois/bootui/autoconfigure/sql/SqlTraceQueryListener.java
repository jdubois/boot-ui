package io.github.jdubois.bootui.autoconfigure.sql;

import io.github.jdubois.bootui.core.dto.SqlTraceQueryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * datasource-proxy listener that records every JDBC statement execution into the {@link
 * SqlTraceStore}.
 *
 * <p>The listener is invoked on the application's own query path, so it must never throw: any
 * failure while building a trace entry is swallowed and logged at debug level.</p>
 */
public class SqlTraceQueryListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SqlTraceQueryListener.class);

    private static final int MAX_STATEMENTS = 50;
    private static final int MAX_SQL_LENGTH = 4_000;
    private static final int MAX_PARAMETER_SETS = 100;
    private static final int MAX_PARAMETER_VALUE_LENGTH = 256;

    private final SqlTraceStore store;

    public SqlTraceQueryListener(SqlTraceStore store) {
        this.store = store;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // No-op: timing and outcome are only known after execution.
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        if (!store.isRecording()) {
            return;
        }
        try {
            store.add(toDto(execInfo, queryInfoList));
        } catch (RuntimeException ex) {
            log.debug("BootUI SQL trace failed to record a query", ex);
        }
    }

    private SqlTraceQueryDto toDto(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        List<String> statements = new ArrayList<>();
        for (QueryInfo queryInfo : queryInfoList) {
            if (statements.size() >= MAX_STATEMENTS) {
                break;
            }
            statements.add(truncate(queryInfo.getQuery(), MAX_SQL_LENGTH));
        }
        List<String> parameters = store.isCaptureParameters() ? extractParameters(queryInfoList) : null;
        long elapsed = Math.max(0, execInfo.getElapsedTime());
        boolean success = execInfo.isSuccess() && execInfo.getThrowable() == null;
        long threshold = store.slowQueryThresholdMillis();
        boolean slow = threshold > 0 && elapsed >= threshold;
        return new SqlTraceQueryDto(
                0L,
                System.currentTimeMillis(),
                execInfo.getDataSourceName(),
                execInfo.getConnectionId(),
                statementType(execInfo.getStatementType()),
                category(statements),
                execInfo.isBatch(),
                execInfo.getBatchSize(),
                elapsed,
                success,
                slow,
                success ? null : throwableMessage(execInfo.getThrowable()),
                Thread.currentThread().getName(),
                List.copyOf(statements),
                parameters);
    }

    private static List<String> extractParameters(List<QueryInfo> queryInfoList) {
        List<String> parameters = new ArrayList<>();
        for (QueryInfo queryInfo : queryInfoList) {
            List<Map<String, Object>> argsList;
            try {
                argsList = queryInfo.getQueryArgsList();
            } catch (RuntimeException ex) {
                continue;
            }
            for (Map<String, Object> args : argsList) {
                if (parameters.size() >= MAX_PARAMETER_SETS) {
                    return parameters;
                }
                parameters.add(formatParameterSet(args));
            }
        }
        return parameters;
    }

    private static String formatParameterSet(Map<String, Object> args) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(args.entrySet());
        entries.sort((a, b) -> {
            Integer left = parseIndex(a.getKey());
            Integer right = parseIndex(b.getKey());
            if (left != null && right != null) {
                return Integer.compare(left, right);
            }
            return a.getKey().compareTo(b.getKey());
        });
        List<String> values = new ArrayList<>(entries.size());
        for (Map.Entry<String, Object> entry : entries) {
            values.add(formatValue(entry.getValue()));
        }
        return "[" + String.join(", ", values) + "]";
    }

    private static Integer parseIndex(String key) {
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        return truncate(Objects.toString(value), MAX_PARAMETER_VALUE_LENGTH);
    }

    private static String statementType(StatementType type) {
        return type == null ? "UNKNOWN" : type.name();
    }

    private static String category(List<String> statements) {
        if (statements.isEmpty()) {
            return "OTHER";
        }
        String keyword = firstKeyword(statements.get(0));
        return switch (keyword) {
            case "SELECT", "WITH" -> "SELECT";
            case "INSERT" -> "INSERT";
            case "UPDATE" -> "UPDATE";
            case "DELETE" -> "DELETE";
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "RENAME" -> "DDL";
            default -> "OTHER";
        };
    }

    private static String firstKeyword(String sql) {
        if (sql == null) {
            return "";
        }
        int i = 0;
        int length = sql.length();
        // Skip leading whitespace, line comments, and block comments.
        while (i < length) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? length : newline + 1;
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < length && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(java.util.Locale.ROOT);
    }

    private static String throwableMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getMessage();
        String text = (message == null || message.isBlank())
                ? throwable.getClass().getSimpleName()
                : throwable.getClass().getSimpleName() + ": " + message;
        return truncate(text, MAX_SQL_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
    }
}
