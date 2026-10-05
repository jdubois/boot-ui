package io.github.jdubois.bootui.engine.javaagent;

/** Tests only: binds the engine's request value hooks to the real bridge class from the test class path. */
public final class RequestValuesTesting {

    private RequestValuesTesting() {}

    public static void bind(Class<?> requestValues) {
        AgentRequestValues.bind(requestValues);
    }

    public static void rebind() {
        AgentRequestValues.rebind();
    }
}
