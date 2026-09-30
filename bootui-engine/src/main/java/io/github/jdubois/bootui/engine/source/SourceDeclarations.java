package io.github.jdubois.bootui.engine.source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative ownership reader, not a Java/Kotlin parser. Only package and top-level type names
 * are needed; comments, literals and nested declarations must never establish source ownership.
 * Shared by the Architecture advisor's generated-source provenance and the {@link SourceLocator}.
 */
public record SourceDeclarations(String packageName, Set<String> typeNames) {
    public SourceDeclarations {
        typeNames = Set.copyOf(typeNames);
    }

    public static SourceDeclarations read(String source) throws IOException {
        return read(source, false);
    }

    public static SourceDeclarations read(String source, boolean kotlin) throws IOException {
        if (!kotlin) source = translateJavaUnicode(source);
        List<String> tokens = tokens(source, kotlin);
        String packageName = "";
        boolean packaged = false;
        Set<String> types = new HashSet<>();
        int braces = 0;
        int parentheses = 0;
        int brackets = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (braces == 0 && parentheses == 0 && brackets == 0) {
                if (token.equals("package")) {
                    if (packaged || !types.isEmpty()) throw unsupported();
                    packaged = true;
                    StringBuilder name = new StringBuilder();
                    boolean identifier = true;
                    while (++i < tokens.size()) {
                        String part = tokens.get(i);
                        if (part.equals("\n") || part.equals(";")) break;
                        if (identifier ? !identifier(part) : !part.equals(".")) throw unsupported();
                        name.append(unquote(part));
                        identifier = !identifier;
                    }
                    if (identifier) throw unsupported();
                    packageName = name.toString();
                } else if (Set.of("class", "interface", "enum", "record", "object")
                        .contains(token)) {
                    int next = adjacentToken(tokens, i, 1);
                    int previous = adjacentToken(tokens, i, -1);
                    if (next < tokens.size()
                            && identifier(tokens.get(next))
                            && !Set.of("class", "interface", "object").contains(tokens.get(next))
                            && (previous < 0 || !Set.of(".", ":").contains(tokens.get(previous)))) {
                        types.add(unquote(tokens.get(next)));
                    }
                }
            }
            switch (token) {
                case "{" -> braces++;
                case "}" -> braces--;
                case "(" -> parentheses++;
                case ")" -> parentheses--;
                case "[" -> brackets++;
                case "]" -> brackets--;
                default -> {}
            }
            if (braces < 0 || parentheses < 0 || brackets < 0) throw unsupported();
        }
        if (braces != 0 || parentheses != 0 || brackets != 0) throw unsupported();
        return new SourceDeclarations(packageName, types);
    }

    private static int adjacentToken(List<String> tokens, int position, int direction) {
        do {
            position += direction;
        } while (position >= 0
                && position < tokens.size()
                && tokens.get(position).equals("\n"));
        return position;
    }

    /** JLS 3.3: eligibility depends on translated backslash parity and the preceding escape. */
    static String translateJavaUnicode(String source) throws IOException {
        StringBuilder result = new StringBuilder(source.length());
        int backslashes = 0;
        boolean previousEscape = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            boolean escape = value == '\\'
                    && (previousEscape || backslashes % 2 == 0)
                    && i + 1 < source.length()
                    && source.charAt(i + 1) == 'u';
            if (escape) {
                int digits = i + 1;
                while (digits < source.length() && source.charAt(digits) == 'u') digits++;
                if (digits + 4 > source.length()) throw unsupported();
                int decoded = 0;
                for (int j = digits; j < digits + 4; j++) {
                    char digit = source.charAt(j);
                    int hex = digit >= '0' && digit <= '9'
                            ? digit - '0'
                            : digit >= 'a' && digit <= 'f'
                                    ? digit - 'a' + 10
                                    : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                    if (hex < 0) throw unsupported();
                    decoded = decoded * 16 + hex;
                }
                value = (char) decoded;
                i = digits + 3;
            }
            result.append(value);
            backslashes = value == '\\' ? backslashes + 1 : 0;
            previousEscape = escape;
        }
        return result.toString();
    }

    private static List<String> tokens(String source, boolean kotlin) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < source.length(); ) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') {
                tokens.add("\n");
                i++;
            } else if (Character.isWhitespace(c) || c == '\ufeff') {
                i++;
            } else if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
            } else if (source.startsWith("/*", i)) {
                int end = commentEnd(source, i, kotlin);
                if (source.substring(i, end).contains("\n")) tokens.add("\n");
                i = end;
            } else if (c == '"' || c == '\'') {
                i = literalEnd(source, i, kotlin, 0);
                tokens.add("<literal>");
            } else if (c == '`') {
                int end = source.indexOf('`', i + 1);
                if (end < 0 || source.substring(i + 1, end).chars().anyMatch(Character::isISOControl)) {
                    throw unsupported();
                }
                tokens.add(source.substring(i, end + 1));
                i = end + 1;
            } else if (Character.isJavaIdentifierStart(c)) {
                StringBuilder name = new StringBuilder();
                do {
                    char part = source.charAt(i++);
                    if (kotlin || !Character.isIdentifierIgnorable(part)) name.append(part);
                } while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i)));
                tokens.add(name.toString());
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }

    private static int commentEnd(String source, int start, boolean kotlin) throws IOException {
        int depth = 1;
        int i = start + 2;
        while (i < source.length()) {
            if (kotlin && source.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (source.startsWith("*/", i)) {
                i += 2;
                if (--depth == 0) return i;
            } else {
                i++;
            }
        }
        throw unsupported();
    }

    private static int literalEnd(String source, int start, boolean kotlin, int nesting) throws IOException {
        if (nesting > 32) throw unsupported();
        char quote = source.charAt(start);
        boolean triple = source.startsWith("\"\"\"", start);
        String end = triple ? "\"\"\"" : String.valueOf(quote);
        int i = start + end.length();
        while (i < source.length()) {
            if (source.charAt(i) == '\\' && (!triple || !kotlin)) {
                i += 2;
            } else if (kotlin && quote == '"' && source.startsWith("${", i)) {
                i = templateEnd(source, i + 2, nesting + 1);
            } else if (source.startsWith(end, i)) {
                return i + end.length();
            } else {
                i++;
            }
        }
        throw unsupported();
    }

    private static int templateEnd(String source, int start, int nesting) throws IOException {
        int braces = 1;
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = literalEnd(source, i, true, nesting);
            } else if (source.startsWith("/*", i)) {
                i = commentEnd(source, i, true);
            } else if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
            } else if (c == '`') {
                int end = source.indexOf('`', i + 1);
                if (end < 0) throw unsupported();
                i = end + 1;
            } else {
                if (c == '{') braces++;
                if (c == '}' && --braces == 0) return i + 1;
                i++;
            }
        }
        throw unsupported();
    }

    private static boolean identifier(String token) {
        return token.startsWith("`") && token.length() > 2
                || !token.isEmpty()
                        && Character.isJavaIdentifierStart(token.charAt(0))
                        && token.chars().allMatch(Character::isJavaIdentifierPart);
    }

    private static String unquote(String token) {
        return token.startsWith("`") ? token.substring(1, token.length() - 1) : token;
    }

    private static IOException unsupported() {
        return new IOException("Source ownership could not be established.");
    }
}
