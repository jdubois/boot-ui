package io.github.jdubois.bootui.webfluxsample.sideeffects;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Security sinks' JDK check seeds ({@code docs/PLAN-v2.md} §5.16, M5-6b2). With the BootUI agent's {@code
 * security-sinks} sensor, {@code GET /api/sinks/checks/digest} asks for MD5, a weak digest, and its counterexamples
 * SHA-256 and AES/GCM/NoPadding, which show nothing; {@code GET /api/sinks/checks/deserialize} reads a cart back without
 * an {@code ObjectInputFilter}, and the same bytes with one, which shows nothing; {@code GET
 * /api/sinks/checks/trust-manager} initializes an {@code SSLContext} with a trust manager of its own, which only
 * delegates to the JDK's and is never used to connect. Each runs on {@code boundedElastic}, off the event loop, as the
 * trust store's read blocks.
 */
@RestController
@RequestMapping("/api/sinks/checks")
public class SecurityCheckSeedsController {

    /** A cart, read back from its own bytes. */
    record Cart(String owner, List<Integer> items) implements Serializable {}

    @GetMapping("/digest")
    public Mono<Map<String, Object>> digest() {
        return Mono.fromCallable(SecurityCheckSeedsController::digests).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/deserialize")
    public Mono<Map<String, Object>> deserialize() {
        return Mono.fromCallable(SecurityCheckSeedsController::reads).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/trust-manager")
    public Mono<Map<String, Object>> trustManager() {
        return Mono.fromCallable(SecurityCheckSeedsController::trust).subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> digests() throws GeneralSecurityException {
        byte[] input = "bootui-seed".getBytes(StandardCharsets.UTF_8);
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(input));
        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        Cipher.getInstance("AES/GCM/NoPadding");
        return Map.of("md5", md5, "sha256", sha256);
    }

    private static Map<String, Object> reads() throws IOException, ClassNotFoundException {
        byte[] bytes = serialize(new Cart("ada", new ArrayList<>(List.of(1, 2, 3))));
        Object unfiltered;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            unfiltered = in.readObject();
        }
        Object filtered;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    Cart.class.getName() + ";java.util.ArrayList;java.lang.Integer;java.lang.Number;!*"));
            filtered = in.readObject();
        }
        return Map.of("unfiltered", unfiltered.toString(), "filtered", filtered.toString());
    }

    private static Map<String, Object> trust() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new DelegatingTrustManager()}, null);
        return Map.of("protocol", context.getProtocol());
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /** A trust manager of the application that only delegates to the JDK's default one. */
    static final class DelegatingTrustManager implements X509TrustManager {

        private final X509TrustManager delegate;

        DelegatingTrustManager() throws GeneralSecurityException {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            this.delegate = (X509TrustManager) factory.getTrustManagers()[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
