package io.github.jdubois.bootui.engine.databaseadvisor;

/** Wording the driver rules share. */
final class DriverRuleText {

    private static final int MAX_SHOWN_LENGTH = 16;

    private DriverRuleText() {}

    /**
     * How a finding says where a setting stands: not set anywhere the advisor reads (so the driver default applies), or
     * the configured value, quoted through {@link #shown(String)}, and its source.
     */
    static String state(DriverSetting configured, String driverDefault) {
        return configured == null
                ? "is not set in the JDBC URL or the pool's driver properties (driver default `" + driverDefault + "`)"
                : "is " + shown(configured.value()) + ", configured in the " + configured.label();
    }

    /**
     * A configured value as a finding quotes it. Only a short plain token is echoed: a value is untrusted text from a
     * URL or a pool, and a malformed query can leave arbitrary fragments of one in it.
     */
    static String shown(String value) {
        if (value != null && value.length() <= MAX_SHOWN_LENGTH && value.matches("[A-Za-z0-9_.-]+")) {
            return "`" + value + "`";
        }
        return value != null && value.isEmpty() ? "empty" : "a value that is not `true`";
    }
}
