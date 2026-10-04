package bootuicodepathsapp;

import io.github.jdubois.bootui.agent.bridge.CodePaths;

/** A bean with every kind of method the code-paths sensor instruments or leaves alone. */
public class OrderService {

    public int price(int quantity) {
        return quantity * 10 + rounding(quantity);
    }

    protected int rounding(int quantity) {
        return quantity % 2;
    }

    public int tax(int price) {
        return Helper.percent(price, 20);
    }

    public void fail() {
        throw new IllegalStateException("boom");
    }

    /** Calls only methods the sensor leaves alone, but {@code Money.doubled}. */
    public int callExcluded() {
        int total = secret()
                + util()
                + $dollar()
                + hashCode()
                + (equals(this) ? 1 : 0)
                + toString().length();
        Money money = new Money(3);
        total += money.amount() + money.doubled();
        total += new ShopProperties().getName().length();
        total += new Helper().instance();
        return total;
    }

    /** The calling thread's open instrumented calls, seen from inside this method. */
    public int depthInside() {
        return CodePaths.depth();
    }

    private int secret() {
        return 1;
    }

    public static int util() {
        return 1;
    }

    public int $dollar() {
        return 1;
    }

    @Override
    public boolean equals(Object other) {
        return other == this;
    }

    @Override
    public int hashCode() {
        return 1;
    }

    @Override
    public String toString() {
        return "OrderService";
    }
}
