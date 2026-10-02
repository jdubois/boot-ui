package com.example.journalapp;

/**
 * Code outside BootUI's and every framework's packages, so a stack walk started from {@link #run} finds an application
 * frame, as it would in a real application.
 */
public final class ApplicationCode {

    private ApplicationCode() {}

    /** Runs {@code work} from an application frame. */
    public static void run(Runnable work) {
        work.run();
    }
}
