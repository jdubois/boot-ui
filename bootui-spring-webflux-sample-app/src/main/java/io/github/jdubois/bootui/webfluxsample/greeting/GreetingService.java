package io.github.jdubois.bootui.webfluxsample.greeting;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Trivial cached lookup so the Cache panel has a populated cache to inspect and clear. */
@Service
public class GreetingService {

    @Cacheable("sample-greetings")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
