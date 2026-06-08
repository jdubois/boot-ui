package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Triggers SPRING-AOT-001 by registering a singleton bean at run time. */
public class RuntimeSingletonRegistrar {

    public void register(DefaultListableBeanFactory factory) {
        factory.registerSingleton("dynamicBean", new Object());
    }
}
