package io.github.jdubois.bootui.core.dto;

/**
 * Result of clearing or invalidating a Tomcat HTTP session.
 */
public record HttpSessionActionResult(String status, String message, String sessionKey, int affectedAttributes) {}
