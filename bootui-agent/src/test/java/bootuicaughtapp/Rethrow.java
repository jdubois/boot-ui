package bootuicaughtapp;

/** A library-style helper that throws what it is given, checked or not. */
public final class Rethrow {

    private Rethrow() {}

    public static void sneaky(Throwable thrown) {
        Rethrow.<RuntimeException>throwIt(thrown);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwIt(Throwable thrown) throws T {
        throw (T) thrown;
    }
}
