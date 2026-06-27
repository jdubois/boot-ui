package io.github.jdubois.bootui.engine.graalvm.fixtures;

import io.github.classgraph.ClassGraph;

/** Triggers GRAAL-SCAN-001 by scanning the classpath through the ClassGraph library. */
public class ClassGraphScanner {

    public Object scan() {
        return new ClassGraph().enableAllInfo().scan();
    }
}
