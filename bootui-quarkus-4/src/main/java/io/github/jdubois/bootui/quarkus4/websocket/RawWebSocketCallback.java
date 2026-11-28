package io.github.jdubois.bootui.quarkus4.websocket;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * One WebSockets Next callback method, captured at <em>build time</em> from the Jandex index and replayed
 * into the runtime through {@link WebSocketsRecorder}.
 *
 * <p>Only the annotation kind, the declaring class, the method name, and the declared message type are
 * captured. Nothing about the messages the callback receives is recorded here or anywhere else.</p>
 *
 * <p>Serialized by the Quarkus bytecode recorder, so the canonical constructor is annotated
 * {@link RecordableConstructor}; the module compiles with {@code -parameters}, which is how the recorder
 * matches constructor parameters back to record components.</p>
 *
 * @param type the callback kind, e.g. {@code ON_OPEN}, {@code ON_TEXT_MESSAGE}, {@code ON_CLOSE}
 * @param declaringClass fully-qualified name of the endpoint class declaring the callback
 * @param method the callback method name
 * @param messageType the declared message parameter type, or {@code null} when the callback takes none
 */
public record RawWebSocketCallback(String type, String declaringClass, String method, String messageType) {

    @RecordableConstructor
    public RawWebSocketCallback(String type, String declaringClass, String method, String messageType) {
        this.type = type;
        this.declaringClass = declaringClass;
        this.method = method;
        this.messageType = messageType;
    }
}
