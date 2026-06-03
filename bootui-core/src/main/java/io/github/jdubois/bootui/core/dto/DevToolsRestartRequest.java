package io.github.jdubois.bootui.core.dto;

/**
 * Request to restart the application through Spring Boot DevTools.
 */
public record DevToolsRestartRequest(Boolean confirm) {}
