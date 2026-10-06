package io.github.jdubois.bootui.engine.sideeffects;

import java.util.List;
import java.util.Set;

/**
 * The facts a security-sinks JDK check row states ({@code docs/PLAN-v2.md} §5.16, M5-6b2): what the application asked
 * the JDK for, or installed, and where, worded as what was seen and never as a weakness, with what to check.
 */
final class CheckWording {

    /** The Other row's sentence: checks past the row caps, of call sites and targets it no longer tells apart. */
    static final String OTHER =
            "More JDK checks were seen than this table keeps apart; they are counted here. Clear the"
                    + " recording to see them again.";

    private static final Set<String> KINDS = Set.of(
            SideEffectsCatalog.DESERIALIZATION,
            SideEffectsCatalog.WEAK_DIGEST,
            SideEffectsCatalog.WEAK_CIPHER,
            SideEffectsCatalog.TRUST_MANAGER,
            SideEffectsCatalog.HOSTNAME_VERIFIER,
            SideEffectsCatalog.SOCKET_FACTORY);

    private CheckWording() {}

    /** Whether a security-sinks row's kind is a JDK check's. */
    static boolean isCheck(String kind) {
        return kind != null && KINDS.contains(kind);
    }

    /**
     * The sentence of a check row of {@code kind} about {@code target} (an algorithm, a class, or a deserialization's
     * top class), asked for at {@code callSite}: by the application, or, when {@code origin} is a library's, by the
     * library frame {@code location} for the application frame {@code callSite}.
     */
    static String detail(
            String kind,
            String target,
            String callSite,
            String origin,
            String location,
            List<String> classes,
            boolean moreClasses) {
        boolean library = SideEffectOrigins.LIBRARY.equals(origin);
        String libraryFrame = location == null ? "(frame not kept)" : "`" + location + "`";
        String at = callSite == null ? "" : " at `" + callSite + "`";
        if (SideEffectsCatalog.DESERIALIZATION.equals(kind)) {
            // A library's read with no application frame above it names its frame once, not again as its call site.
            String readAt = library && (callSite == null || callSite.equals(location)) ? "" : at;
            return "Deserialization without an ObjectInputFilter"
                    + (library ? " by library code " + libraryFrame : "")
                    + readAt + " (classes read: " + classes(target, classes, moreClasses)
                    + "). Check that the stream comes"
                    + " only from a trusted source, or give it a filter (ObjectInputStream.setObjectInputFilter or"
                    + " jdk.serialFilter).";
        }
        if (SideEffectsCatalog.WEAK_DIGEST.equals(kind) || SideEffectsCatalog.WEAK_CIPHER.equals(kind)) {
            String who = library
                    ? "library code " + libraryFrame
                            + (callSite == null || callSite.equals(location)
                                    ? " (no application frame on the stack)"
                                    : " for application frame `" + callSite + "`")
                    : "application code" + at;
            String check = SideEffectsCatalog.WEAK_DIGEST.equals(kind)
                    ? " MD5 and SHA-1 remain fine for checksums and ETags; check that this one protects no password,"
                            + " signature, or token."
                    : " Check that it protects nothing that matters; AES/GCM/NoPadding is the usual choice.";
            return "Weak algorithm " + target + " requested by " + who + "." + check;
        }
        if (SideEffectsCatalog.TRUST_MANAGER.equals(kind)) {
            String who = library
                    ? "Library code " + libraryFrame
                            + " initialized an SSLContext with the application's trust manager (" + target + ")"
                            + (callSite == null ? "" : " for application frame `" + callSite + "`")
                    : "Application code initialized an SSLContext with a trust manager of its own (" + target + ")"
                            + at;
            return who + ". Check that it validates certificate chains and never trusts every certificate.";
        }
        if (SideEffectsCatalog.HOSTNAME_VERIFIER.equals(kind)) {
            return "Application code set the default hostname verifier (" + target + ")" + at + ". Every"
                    + " HttpsURLConnection opened afterwards uses it: check that it verifies host names.";
        }
        if (SideEffectsCatalog.SOCKET_FACTORY.equals(kind)) {
            return "Application code set the default SSL socket factory (" + target + ")" + at + ". Every"
                    + " HttpsURLConnection opened afterwards uses it: check that its trust managers validate"
                    + " certificates.";
        }
        return kind + " (" + target + ")" + at + ".";
    }

    private static String classes(String top, List<String> others, boolean more) {
        StringBuilder text = new StringBuilder(top == null || top.startsWith("(") ? "not named" : top);
        for (String name : others) {
            if (!name.equals(top)) {
                text.append(", ").append(name);
            }
        }
        if (more) {
            text.append(", and more");
        }
        return text.toString();
    }
}
