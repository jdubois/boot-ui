package io.github.classgraph;

/**
 * Minimal test stub standing in for the absent ClassGraph library, so GRAAL-SCAN-001 fixtures can
 * compile and exercise the ClassGraph detection branch. Not the real library.
 */
public class ClassGraph {

    public ClassGraph enableAllInfo() {
        return this;
    }

    public Object scan() {
        return new Object();
    }
}
