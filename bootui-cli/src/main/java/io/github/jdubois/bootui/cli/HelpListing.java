package io.github.jdubois.bootui.cli;

import java.util.ArrayList;
import java.util.List;

final class HelpListing {

    static final int SUMMARY_LIMIT = 110;

    private static final String INDENT = "  ";
    private static final String DETAIL = "      ";

    private HelpListing() {}

    static String render(List<ToolManifest.Tool> tools, boolean root, int width) {
        int wrapAt = Math.max(60, width);
        StringBuilder out = new StringBuilder();
        out.append(System.lineSeparator()).append("Commands:").append(System.lineSeparator());
        wrap(
                out,
                INDENT,
                "<angle brackets> are required, [square brackets] optional; a command shown without arguments runs as"
                        + " shown. " + ToolManifest.Tool.ACTION_TAG + " marks a command that changes the application's"
                        + " state: ask the user before running it. 'bootui <command> --help' gives the whole description.",
                wrapAt);
        out.append(System.lineSeparator());
        for (ToolManifest.Tool tool : tools) {
            String synopsis = tool.synopsis();
            out.append(INDENT).append(synopsis).append(System.lineSeparator());
            wrap(out, DETAIL, tool.tag() + shorten(tool.summary()) + CommandTree.stackNote(tool), wrapAt);
            if (!tool.idHelp().isEmpty()) {
                wrap(out, DETAIL, "<id>: " + tool.idHelp(), wrapAt);
            }
            if (!tool.queryHelp().isEmpty()) {
                wrap(out, DETAIL, "--query: " + tool.queryHelp() + ".", wrapAt);
            }
            if (!tool.example().equals(synopsis)) {
                out.append(DETAIL).append("Example: ").append(tool.example()).append(System.lineSeparator());
            }
        }
        if (root) {
            out.append(INDENT)
                    .append("bootui docs [<topic>] | docs --search <text> [--json]")
                    .append(System.lineSeparator());
            wrap(out, DETAIL, "Read and search this CLI version's bundled documentation offline.", wrapAt);
            out.append(INDENT).append("bootui update [--check] [--json]").append(System.lineSeparator());
            wrap(
                    out,
                    DETAIL,
                    "Check for a newer CLI or update a recognized installation. Ask before replacing an executable. --check exits 3 when an update is available.",
                    wrapAt);
            out.append(INDENT).append("bootui instances [--json]").append(System.lineSeparator());
            wrap(out, DETAIL, "List running local BootUI instances discovered in ~/.bootui/instances.", wrapAt);
            out.append(INDENT)
                    .append("bootui init [--vscode | --cursor | --claude] [--print | --dry-run]")
                    .append(System.lineSeparator());
            wrap(out, DETAIL, "Preview or idempotently write IDE/agent MCP configuration without credentials.", wrapAt);
            out.append(INDENT).append("bootui tools").append(System.lineSeparator());
            wrap(
                    out,
                    DETAIL,
                    "List the tools this application exposes, and whether its panels allow each one.",
                    wrapAt);
            out.append(INDENT).append("bootui mcp status|enable|disable").append(System.lineSeparator());
            wrap(out, DETAIL, "Show, turn on, or turn off the application's MCP server.", wrapAt);
        }
        return out.toString();
    }

    static String literal(String text) {
        return text.replace("%", "%%");
    }

    static String shorten(String summary) {
        if (summary.length() <= SUMMARY_LIMIT) {
            return summary;
        }
        int cut = summary.lastIndexOf(' ', SUMMARY_LIMIT - 3);
        String head = summary.substring(0, cut < 0 ? SUMMARY_LIMIT - 3 : cut);
        while (!head.isEmpty() && ",;:".indexOf(head.charAt(head.length() - 1)) >= 0) {
            head = head.substring(0, head.length() - 1);
        }
        return head + "...";
    }

    private static void wrap(StringBuilder out, String indent, String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && indent.length() + line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        for (String wrapped : lines) {
            out.append(indent).append(wrapped).append(System.lineSeparator());
        }
    }
}
