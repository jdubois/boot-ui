package bootuihotswapapp;

/**
 * A bean class the HotSwap tests redefine while the inventory and code-paths sensors are claimed: the edit IntelliJ's
 * HotSwap would apply changes {@link #price()}'s {@code + 1} to {@code + 2}, a method body change the JVM accepts; a
 * second edit adds a method, a schema change it refuses.
 */
public class Shop {

    public int price() {
        return base() + 1;
    }

    public int base() {
        return 40;
    }

    public String later() {
        return "later";
    }
}
