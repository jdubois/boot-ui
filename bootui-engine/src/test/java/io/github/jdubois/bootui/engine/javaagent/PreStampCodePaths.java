package io.github.jdubois.bootui.engine.javaagent;

/** A bridge's {@code CodePaths} as an agent predating M5-4c's stamps has it: begin, end, and phase only. */
public final class PreStampCodePaths {

    private PreStampCodePaths() {}

    public static void begin() {}

    public static void end() {}

    public static void phase(int phase) {}
}
