package bootuiagentit;

import java.security.MessageDigest;

/** Mockito's inline mock maker stubbing {@code MessageDigest.getInstance}, apart so other modes never link Mockito. */
final class SecurityChecksMockito {

    private SecurityChecksMockito() {}

    static void mockStatic(String mode) throws Exception {
        MessageDigest real = MessageDigest.getInstance("SHA-256");
        try (org.mockito.MockedStatic<MessageDigest> mocked = org.mockito.Mockito.mockStatic(MessageDigest.class)) {
            mocked.when(() -> MessageDigest.getInstance("MD5")).thenReturn(real);
            MessageDigest stubbed = MessageDigest.getInstance("MD5");
            System.out.println("MOCKITO_" + mode + "=" + (stubbed == real ? "ok" : "unexpected " + stubbed));
        }
        System.out.println(
                "MOCKITO_AFTER_" + mode + "=" + MessageDigest.getInstance("MD5").getAlgorithm());
    }
}
