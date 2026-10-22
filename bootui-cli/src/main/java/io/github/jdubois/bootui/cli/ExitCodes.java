package io.github.jdubois.bootui.cli;

/**
 * What the process exits with.
 *
 * <p>The distinction that earns its keep is {@link #REFUSED}: a CI job that asks a read-only panel to run a
 * scan has not hit an error, it has hit a configuration statement about the target application, and a script
 * should be able to tell those apart without parsing stderr.
 */
public final class ExitCodes {

    /** The tool ran and answered. */
    public static final int SUCCESS = 0;

    /** Bad usage, or the application could not be reached or did not answer. */
    public static final int ERROR = 1;

    /** BootUI declined to run the tool: the panel is disabled, or read-only and this is an action. */
    public static final int REFUSED = 2;

    /** An advisor answered, and a retained finding is at or above the {@code --fail-on} severity. */
    public static final int THRESHOLD = 3;

    private ExitCodes() {}
}
