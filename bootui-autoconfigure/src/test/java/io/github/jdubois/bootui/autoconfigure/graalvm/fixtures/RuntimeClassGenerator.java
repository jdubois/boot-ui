package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.lang.invoke.MethodHandles;

/** Triggers GRAAL-CLASSGEN-001 by defining a class at run time through a MethodHandles lookup. */
public class RuntimeClassGenerator {

    public Class<?> generate(byte[] bytecode) throws IllegalAccessException {
        return MethodHandles.lookup().defineClass(bytecode);
    }
}
