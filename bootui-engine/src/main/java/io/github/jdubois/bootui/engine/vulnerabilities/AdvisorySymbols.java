package io.github.jdubois.bootui.engine.vulnerabilities;

import io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The classes and methods an OSV advisory names, normalized to Java binary names ({@code docs/PLAN-v2.md} §5.15,
 * M5-9a), so the BootUI agent's class-name evidence can tell whether one of them loaded.
 *
 * <p>Structured symbols come first: the matching {@code affected[]} entry's {@code ecosystem_specific} or
 * {@code database_specific} {@code imports[].symbols} (with their {@code path}), {@code affected_functions}, or
 * {@code symbols}. No Maven advisory observed on OSV carries them today, so when there are none, fully qualified class
 * names in the summary and details are used instead, marked as such: those may name a proof of concept's or another
 * artifact's classes, which is why they are only ever matched against the affected artifact's own classes. Without
 * either, the advisory names nothing, which is stated, never guessed.
 *
 * <p>A symbol is {@code com.example.Type}, {@code com.example.Outer$Inner}, or {@code com.example.Type#method}.
 */
public final class AdvisorySymbols {

    /** The most symbols kept per advisory. */
    public static final int MAX_SYMBOLS = 20;

    /** How much of an advisory's text is searched for class names. */
    static final int MAX_TEXT = 20_000;

    private static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.$/\\\\\\-])((?:[a-z_][a-z0-9_]*\\.){2,}"
            + "[A-Z][A-Za-z0-9_]*(?:[.$][A-Z][A-Za-z0-9_]*)*)(?:(?:#|::|\\.)([a-z_][A-Za-z0-9_]*)\\s*\\()?");

    private AdvisorySymbols() {}

    /**
     * The symbols an advisory names and where they came from.
     *
     * @param symbols the normalized symbols, sorted, at most {@value #MAX_SYMBOLS}
     * @param source {@code OSV}, {@code ADVISORY_TEXT}, or {@code NONE}
     */
    public record Symbols(List<String> symbols, String source) {

        public Symbols {
            symbols = List.copyOf(symbols);
        }
    }

    /**
     * The symbols of an advisory: its {@code structured} ones when any normalizes, else the class names in
     * {@code summary} and {@code details}.
     */
    public static Symbols of(List<String> structured, String summary, String details) {
        Set<String> symbols = new TreeSet<>();
        if (structured != null) {
            for (String raw : structured) {
                String symbol = normalize(raw);
                if (symbol != null) {
                    symbols.add(symbol);
                }
            }
        }
        if (!symbols.isEmpty()) {
            return new Symbols(bounded(symbols), "OSV");
        }
        text(summary, symbols);
        text(details, symbols);
        return symbols.isEmpty() ? new Symbols(List.of(), "NONE") : new Symbols(bounded(symbols), "ADVISORY_TEXT");
    }

    /** {@code vulnerability} with the symbols its advisory names: {@code structured} ones, else its text's. */
    public static DependencyVulnerabilityDto annotate(
            DependencyVulnerabilityDto vulnerability, List<String> structured) {
        Symbols symbols = of(structured, vulnerability.summary(), vulnerability.details());
        return vulnerability.withAdvisorySymbols(symbols.symbols(), symbols.source());
    }

    /**
     * A structured symbol from an OSV {@code imports[]} entry: {@code symbol} qualified by the entry's {@code path}
     * unless it already starts with it.
     */
    public static String qualified(String path, String symbol) {
        if (symbol == null || path == null || path.isBlank() || symbol.startsWith(path + ".")) {
            return symbol;
        }
        return path + "." + symbol;
    }

    /** The binary class name of a normalized symbol. */
    public static String className(String symbol) {
        int hash = symbol.indexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    /**
     * A structured symbol as {@code package.Type[$Inner][#method]}: {@code /} read as {@code .}, a parameter list
     * dropped, {@code #} or {@code ::} separating a method, otherwise the first upper-case segment starting the type,
     * further upper-case segments nested in it, and a lower-case segment after it the method. {@code null} when it names
     * no type in a package, or a type of {@code java.}, which no library can define.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace('/', '.');
        int dollar = value.lastIndexOf('$');
        if (dollar > 0 && isConstant(value.substring(dollar + 1))) {
            value = value.substring(0, dollar);
        }
        int parenthesis = value.indexOf('(');
        if (parenthesis >= 0) {
            value = value.substring(0, parenthesis);
        }
        String method = null;
        int separator = value.indexOf("::");
        if (separator < 0) {
            separator = value.indexOf('#');
            if (separator >= 0) {
                method = value.substring(separator + 1);
                value = value.substring(0, separator);
            }
        } else {
            method = value.substring(separator + 2);
            value = value.substring(0, separator);
        }
        String[] segments = value.split("\\.", -1);
        StringBuilder type = new StringBuilder();
        int index = 0;
        while (index < segments.length && isPackage(segments[index])) {
            type.append(segments[index]).append('.');
            index++;
        }
        if (index == 0 || index >= segments.length || !isType(segments[index])) {
            return null;
        }
        type.append(segments[index++]);
        while (index < segments.length && isType(segments[index]) && !isConstant(segments[index])) {
            type.append('$').append(segments[index++]);
        }
        if (index == segments.length - 1 && isConstant(segments[index])) {
            // A constant, such as Type.MAX_LENGTH: the class is what loads.
            index++;
        }
        if (index < segments.length) {
            if (method != null || index != segments.length - 1 || !isIdentifier(segments[index])) {
                return null;
            }
            method = segments[index];
        }
        String name = type.toString();
        if (name.startsWith("java.")) {
            return null;
        }
        return method == null || method.isBlank() || !isIdentifier(method.trim()) ? name : name + "#" + method.trim();
    }

    private static void text(String text, Set<String> symbols) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = QUALIFIED.matcher(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        while (matcher.find()) {
            String raw = matcher.group(2) == null ? matcher.group(1) : matcher.group(1) + "#" + matcher.group(2);
            String symbol = normalize(raw);
            if (symbol != null) {
                symbols.add(symbol);
            }
        }
    }

    private static List<String> bounded(Set<String> symbols) {
        List<String> list = new ArrayList<>(symbols);
        return list.size() > MAX_SYMBOLS ? list.subList(0, MAX_SYMBOLS) : list;
    }

    private static boolean isPackage(String segment) {
        return !segment.isEmpty() && Character.isLowerCase(segment.charAt(0)) && isIdentifier(segment);
    }

    /** An all-capitals member name, such as {@code MAX_LENGTH}, never a nested type's. */
    private static boolean isConstant(String segment) {
        return segment.length() > 1
                && segment.equals(segment.toUpperCase(java.util.Locale.ROOT))
                && isIdentifier(segment);
    }

    private static boolean isType(String segment) {
        if (segment.isEmpty() || !Character.isUpperCase(segment.charAt(0))) {
            return false;
        }
        for (String part : segment.split("\\$", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
