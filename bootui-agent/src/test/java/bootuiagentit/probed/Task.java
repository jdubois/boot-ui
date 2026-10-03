package bootuiagentit.probed;

/** A {@code Runnable} in the probed package: the OpenTelemetry agent instruments it too, adding its own interface. */
public final class Task implements Runnable {

    @Override
    public void run() {
        Greeter.greet("task");
    }
}
