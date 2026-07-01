package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Field;

/** Triggers GRAAL-REFLECT-001 by reading a field value reflectively. */
public class FieldValueAccessor {

    public int read(Field field, Object target) throws IllegalAccessException {
        return field.getInt(target);
    }
}
