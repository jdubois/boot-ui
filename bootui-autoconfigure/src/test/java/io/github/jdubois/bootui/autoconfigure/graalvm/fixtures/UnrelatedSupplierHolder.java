package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.util.function.Supplier;

/**
 * Declares and calls its own unrelated setInstanceSupplier method on a non-Spring type, so it must
 * NOT trigger SPRING-AOT-002 (which matches Spring bean-definition owners only).
 */
public class UnrelatedSupplierHolder {

    private Supplier<Object> instanceSupplier;

    public void setInstanceSupplier(Supplier<Object> supplier) {
        this.instanceSupplier = supplier;
    }

    public Supplier<Object> configure() {
        setInstanceSupplier(Object::new);
        return instanceSupplier;
    }
}
