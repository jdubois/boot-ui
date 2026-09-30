package io.github.jdubois.bootui.engine.architecture.locationfixtures;

/**
 * Location fixture: each standard-stream access sits on a line the location tests pin, so keep the line layout
 * stable when editing this file.
 */
public class LocatedStreamUser {

    static {
        System.err.println("static initializer");
    }

    private final Runnable callback = () -> System.out.println("lambda body");

    public void emit() {
        System.out.println("method body");
    }

    public Runnable callback() {
        return callback;
    }
}
