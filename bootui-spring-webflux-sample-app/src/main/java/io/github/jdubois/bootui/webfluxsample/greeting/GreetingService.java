package io.github.jdubois.bootui.webfluxsample.greeting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cached, configuration-backed greeting so the Cache panel and the Configuration panel both have
 * something real to inspect (mirrors {@code SampleCatalog.greeting} in the servlet sample app, minus the
 * retry-count property, which this minimal app has no use for).
 */
@Service
public class GreetingService {

    private final String greetingPrefix;

    public GreetingService(@Value("${sample.greeting:Hello}") String greetingPrefix) {
        this.greetingPrefix = greetingPrefix;
    }

    @Cacheable("sample-greetings")
    public String greet(String name) {
        return greetingPrefix + ", " + name + "!";
    }
}
