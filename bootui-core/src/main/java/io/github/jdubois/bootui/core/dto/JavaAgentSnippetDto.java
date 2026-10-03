package io.github.jdubois.bootui.core.dto;

/**
 * One copy-paste line that attaches the BootUI Java agent in a build tool, test runner, or IDE.
 *
 * @param id a stable snippet id, such as {@code maven-plugin}
 * @param label the tab label
 * @param language the snippet's language for display: {@code xml}, {@code kotlin}, {@code groovy}, {@code shell}, or
 *     {@code text}
 * @param text the snippet itself
 */
public record JavaAgentSnippetDto(String id, String label, String language, String text) {}
