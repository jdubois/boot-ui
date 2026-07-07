package io.github.jdubois.bootui.engine.graalvm.fixtures;

/**
 * Triggers GRAAL-REFLECT-005 by calling {@code Unsafe.allocateInstance(Class)} directly.
 *
 * <p>The {@code sun.misc.Unsafe} reference below is load-bearing, not incidental: {@code
 * UnsafeAllocateInstanceCheck} matches on the ArchUnit bytecode call target's owner name, so the fixture must
 * compile a real call to that exact class. The resulting "Unsafe is internal proprietary API" javac warning is
 * therefore expected and cannot be suppressed with {@code @SuppressWarnings} (it is not routed through the
 * standard lint machinery); do not "fix" it by rewriting this fixture to use reflection instead.
 */
public class UnsafeAllocator {

    public Object allocate(sun.misc.Unsafe unsafe) throws InstantiationException {
        return unsafe.allocateInstance(String.class);
    }
}
