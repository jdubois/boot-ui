package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

/** A class with no native-image readiness concerns. */
public class CleanComponent {

    public int add(int a, int b) {
        return a + b;
    }
}
