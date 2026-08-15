package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.invoke.MethodHandles;

public class MethodHandleClassLookup {

    public Class<?> find(String name) throws ClassNotFoundException, IllegalAccessException {
        return MethodHandles.lookup().findClass(name);
    }
}
