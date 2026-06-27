package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Field;

/**
 * Reads only reflective field metadata (name/type/modifiers) and therefore must NOT trigger
 * GRAAL-REFLECT-001, which now matches field value accessors only.
 */
public class FieldMetadataReader {

    public String describe(Field field) {
        return field.getName() + field.getType().getName() + field.getModifiers();
    }
}
