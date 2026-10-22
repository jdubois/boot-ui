package io.github.jdubois.bootui.engine.databaseadvisor;

/** Wording the driver rules share. */
final class DriverRuleText {

    private static final int MAX_SHOWN_LENGTH = 16;

    private DriverRuleText() {}

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
