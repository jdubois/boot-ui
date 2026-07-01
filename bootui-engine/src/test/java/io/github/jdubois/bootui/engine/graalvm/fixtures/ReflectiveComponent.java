package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Method;

/** Triggers GRAAL-REFLECT-001 by using the reflection API. */
public class ReflectiveComponent {

    public Object create(String className) throws Exception {
        Class<?> type = Class.forName(className);
        Method method = type.getDeclaredMethod("toString");
        return method.invoke(type.getDeclaredConstructor().newInstance());
    }
}
