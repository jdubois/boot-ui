package io.github.jdubois.bootui.spi;

/**
 * One {@code quarkus.http.auth.permission.<name>} block: the paths it matches and the policy applied
 * ({@code permit}, {@code authenticated}, {@code deny}, or a named roles policy). Neutral carrier with
 * no framework dependency; the Quarkus adapter populates it from MicroProfile config.
 */
public record QuarkusSecurityPermission(String name, String paths, String policy) {}
