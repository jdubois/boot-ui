package io.github.jdubois.bootui.engine.architecture.fixtures;

/**
 * Clean test fixture that does not trip any architecture rule.
 */
public class CleanComponent {

    private final String name;

    public CleanComponent(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
