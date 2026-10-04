package bootuiinventoryapp;

/** An application interface: its default method is instrumented, its abstract one is not. */
public interface Shape {

    double area();

    default String describe() {
        return "area " + area();
    }
}
