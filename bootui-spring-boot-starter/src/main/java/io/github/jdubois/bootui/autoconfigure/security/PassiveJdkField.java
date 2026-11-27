package io.github.jdubois.bootui.autoconfigure.security;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Reads a JDK wrapper's backing field without executing a potentially application-owned delegate. */
final class PassiveJdkField {
    private PassiveJdkField() {}

    static Object read(Object wrapper, Class<?> owner, String name, Class<?> expectedType) {
        try {
            Field delegate = owner.getDeclaredField(name);
            if (delegate.getType() != expectedType || Modifier.isStatic(delegate.getModifiers())) return null;
            if (delegate.trySetAccessible()) return delegate.get(wrapper);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe", false, ClassLoader.getPlatformClassLoader());
            Field singleton = unsafeType.getDeclaredField("theUnsafe");
            if (!singleton.trySetAccessible()) return null;
            Object unsafe = singleton.get(null);
            long offset = (Long)
                    unsafeType.getMethod("objectFieldOffset", Field.class).invoke(unsafe, delegate);
            return unsafeType.getMethod("getObject", Object.class, long.class).invoke(unsafe, wrapper, offset);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }
}
