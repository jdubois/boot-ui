package io.github.classgraph;

/** Test-only result type, intentionally separate from the ClassGraph scanner. */
public class ScanResult implements AutoCloseable {

    public Object getAllClasses() {
        return new Object();
    }

    @Override
    public void close() {}
}
