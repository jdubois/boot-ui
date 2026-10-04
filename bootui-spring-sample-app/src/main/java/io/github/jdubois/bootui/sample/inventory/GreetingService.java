package io.github.jdubois.bootui.sample.inventory;

import org.springframework.stereotype.Service;

/**
 * Code Inventory's seed ({@code docs/PLAN-v2.md} §5.15): {@link #greet} runs on every {@code GET /api/hello}, so the
 * panel lists it as executed with that route, and {@link #farewell} is never called, so it stays never executed.
 */
@Service
public class GreetingService {

    /** Called by {@code GET /api/hello}. */
    public String greet(String name) {
        return "Hello, " + name;
    }

    /** Never called: Code Inventory's never-executed seed. Do not call it. */
    public String farewell(String name) {
        return "Goodbye, " + name;
    }
}
