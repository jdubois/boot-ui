package com.example.resources;

import java.util.concurrent.Callable;

/** An application class of the claimed packages, so a resource opened through it has an application frame. */
public final class ReportReader {

    private ReportReader() {}

    public static <T> T read(Callable<T> work) throws Exception {
        return work.call();
    }

    /** Calls a library that opens the resource: the application frame is below the library's. */
    public static <T> T readThroughLibrary(Callable<T> work) throws Exception {
        return org.example.library.Opener.open(work);
    }
}
