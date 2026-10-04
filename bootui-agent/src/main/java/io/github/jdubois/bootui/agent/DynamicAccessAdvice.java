package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.DynamicAccess;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * The dynamic-access sensor's advice (PLAN-v2 M5-9b spike). Inlined, never delegated: a caller-sensitive method such as
 * {@code Class.forName(String)} or {@code Method.invoke} asks {@code Reflection.getCallerClass()} for the frame right
 * below its own, so the advice must add no frame between the method and its caller. Each advice reads
 * {@link DynamicAccess#on} first: with no session, that one volatile read is its whole cost.
 */
final class DynamicAccessAdvice {

    private DynamicAccessAdvice() {}

    /** The hook's index in {@link DynamicAccess#hooks()}, bound as a constant when the advice is woven. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Hook {}

    /** The {@code Class.forName} overloads: the class returned, or a failure, which is only counted. */
    static final class ForName {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Hook int hook, @Advice.Return Class<?> type, @Advice.Thrown Throwable thrown) {
            if (DynamicAccess.on) {
                if (thrown == null) {
                    DynamicAccess.forName(type, hook);
                } else {
                    DynamicAccess.forNameFailed(hook);
                }
            }
        }
    }

    /** {@code Method.invoke}: the method, never its receiver or arguments. */
    static final class Invoke {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Method method) {
            if (DynamicAccess.on) {
                DynamicAccess.invoke(method);
            }
        }
    }

    /** {@code Constructor.newInstance}: the constructor, never its arguments. */
    static final class NewInstance {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Constructor<?> constructor) {
            if (DynamicAccess.on) {
                DynamicAccess.newInstance(constructor);
            }
        }
    }

    /** {@code Proxy.newProxyInstance}: the interface list, never the handler. */
    static final class NewProxyInstance {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(1) Class<?>[] interfaces) {
            if (DynamicAccess.on && interfaces != null) {
                DynamicAccess.proxy(interfaces);
            }
        }
    }

    /** The extended hooks, count-only: the spike's evidence that each method can be advised. */
    static final class Counted {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Hook int hook) {
            if (DynamicAccess.on) {
                DynamicAccess.hit(hook);
            }
        }
    }
}
