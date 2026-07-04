package io.github.jdubois.bootui.spi;

/**
 * One {@code quarkus.http.auth.permission.<name>} block: the paths it matches, the policy applied
 * ({@code permit}, {@code authenticated}, {@code deny}, or a named roles policy), and the HTTP methods it
 * is scoped to. Neutral carrier with no framework dependency; the Quarkus adapter populates it from
 * MicroProfile config.
 *
 * @param name the permission block name (the {@code <name>} segment of the config key)
 * @param paths the comma-separated paths the policy matches
 * @param policy the policy applied ({@code permit}, {@code authenticated}, {@code deny}, or a roles policy name)
 * @param methods the comma-separated HTTP methods the policy is scoped to via
 *     {@code quarkus.http.auth.permission.<name>.methods}, or {@code null}/blank when unset — meaning the
 *     policy applies to every method, not just some
 */
public record QuarkusSecurityPermission(String name, String paths, String policy, String methods) {}
