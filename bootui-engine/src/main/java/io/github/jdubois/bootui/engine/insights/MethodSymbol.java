package io.github.jdubois.bootui.engine.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A method named in change impact ({@code docs/PLAN-v2.md} §5.7, M5-7a): {@code Class#name}, {@code Class.name(...)},
 * or a candidate's {@code METHOD fq.Class#name}, the class fully qualified or simple, with optional parameters that name
 * one overload: Java types, such as {@code (String, int)} or {@code (java.util.List<Long>)}, or a JVM descriptor, such
 * as {@code (Ljava/lang/String;)V}.
 *
 * @param type the class as asked: a binary name, its source form, or a simple name; {@code null} for a bare method
 *     name, which any class may declare
 * @param name the method's name
 * @param parameters the parameter list as asked, without its parentheses, or {@code null} for every overload
 * @param returns the JVM return descriptor asked for after the parameters, or {@code null}
 */
record MethodSymbol(String type, String name, String parameters, String returns) {

    static final String KIND = "METHOD";

    /**
     * The method {@code symbol} names, or {@code null} when it names none. With {@code dotted}, {@code Class.name}
     * without parentheses names a method too: callers try it only when no other symbol matched, since bean names,
     * tables, and hosts contain dots.
     */
    static MethodSymbol parse(String symbol, boolean dotted) {
        if (symbol == null) {
            return null;
        }
        String text = symbol.strip();
        if (text.startsWith(KIND + " ")) {
            text = text.substring(KIND.length() + 1).strip();
        }
        String parameters = null;
        String returns = null;
        int open = text.indexOf('(');
        String head = text;
        if (open >= 0) {
            int close = text.indexOf(')', open);
            if (close < 0 || text.indexOf('(', open + 1) >= 0 || text.indexOf(')', close + 1) >= 0) {
                return null;
            }
            parameters = text.substring(open + 1, close).strip();
            returns = text.substring(close + 1).strip();
            returns = returns.isEmpty() ? null : returns;
            head = text.substring(0, open).strip();
        }
        int hash = head.indexOf('#');
        String type;
        String name;
        if (hash >= 0) {
            if (head.indexOf('#', hash + 1) >= 0) {
                return null;
            }
            type = head.substring(0, hash);
            name = head.substring(hash + 1);
        } else if (open >= 0 || dotted) {
            int dot = head.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            type = head.substring(0, dot);
            name = head.substring(dot + 1);
        } else {
            return null;
        }
        if (!javaName(type, true) || !javaName(name, false)) {
            return null;
        }
        if (returns != null && !descriptorLike(parameters)) {
            return null;
        }
        return new MethodSymbol(type, name, parameters, returns);
    }

    /**
     * A bare method name, such as {@code applyDiscount}, which names that method of any class, or {@code null} when
     * {@code symbol} is no Java identifier. Callers try it only when no other symbol matched, since a bean is named the
     * same way.
     */
    static MethodSymbol anyClass(String symbol) {
        String text = symbol == null ? "" : symbol.strip();
        return !text.contains(".") && javaName(text, false) ? new MethodSymbol(null, text, null, null) : null;
    }

    /** Whether {@code key}, {@code class#name+descriptor}, is this method: its class, its name, and its parameters. */
    boolean matchesKey(String key) {
        int hash = key.indexOf('#');
        int open = key.indexOf('(', hash + 1);
        if (hash <= 0 || open < 0) {
            return false;
        }
        return name.equals(key.substring(hash + 1, open))
                && namesClass(key.substring(0, hash))
                && matchesDescriptor(key.substring(open));
    }

    /** Whether {@code className}, a binary name, is the class asked for. */
    boolean namesClass(String className) {
        if (type == null) {
            return true;
        }
        if (className.equals(type) || className.replace('$', '.').equals(type)) {
            return true;
        }
        int dot = className.lastIndexOf('.');
        String simple = className.substring(dot + 1);
        int dollar = simple.lastIndexOf('$');
        return simple.equals(type)
                || simple.replace('$', '.').equals(type)
                || (dollar >= 0 && simple.substring(dollar + 1).equals(type));
    }

    /** Whether the JVM {@code descriptor}, such as {@code (Ljava/lang/String;)V}, matches the parameters asked for. */
    boolean matchesDescriptor(String descriptor) {
        if (parameters == null) {
            return true;
        }
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return false;
        }
        if (descriptorLike(parameters)) {
            return descriptor.substring(1, close).equals(parameters)
                    && (returns == null || descriptor.substring(close + 1).equals(returns));
        }
        List<String> declared = parameterTypes(descriptor.substring(1, close));
        List<String> asked = javaParameters(parameters);
        if (declared == null || asked == null || declared.size() != asked.size()) {
            return false;
        }
        for (int i = 0; i < declared.size(); i++) {
            String full = declared.get(i);
            String wanted = asked.get(i);
            // A simple name, a nested one such as Map.Entry, or the fully qualified one.
            if (!full.equals(wanted) && !full.endsWith("." + wanted)) {
                return false;
            }
        }
        return true;
    }

    /** Whether one overload is named: parameters were given. */
    boolean overloadNamed() {
        return parameters != null;
    }

    /** The label a candidate of this method takes in class {@code className}, which names exactly that class. */
    String label(String className) {
        return KIND + " " + className + "#" + name + (parameters == null ? "" : "(" + parameters + ")")
                + (returns == null ? "" : returns);
    }

    /** The Java types of a JVM parameter descriptor, nested classes with dots, arrays with {@code []}. */
    static List<String> parameterTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        int i = 0;
        while (i < descriptor.length()) {
            int dimensions = 0;
            while (i < descriptor.length() && descriptor.charAt(i) == '[') {
                dimensions++;
                i++;
            }
            if (i >= descriptor.length()) {
                return null;
            }
            char code = descriptor.charAt(i);
            String type;
            if (code == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    return null;
                }
                type = descriptor.substring(i + 1, end).replace('/', '.').replace('$', '.');
                i = end + 1;
            } else {
                type = switch (code) {
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'D' -> "double";
                    case 'F' -> "float";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'S' -> "short";
                    case 'Z' -> "boolean";
                    default -> null;
                };
                if (type == null) {
                    return null;
                }
                i++;
            }
            types.add(type + "[]".repeat(dimensions));
        }
        return types;
    }

    /** The Java parameter types asked for, generics left out and varargs as arrays; {@code null} when malformed. */
    static List<String> javaParameters(String text) {
        StringBuilder plain = new StringBuilder();
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth < 0) {
                    return null;
                }
            } else if (depth == 0 && !Character.isWhitespace(c)) {
                plain.append(c);
            }
        }
        if (depth != 0) {
            return null;
        }
        List<String> types = new ArrayList<>();
        if (plain.isEmpty()) {
            return types;
        }
        for (String part : plain.toString().split(",", -1)) {
            String type = part.replace("...", "[]").replace('$', '.');
            String bare = type.replace("[]", "");
            if (!javaName(bare, true)) {
                return null;
            }
            types.add(type);
        }
        return types;
    }

    /** Whether the parameters are a JVM descriptor's, such as {@code Ljava/lang/String;I}, rather than Java types. */
    private static boolean descriptorLike(String parameters) {
        if (parameters == null) {
            return false;
        }
        if (parameters.isEmpty()) {
            return true;
        }
        return parameters.contains(";")
                || parameters.contains("/")
                || (parameters.toUpperCase(Locale.ROOT).equals(parameters)
                        && parameters.chars().allMatch(c -> "BCDFIJSZ[".indexOf(c) >= 0));
    }

    private static boolean javaName(String text, boolean qualified) {
        if (text.isEmpty()) {
            return false;
        }
        for (String part : text.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
            if (!qualified && text.contains(".")) {
                return false;
            }
        }
        return true;
    }
}
