package io.github.jdubois.bootui.engine.graalvm.fixtures;

/** Triggers GRAAL-REFLECT-005 by calling {@code Unsafe.allocateInstance(Class)} directly. */
public class UnsafeAllocator {

    public Object allocate(sun.misc.Unsafe unsafe) throws InstantiationException {
        return unsafe.allocateInstance(String.class);
    }
}
