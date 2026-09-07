package io.github.jdubois.bootui.engine.graalvm.fixtures;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.reflections.Reflections;
import org.reflections.Store;

public class PassiveClasspathMetadata {

    public ClassGraph configure() {
        return new ClassGraph().enableAllInfo();
    }

    public Object read(ScanResult result, Store metadata) {
        result.close();
        new Reflections(metadata).getSubTypesOf(Runnable.class);
        return result.getAllClasses();
    }
}
