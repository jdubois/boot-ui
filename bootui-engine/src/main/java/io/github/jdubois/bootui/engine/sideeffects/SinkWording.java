package io.github.jdubois.bootui.engine.sideeffects;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The facts a security-sinks row states ({@code docs/PLAN-v2.md} §5.17 {@code request-input-in-sink}, M5-6b): worded as
 * what was seen, never as a weakness, with what to check. A row not confirmed by a second request says it was seen in
 * one request so far.
 */
final class SinkWording {

    /** A row no second request confirmed yet: shown because its value stands alone (§5.17's minimum is one request). */
    static final String SEEN_ONCE = " Seen in one request so far.";

    /** The Other row's sentence: matches past the row caps, of sinks and parameters it no longer tells apart. */
    static final String OTHER = "Request input reached more sinks than this table keeps apart; their parameters and"
            + " sinks are counted here. Clear the recording to see them again.";

    private static final Pattern ARGUMENT = Pattern.compile("^(.*), argument (\\d+)$");

    private SinkWording() {}

    /** A command's file name in backquotes, or the executable when it held request input and is not named. */
    private static String command(String name) {
        return name.startsWith("(") ? "the executable" : "`" + name + "`";
    }

    /** The sentence of a row of {@code kind}, its value named {@code parameter}, at {@code location} in {@code target}. */
    static String detail(String kind, String location, String parameter, String target, boolean confirmed) {
        String name = "`" + (parameter == null ? "a parameter" : parameter) + "`";
        String fact;
        if (SideEffectsCatalog.SQL_TEXT.equals(kind)) {
            if (SideEffectsCatalog.OUTSIDE_LITERAL.equals(location)) {
                fact = "Request input reached this SQL text unchanged: the value of " + name + " appeared outside a"
                        + " literal, where a column, a keyword, or an operator stands. Check that it is chosen from a"
                        + " fixed list.";
            } else if (SideEffectsCatalog.INSIDE_LITERAL.equals(location)) {
                fact = "Request input reached this SQL text unchanged: the value of " + name + " appeared inside a"
                        + " literal. Check that it is bound as a parameter or escaped.";
            } else {
                fact = "Request input reached this SQL text unchanged: the value of " + name + " appeared in it. Check"
                        + " that it is bound as a parameter, escaped, or chosen from a fixed list.";
            }
        } else if (SideEffectsCatalog.COMMAND.equals(kind)) {
            Matcher argument = target == null ? null : ARGUMENT.matcher(target);
            fact = argument != null && argument.matches()
                    ? "Request input reached this command unchanged: the value of " + name + " appeared in argument "
                            + argument.group(2) + " of " + command(argument.group(1)) + ". Check that it is validated."
                    : "Request input reached this command unchanged: the value of " + name + " appeared in it. Check"
                            + " that it is validated.";
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
