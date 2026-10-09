package io.github.jdubois.bootui.engine.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A reusable MCP prompt advertised by the BootUI server.
 *
 * @param arguments the optional arguments {@code prompts/list} declares, in order; every one is optional, so a client
 *     that sends none gets {@link #text()} unchanged
 */
public record McpPrompt(String name, String description, String text, List<Argument> arguments) {

    /** The most characters of one argument value a rendered prompt keeps. */
    public static final int MAX_ARGUMENT_LENGTH = 500;

    /**
     * One optional prompt argument.
     *
     * @param name the argument name a client sends in {@code prompts/get}
     * @param description what the argument means, advertised in {@code prompts/list}
     * @param label how the rendered prompt introduces the value, such as {@code The symptom the user reports}
     */
    public record Argument(String name, String description, String label) {

        public Argument {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(label, "label");
        }
    }

    public McpPrompt {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(text, "text");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** A prompt without arguments. */
    public McpPrompt(String name, String description, String text) {
        this(name, description, text, List.of());
    }

    /** The names of {@link #arguments()}. */
    public Set<String> argumentNames() {
        return arguments.stream().map(Argument::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The prompt text, followed by the context the client supplied: one line per declared argument with a non-blank
     * value, in declaration order, each value stripped and cut at {@link #MAX_ARGUMENT_LENGTH} characters. Without
     * such a value it is {@link #text()} exactly.
     *
     * @param values the client's argument values by name; names this prompt does not declare are ignored here, the
     *     dispatcher refuses them first
     */
    public String render(Map<String, String> values) {
        Map<String, String> supplied = new LinkedHashMap<>();
        for (Argument argument : arguments) {
            String value = values == null ? null : values.get(argument.name());
            if (value != null && !value.isBlank()) {
                String stripped = value.strip();
                supplied.put(argument.label(), bounded(stripped));
            }
        }
        if (supplied.isEmpty()) {
            return text;
        }
        StringBuilder rendered = new StringBuilder(text)
                .append("\n\nContext the user supplied; use it to focus the steps above, and verify it against BootUI"
                        + " evidence rather than assume it:");
        supplied.forEach((label, value) ->
                rendered.append("\n- ").append(label).append(": ").append(value));
        return rendered.toString();
    }

    /** {@code value} cut at {@link #MAX_ARGUMENT_LENGTH} characters, never between the two halves of a surrogate pair. */
    static String bounded(String value) {
        if (value.length() <= MAX_ARGUMENT_LENGTH) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(MAX_ARGUMENT_LENGTH - 1))
                ? MAX_ARGUMENT_LENGTH - 1
                : MAX_ARGUMENT_LENGTH;
        return value.substring(0, end) + "...";
    }
}
