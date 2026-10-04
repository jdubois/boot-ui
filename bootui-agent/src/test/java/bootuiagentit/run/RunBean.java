package bootuiagentit.run;

/** A bean class of each simulated run, timed by the code-paths sensor. */
public final class RunBean {

    public int work(int value) {
        return Probed.touch(value) + 1;
    }
}
