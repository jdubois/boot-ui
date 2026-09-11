package io.github.jdubois.bootui.engine.explorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded lexical SQL references, not a dialect parser or a physical-schema claim. CTEs, nested
 * SELECTs, table-valued functions and multi-statement batches deliberately remain partial evidence.
 * Quoted identifiers retain their quoting and case; literals and comments can never become targets.
 */
public final class SqlReferenceExtractor {
    public static final int MAX_REFERENCES = 64;
    private static final int MAX_CHARS = 32_768;
    private static final int MAX_TOKENS = 2_048;
    private static final Set<String> RESERVED = Set.of(
            "SELECT",
            "FROM",
            "JOIN",
            "WHERE",
            "SET",
            "VALUES",
            "INTO",
            "ON",
            "AS",
            "ONLY",
            "LATERAL",
            "RETURNING",
            "WITH",
            "USING",
            "UNION",
            "DELETE",
            "UPDATE",
            "INSERT",
            "IGNORE",
            "LOW_PRIORITY",
            "HIGH_PRIORITY",
            "DELAYED",
            "TOP",
            "FOR",
            "LIMIT",
            "OFFSET",
            "FETCH",
            "ORDER",
            "GROUP",
            "HAVING",
            "WINDOW",
            "QUALIFY",
            "LOCK");
    private static final Set<String> FROM_BOUNDARIES = Set.of(
            "WHERE",
            "GROUP",
            "ORDER",
            "HAVING",
            "RETURNING",
            "SET",
            "VALUES",
            "UNION",
            "FOR",
            "LIMIT",
            "OFFSET",
            "FETCH",
            "WINDOW",
            "QUALIFY",
            "LOCK");

    public record Result(List<String> identifiers, String status) {
        public Result {
            identifiers = List.copyOf(identifiers);
        }
    }

    public Result extract(String sql, int batchSize) {
        if (sql == null || sql.isBlank()) {
            return new Result(List.of(), "UNAVAILABLE");
        }
        Scan scan = tokenize(sql.substring(0, Math.min(sql.length(), MAX_CHARS)));
        List<Token> tokens = scan.tokens;
        if (tokens.isEmpty()) {
            return new Result(List.of(), "UNAVAILABLE");
        }
        String verb = tokens.get(0).keyword();
        if (!Set.of("SELECT", "INSERT", "UPDATE", "DELETE").contains(verb)) {
            return new Result(List.of(), "WITH".equals(verb) ? "PARTIAL" : "UNAVAILABLE");
        }
        // Avoid mistaking CTE aliases or derived tables for physical references.
        for (int i = 1; i < tokens.size(); i++) {
            if ("SELECT".equals(tokens.get(i).keyword())
                    || "WITH".equals(tokens.get(i).keyword())) {
                return new Result(List.of(), "PARTIAL");
            }
        }
        boolean partial = scan.partial || batchSize > 0 || sql.length() > MAX_CHARS || sql.contains("…");
        LinkedHashSet<String> references = new LinkedHashSet<>();
        boolean fromList = false;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String keyword = token.keyword();
            if (";".equals(token.text)) {
                partial |= i < tokens.size() - 1;
                break;
            }
            if ("(".equals(token.text)) depth++;
            if (")".equals(token.text)) depth--;
            if (depth != 0) continue;
            if (FROM_BOUNDARIES.contains(keyword)) {
                fromList = false;
            }
            boolean target = "FROM".equals(keyword)
                    || "JOIN".equals(keyword)
                    || ("INTO".equals(keyword) && "INSERT".equals(verb))
                    || (i == 0 && "UPDATE".equals(verb))
                    || (fromList && ",".equals(token.text));
            if (!target) continue;
            fromList |= "FROM".equals(keyword);
            int next = i + 1;
            if (next >= tokens.size() || !identifier(tokens.get(next))) {
                partial = true;
                continue;
            }
            StringBuilder name = new StringBuilder(tokens.get(next++).text);
            boolean completeName = true;
            while (next < tokens.size() && ".".equals(tokens.get(next).text)) {
                if (++next >= tokens.size() || !identifier(tokens.get(next))) {
                    partial = true;
                    completeName = false;
                    break;
                }
                name.append('.').append(tokens.get(next++).text);
            }
            if (!completeName) continue;
            // A function or derived relation is not a table. INSERT's column list is different.
            if (next < tokens.size() && "(".equals(tokens.get(next).text) && !"INSERT".equals(verb)) {
                partial = true;
                continue;
            }
            if (references.size() == MAX_REFERENCES || name.length() > 512) {
                partial = true;
                break;
            }
            references.add(name.toString());
            i = next - 1;
        }
        return new Result(
                new ArrayList<>(references), partial ? "PARTIAL" : references.isEmpty() ? "UNAVAILABLE" : "COMPLETE");
    }

    private static boolean identifier(Token token) {
        return token.identifier && (token.quoted || !RESERVED.contains(token.keyword()));
    }

    private record Token(String text, boolean identifier, boolean quoted) {
        String keyword() {
            return quoted ? "" : text.toUpperCase(Locale.ROOT);
        }
    }

    private record Scan(List<Token> tokens, boolean partial) {}

    private static Scan tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        boolean partial = false;
        while (i < sql.length() && tokens.size() < MAX_TOKENS) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return new Scan(tokens, true);
                // Nested comments are not supported; do not read their text as identifiers.
                if (sql.substring(i + 2, end).contains("/*")) return new Scan(List.of(), true);
                i = end + 2;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                int start = i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char current = sql.charAt(i++);
                    if (current == '\\' && ch == '\'') {
                        if (i < sql.length()) i++;
                    } else if (current == close) {
                        if (i < sql.length() && sql.charAt(i) == close) {
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) return new Scan(tokens, true);
                tokens.add(new Token(ch == '\'' ? "?" : sql.substring(start, i), ch != '\'', ch != '\''));
            } else if (Character.isLetter(ch) || ch == '_') {
                int start = i++;
                while (i < sql.length()) {
                    char next = sql.charAt(i);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') break;
                    i++;
                }
                tokens.add(new Token(sql.substring(start, i), true, false));
            } else if (ch == '$') {
                // Dollar-quoted strings are dialect-specific: refuse, rather than scan their content.
                return new Scan(List.of(), true);
            } else {
                tokens.add(new Token(String.valueOf(ch), false, false));
                partial |= ch == '…';
                i++;
            }
        }
        return new Scan(tokens, partial || i < sql.length());
    }
}
