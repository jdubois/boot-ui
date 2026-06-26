package io.github.jdubois.bootui.engine.architecture.fixtures;

/**
 * Test fixture that deliberately writes to a standard stream so the architecture scanner has a
 * reproducible {@code ARCH-CODE-001} violation to detect.
 */
public class StandardStreamUser {

    public void emit() {
        System.out.println("fixture output");
    }
}
