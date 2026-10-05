package sun.net.www.fake;

import java.util.function.Supplier;

/** Stands for the JDK's HttpURLConnection client, just above the socket. */
public final class FakeHttpClient {

    private FakeHttpClient() {}

    public static long[] connect(Supplier<long[]> socket) {
        return socket.get();
    }
}
