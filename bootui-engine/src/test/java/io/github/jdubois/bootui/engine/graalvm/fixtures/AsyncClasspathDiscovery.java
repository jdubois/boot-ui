package io.github.jdubois.bootui.engine.graalvm.fixtures;

import io.github.classgraph.ClassGraph;
import java.util.concurrent.ExecutorService;

public class AsyncClasspathDiscovery {

    public Object scan(ClassGraph scanner, ExecutorService executor) {
        return scanner.scanAsync(executor, 2);
    }

    public String classpath(ClassGraph scanner) {
        return scanner.getClasspath();
    }
}
