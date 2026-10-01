package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Field;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/** Uses Spring helpers that need no reflection metadata of their own; GRAAL-REFLECT-001 must stay quiet. */
public class OptionalDependencyProbe {

    public boolean jacksonPresent() {
        return ClassUtils.isPresent(
                "tools.jackson.databind.ObjectMapper", getClass().getClassLoader());
    }

    public void open(Field field) {
        ReflectionUtils.makeAccessible(field);
    }
}
