package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

/** Triggers GRAAL-NATIVE-001 by loading a native library. */
public class NativeLoader {

    public void init() {
        System.loadLibrary("demo");
    }
}
