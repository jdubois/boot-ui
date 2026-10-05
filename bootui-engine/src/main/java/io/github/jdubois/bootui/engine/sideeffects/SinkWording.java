package io.github.jdubois.bootui.engine.sideeffects;

/**
 * The facts a security-sinks row states ({@code docs/PLAN-v2.md} §5.17 {@code request-input-in-sink}, M5-6b): worded as
 * what was seen, never as a vulnerability or an injection, with what to check. A row not confirmed by a second request
 * says it was seen in one request so far.
 */
final class SinkWording {

    /** A row no second request confirmed yet: shown because its value stands alone (§5.17's minimum is one request). */
    static final String SEEN_ONCE = " Seen in one request so far.";

    private SinkWording() {}

    /** The sentence of a row of {@code kind}, its value named {@code parameter}, at {@code location} in {@code target}. */
    static String detail(String kind, String location, String parameter, String target, boolean confirmed) {
        String name = "`" + (parameter == null ? "a parameter" : parameter) + "`";
        String fact;
        if (SideEffectsCatalog.SQL_TEXT.equals(kind)) {
            fact = SideEffectsCatalog.OUTSIDE_LITERAL.equals(location)
                    ? "Request input reached this SQL text unchanged: the value of " + name + " appeared outside a"
                            + " literal, where a column, a keyword, or an operator stands. Check that it is chosen from"
                            + " a fixed list."
                    : "Request input reached this SQL text unchanged: the value of " + name + " appeared inside a"
                            + " literal. Check that it is bound as a parameter or escaped.";
        } else if (SideEffectsCatalog.COMMAND.equals(kind)) {
            fact = "Request input reached this command unchanged: the value of " + name + " appeared in "
                    + (target == null ? "an argument" : target) + ". Check that it is validated.";
        } else if (SideEffectsCatalog.FILE_PATH.equals(kind)) {
            fact = "Request input reached this file path unchanged: the value of " + name + " appeared in it. Check"
                    + " that it is validated and cannot leave its directory.";
        } else if (SideEffectsCatalog.OUTBOUND_URL.equals(kind)) {
            fact = "Request input reached this outbound URL unchanged: the value of " + name + " appeared in it. Check"
                    + " that it is validated or encoded, and cannot change the host.";
        } else {
            fact = "Request input reached this sink unchanged: the value of " + name + " appeared in it. Check that it"
                    + " is validated.";
        }
        return confirmed ? fact : fact + SEEN_ONCE;
    }
}
