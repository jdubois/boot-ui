package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.util.Set;
import org.reflections.Reflections;

/** Triggers GRAAL-SCAN-001 by scanning the classpath through the Reflections library constructor. */
public class ReflectionsScanner {

    public Set<Class<?>> subTypes() {
        return new Reflections("com.example").getSubTypesOf(Runnable.class);
    }
}
