package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Method;

/** Reads member annotations only; the retired GRAAL-REFLECT-004 must not reappear in any active check. */
public class AnnotationReader {

    public boolean deprecated(Method method) {
        return method.isAnnotationPresent(Deprecated.class);
    }
}
