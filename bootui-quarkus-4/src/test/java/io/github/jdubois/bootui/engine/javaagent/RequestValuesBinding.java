package io.github.jdubois.bootui.engine.javaagent;

/** Tests only: binds the engine's request value hooks to a fake holder from the Quarkus adapter's tests. */
public final class RequestValuesBinding {

    private RequestValuesBinding() {}

    public static void bind(Class<?> requestValues) {
        AgentRequestValues.bind(requestValues);
    }

    public static void rebind() {
        AgentRequestValues.rebind();
    }
}
