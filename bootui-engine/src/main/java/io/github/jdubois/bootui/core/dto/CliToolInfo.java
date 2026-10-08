package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Describes one tool the command-line endpoint exposes, as returned by {@code GET /bootui/api/cli}.
 *
 * <p>This is what makes the {@code bootui} CLI self-describing: it reports what the <em>running</em>
 * instance advertises and how each panel is currently gated, so {@code --help} and shell completion stay
 * correct even when the CLI was built against a different BootUI version.
 *
 * @param name machine name of the tool (e.g. {@code architecture_scan})
 * @param command the {@code bootui} command path this tool is reached by (e.g. {@code architecture scan}),
 *     without the executable prefix, so a caller learns what to type from the running instance rather than
 *     from the version of the CLI it happens to have
 * @param description human-readable description
 * @param panel the {@code BootUiPanels} id backing this tool
 * @param action {@code true} when the tool changes state and is refused on a read-only panel
 * @param schema the argument-schema name ({@code NONE}, {@code LIMIT}, {@code QUERY_LIMIT}, or {@code ID})
 * @param arguments the accepted argument names, which the CLI projects onto flags and positionals
 * @param panelEnabled whether the backing panel is currently enabled
 * @param panelReadOnly whether the backing panel is currently read-only
 */
public record CliToolInfo(
        String name,
        String command,
        String description,
        String panel,
        boolean action,
        String schema,
        List<String> arguments,
        boolean panelEnabled,
        boolean panelReadOnly) {

    public CliToolInfo {
        arguments = DtoCollections.immutableCopy(arguments);
    }
}
