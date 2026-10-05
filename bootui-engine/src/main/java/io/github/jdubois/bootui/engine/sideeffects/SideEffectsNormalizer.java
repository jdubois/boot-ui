package io.github.jdubois.bootui.engine.sideeffects;

import java.util.regex.Pattern;

/**
 * Normalizes Side Effects targets and thread names ({@code docs/PLAN-v2.md} §5.16), so that rows aggregate what differs
 * only by an id: the home directory becomes {@code ~}, a UUID {@code {uuid}}, a run of eight or more hexadecimal
 * characters with a digit {@code {hex}}, and a run of digits {@code {n}}.
 */
public final class SideEffectsNormalizer {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX =
            Pattern.compile("(?<![0-9A-Za-z])(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{8,}(?![0-9A-Za-z])");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    /** The longest normalized text kept, in characters. */
    static final int MAX_LENGTH = 200;

    private final String home;

    /** @param home the user's home directory, or {@code null} */
    public SideEffectsNormalizer(String home) {
        this.home = home == null || home.isBlank() || "/".equals(home) ? null : home;
    }

    /** {@code text} normalized; {@code null} stays {@code null}. */
    public String target(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text;
        if (home != null
                && (normalized.equals(home)
                        || normalized.startsWith(home + "/")
                        || normalized.startsWith(home + "\\"))) {
            normalized = "~" + normalized.substring(home.length());
        }
        normalized = UUID.matcher(normalized).replaceAll("{uuid}");
        normalized = HEX.matcher(normalized).replaceAll("{hex}");
        normalized = DIGITS.matcher(normalized).replaceAll("{n}");
        return normalized.length() > MAX_LENGTH ? normalized.substring(0, MAX_LENGTH) + "…" : normalized;
    }

    /**
     * A network target normalized: a host and port keep their digits, which are the information (an address, a port);
     * only UUIDs and long hexadecimal runs are folded, and the home directory of a {@code unix:} path.
     */
    public String networkTarget(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text;
        if (normalized.startsWith("unix:")) {
            String path = normalized.substring(5);
            if (home != null && (path.equals(home) || path.startsWith(home + "/") || path.startsWith(home + "\\"))) {
                normalized = "unix:~" + path.substring(home.length());
            }
        }
        normalized = UUID.matcher(normalized).replaceAll("{uuid}");
        normalized = HEX.matcher(normalized).replaceAll("{hex}");
        return normalized.length() > MAX_LENGTH ? normalized.substring(0, MAX_LENGTH) + "…" : normalized;
    }

    /** A thread name's family: its digit runs collapsed, as {@code pool-{n}-thread-{n}}. */
    public String threadFamily(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String family = DIGITS.matcher(name).replaceAll("{n}");
        return family.length() > MAX_LENGTH ? family.substring(0, MAX_LENGTH) + "…" : family;
    }
}
