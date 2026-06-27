package io.github.jdubois.bootui.engine.graalvm.fixtures;

/** Triggers GRAAL-REFLECT-002 by loading a class by name through a ClassLoader. */
public class DynamicClassLoader {

    public Class<?> load(String name) throws ClassNotFoundException {
        return getClass().getClassLoader().loadClass(name);
    }
}
