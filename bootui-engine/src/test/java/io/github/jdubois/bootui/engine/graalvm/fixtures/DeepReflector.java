package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Field;

/** Uses deep reflection only; the retired GRAAL-REFLECT-003 must not reappear in any active check. */
public class DeepReflector {

    public void open(Field field) {
        field.setAccessible(true);
    }
}
