package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Triggers GRAAL-RES-001 through Spring's classpath resource abstraction. */
public class SpringResourceLoader {

    public Resource template(String name) {
        return new ClassPathResource("templates/" + name);
    }

    public Resource[] seeds() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:seeds/*.json");
    }
}
