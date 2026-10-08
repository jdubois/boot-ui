package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * How to attach the BootUI Java agent to this application ({@code docs/PLAN-v2.md} §5.13).
 *
 * @param jarPath the agent jar's path: the attached agent's own jar, else the local Maven repository's copy, which may
 *     not exist yet
 * @param jarFound whether {@code jarPath} exists
 * @param buildTool the build tool detected in the working directory: {@code MAVEN}, {@code GRADLE}, or {@code UNKNOWN}
 * @param snippets the setup snippets for this stack and build tool
 */
public record JavaAgentSetupDto(
        String jarPath, boolean jarFound, String buildTool, List<JavaAgentSnippetDto> snippets) {

    public JavaAgentSetupDto {
        snippets = DtoCollections.immutableCopy(snippets);
    }
}
