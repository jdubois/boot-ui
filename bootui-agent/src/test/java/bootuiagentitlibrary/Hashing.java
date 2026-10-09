package bootuiagentitlibrary;

import java.security.MessageDigest;

/** A library outside the claimed packages asking for a digest on the application's behalf. */
public final class Hashing {

    private Hashing() {}

    public static byte[] sha1(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(bytes);
    }
}
