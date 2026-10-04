package io.github.jdubois.bootui.engine.javaagent;

/** A Java agent that only announces itself, so a test can launch the generated setup snippet with a real agent. */
public final class SnippetProbeAgent {

    static final String MARKER = "BOOTUI_SNIPPET_PROBE_AGENT";

    private SnippetProbeAgent() {}

    public static void premain(String arguments) {
        System.out.println(MARKER);
    }
}
