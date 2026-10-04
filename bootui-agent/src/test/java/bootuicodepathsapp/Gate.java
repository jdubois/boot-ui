package bootuicodepathsapp;

import java.util.concurrent.CountDownLatch;

/** A bean whose method a test holds open, so a method probe's install or removal happens during a call (M5-8). */
public class Gate {

    /** Signals {@code entered}, then waits for {@code release}. */
    public int pass(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
            return 1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
