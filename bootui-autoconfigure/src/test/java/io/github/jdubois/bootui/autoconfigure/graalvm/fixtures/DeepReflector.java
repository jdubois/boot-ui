package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.lang.reflect.Field;

/** Triggers GRAAL-REFLECT-003 by using deep reflection (setAccessible). */
public class DeepReflector {

    public void open(Field field) {
        field.setAccessible(true);
    }
}
