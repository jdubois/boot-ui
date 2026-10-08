package io.github.jdubois.bootui.core.dto;

import java.util.Map;

/**
 * One call an agent can make next, named both ways ({@code docs/PLAN-v2.md} M4-21): the {@code bootui} command line to
 * run, and the MCP tool with its arguments. Only tools this application advertises are named.
 *
 * @param command the {@code bootui} command line, quoted for a POSIX shell
 * @param tool the MCP tool it calls
 * @param arguments the MCP arguments, in the order the command line passes them
 * @param why what it answers, in one clause
 */
public record RuntimeNextStepDto(String command, String tool, Map<String, Object> arguments, String why) {

    public RuntimeNextStepDto {
        arguments = DtoCollections.immutableCopy(arguments);
    }
}
