package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Triggers GRAAL-MH-001 by performing a MethodHandles.Lookup.findVirtual lookup. */
public class MethodHandleUser {

    public Object lookup() throws NoSuchMethodException, IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        return lookup.findVirtual(String.class, "length", MethodType.methodType(int.class));
    }
}
