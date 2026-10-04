package bootuicodepathsapp;

/** Methods for method probes (PLAN-v2 M5-8): an overload, and one method per concurrent probe. */
public class Quotes {

    public int quote(int quantity) {
        return quantity * 3;
    }

    public long quote(long quantity) {
        return quantity * 4L;
    }

    public int m1() {
        return 1;
    }

    public int m2() {
        return 2;
    }

    public int m3() {
        return 3;
    }

    public int m4() {
        return 4;
    }

    public int m5() {
        return 5;
    }

    public int m6() {
        return 6;
    }
}
