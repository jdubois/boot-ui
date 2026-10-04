package bootuicodepathsapp;

/** A record bean: its accessors are left alone, its other methods are instrumented. */
public record Money(int amount) {

    public int doubled() {
        return amount * 2;
    }
}
