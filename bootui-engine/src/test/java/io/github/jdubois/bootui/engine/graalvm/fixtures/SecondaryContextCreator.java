package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Triggers SPRING-AOT-004 by constructing a secondary AnnotationConfigApplicationContext. */
public class SecondaryContextCreator {

    public AnnotationConfigApplicationContext createContext() {
        return new AnnotationConfigApplicationContext();
    }
}
