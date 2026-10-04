package bootuicodepathsapp;

/** A bean recursing past the sensor's 32 levels. */
public class Deep {

    public int down(int levels) {
        return levels == 0 ? 0 : 1 + down(levels - 1);
    }
}
