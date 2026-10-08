package org.example.library;

import java.util.concurrent.Callable;

/** A library class outside the claimed packages, opening a resource for the application that called it. */
public final class Opener {

    private Opener() {}

    public static <T> T open(Callable<T> work) throws Exception {
        return work.call();
    }
}
