package bootuicodepathsapp;

/** Not a bean: never instrumented by the code-paths sensor. */
public class Helper {

    public static int percent(int value, int percent) {
        return value * percent / 100;
    }

    public int instance() {
        return 1;
    }
}
