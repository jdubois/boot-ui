package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.CodeInventory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.bytebuddy.asm.Advice;

/**
 * The inventory sensor's advice (PLAN-v2 M5-3), inlined at the entry of every instrumented application method and
 * constructor: one array read and one volatile read once the method ran in this run. The method's id is bound as a
 * constant per method ({@link MethodId}), so the advice reads no field of the instrumented class.
 */
final class InventoryAdvice {

    private InventoryAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@MethodId int id) {
        if (CodeInventory.HITS[id] != CodeInventory.epoch) {
            CodeInventory.hit(id);
        }
    }

    /** The instrumented method's id in {@link CodeInventory}, bound as a constant by the inventory sensor. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface MethodId {}
}
