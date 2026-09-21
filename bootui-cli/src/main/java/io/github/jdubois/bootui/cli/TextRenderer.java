package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Renders a tool payload for a human.
 *
 * <p>Best-effort by necessity: the CLI treats payloads as opaque so it keeps working across BootUI versions,
 * which means it cannot know that {@code findings} is a table and {@code jvm} is a header. It infers instead —
 * an array of like-shaped objects becomes a table, everything else a key/value tree — and {@code --json}
 * remains the exact, contract-stable output for anything that needs to be parsed.
 */
final class TextRenderer {

    /** Wide enough for a stack frame or a bean name, narrow enough to stay in an 80-column terminal. */
    private static final int MAX_CELL_WIDTH = 60;

    private final boolean color;

    TextRenderer(boolean color) {
        this.color = color;
    }

    String render(JsonValue value) {
        StringBuilder out = new StringBuilder();
        if (value.isObject()) {
            renderObject(value, out, 0);
        } else if (value.isArray()) {
            renderArray(value, out, 0);
        } else {
            out.append(value.asDisplayText());
        }
        String text = out.toString();
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private void renderObject(JsonValue object, StringBuilder out, int depth) {
        for (String rawName : object.names()) {
            String name = safe(rawName);
            JsonValue member = object.get(rawName);
            String indent = "  ".repeat(depth);
            if (member.isObject() && member.size() > 0) {
                out.append(indent).append(bold(name)).append('\n');
                renderObject(member, out, depth + 1);
                continue;
            }
            if (member.isArray() && member.size() > 0) {
                out.append(indent)
                        .append(bold(name))
                        .append(dim(" (" + member.size() + ")"))
                        .append('\n');
                renderArray(member, out, depth + 1);
                continue;
            }
            out.append(indent)
                    .append(label(name))
                    .append(": ")
                    .append(scalar(member))
                    .append('\n');
        }
    }

    private void renderArray(JsonValue array, StringBuilder out, int depth) {
        List<String> columns = tableColumns(array);
        if (!columns.isEmpty()) {
            renderTable(array, columns, out, depth);
            return;
        }
        String indent = "  ".repeat(depth);
        for (JsonValue element : array.values()) {
            if (element.isObject()) {
                out.append(indent).append(dim("-")).append('\n');
                renderObject(element, out, depth + 1);
            } else if (element.isArray()) {
                renderArray(element, out, depth + 1);
            } else {
                out.append(indent).append(dim("- ")).append(scalar(element)).append('\n');
            }
        }
    }

    /**
     * The columns to tabulate, or empty when this array is not table-shaped.
     *
     * <p>Requires every element to be an object of scalars. One nested object in one element and the table
     * would either lie or wrap unreadably, so the tree rendering is used instead.
     */
    private List<String> tableColumns(JsonValue array) {
        if (array.size() == 0) {
            return List.of();
        }
        Set<String> columns = new LinkedHashSet<>();
        for (JsonValue element : array.values()) {
            if (!element.isObject() || element.size() == 0) {
                return List.of();
            }
            for (String name : element.names()) {
                JsonValue member = element.get(name);
                if (member.isObject() || member.isArray()) {
                    return List.of();
                }
                columns.add(name);
            }
        }
        return columns.size() > 8 ? List.of() : List.copyOf(columns);
    }

    private void renderTable(JsonValue array, List<String> columns, StringBuilder out, int depth) {
        String indent = "  ".repeat(depth);
        List<List<String>> rows = new ArrayList<>();
        for (JsonValue element : array.values()) {
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) {
                row.add(truncate(element.get(column).asDisplayText()));
            }
            rows.add(row);
        }
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
            for (List<String> row : rows) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        List<String> headerCells = new ArrayList<>(columns.size());
        for (String column : columns) {
            headerCells.add(safe(column));
        }
        out.append(indent)
                .append(formatRow(headerCells, widths, false, this::bold))
                .append('\n');

        List<String> separatorCells = new ArrayList<>(widths.length);
        for (int width : widths) {
            separatorCells.add("-".repeat(width));
        }
        out.append(indent)
                .append(formatRow(separatorCells, widths, true, this::dim))
                .append('\n');

        for (List<String> row : rows) {
            out.append(indent)
                    .append(formatRow(row, widths, false, UnaryOperator.identity()))
                    .append('\n');
        }
    }

    /**
     * Joins one table row: each cell is padded to its column width (the last column is left
     * unpadded unless {@code padLast}, so a row never carries trailing whitespace) and passed
     * through {@code style} for bold/dim highlighting, with two spaces between columns.
     */
    private static String formatRow(List<String> cells, int[] widths, boolean padLast, UnaryOperator<String> style) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            int width = (i == cells.size() - 1 && !padLast) ? 0 : widths[i];
            row.append(style.apply(pad(cells.get(i), width)));
            if (i < cells.size() - 1) {
                row.append("  ");
            }
        }
        return row.toString();
    }

    private String scalar(JsonValue value) {
        if (value.isNull() || value.isMissing()) {
            return dim("-");
        }
        if (value.isObject() || value.isArray()) {
            // An empty object or array; anything non-empty was handled as a nested structure.
            return dim(value.toJson());
        }
        return truncate(value.asDisplayText());
    }

    private static String truncate(String text) {
        String single = safe(text);
        return single.length() <= MAX_CELL_WIDTH ? single : single.substring(0, MAX_CELL_WIDTH - 1) + "\u2026";
    }

    /**
     * Neutralises control characters before anything reaches the terminal.
     *
     * <p>Tool payloads carry application-controlled text — log lines, exception messages, header values, SQL,
     * bean names. Left alone, an ESC sequence in any of those could retitle the window or repaint the screen,
     * and a backspace could erase what was already printed so the value shown is not the value received.
     * {@code --json} still emits the server's bytes verbatim, which is the machine-readable contract.
     */
    private static String safe(String text) {
        StringBuilder clean = null;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            boolean control = current < 0x20 || current == 0x7F || (current >= 0x80 && current <= 0x9F);
            if (control && clean == null) {
                clean = new StringBuilder(text.length()).append(text, 0, index);
            }
            if (clean != null) {
                clean.append(control ? ' ' : current);
            }
        }
        return clean == null ? text : clean.toString();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private String bold(String text) {
        return color ? "\u001B[1m" + text + "\u001B[0m" : text;
    }

    private String dim(String text) {
        return color ? "\u001B[2m" + text + "\u001B[0m" : text;
    }

    private String label(String text) {
        return color ? "\u001B[36m" + text + "\u001B[0m" : text;
    }

    /** The payload as indented JSON, for {@code --json} when a human is still reading it. */
    static String json(JsonValue value) {
        return JsonWriter.pretty(value);
    }
}
