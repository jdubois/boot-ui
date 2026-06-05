package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

/** Triggers GRAAL-NATIVE-002 by declaring a native method. */
public class NativeMethodHolder {

    public native void doNativeWork();
}
