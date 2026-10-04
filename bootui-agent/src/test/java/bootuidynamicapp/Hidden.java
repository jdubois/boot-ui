package bootuidynamicapp;

import java.io.Serializable;

/** The dynamic-access tests' reflective target, defined by the isolated loader and, separately, by the test loader. */
public class Hidden implements Runnable, Serializable {

    private static final long serialVersionUID = 1L;

    public Hidden() {}

    public String greet(String name) {
        return "hello " + name.length();
    }

    @Override
    public void run() {}

    /** A proxy interface only the isolated loader's copy of this package defines for it. */
    public interface Marker {}
}
