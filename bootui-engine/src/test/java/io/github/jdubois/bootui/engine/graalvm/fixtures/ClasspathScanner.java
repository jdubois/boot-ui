package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/** Triggers GRAAL-SCAN-001 by scanning the classpath for components at run time. */
public class ClasspathScanner {

    public Set<BeanDefinition> scan() {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        return provider.findCandidateComponents("com.example");
    }
}
