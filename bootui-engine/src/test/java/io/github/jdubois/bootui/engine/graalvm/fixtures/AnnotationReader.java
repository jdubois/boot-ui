package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Method;

/** Triggers GRAAL-REFLECT-004 by reading annotations from a reflected member. */
public class AnnotationReader {

    public boolean deprecated(Method method) {
        return method.isAnnotationPresent(Deprecated.class);
    }
}
