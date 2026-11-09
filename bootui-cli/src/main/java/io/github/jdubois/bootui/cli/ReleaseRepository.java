package io.github.jdubois.bootui.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Bounded Maven metadata and verified artifact reads shared by the notice and explicit updater. */
final class ReleaseRepository {
    static final String DEFAULT = "https://repo1.maven.org/maven2";
    static final String ARTIFACT_PATH = "/com/julien-dubois/bootui/bootui-cli/";
    static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]*");
    private static final int MAX_METADATA = 512 * 1024;
    private static final int MAX_JAR = 64 * 1024 * 1024;
    private final URI base;
    private final HttpClient client;

    ReleaseRepository(String repository) {
        String normalized = repository.trim().replaceAll("/+$", "");
        try {
            base = URI.create(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Maven repository base URL.");
        }
        if (base.getHost() == null
                || !List.of("http", "https").contains(base.getScheme())
                || base.getRawQuery() != null
                || base.getRawFragment() != null
                || base.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "BOOTUI_MAVEN_REPO must be an HTTP(S) repository base URL without credentials, query, or fragment.");
        }
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static String latest(URI metadata, String installed) throws IOException, InterruptedException {
        String address = metadata.toString();
        String suffix = ARTIFACT_PATH + "maven-metadata.xml";
        if (!address.endsWith(suffix)) {
            throw new IOException("Unexpected Maven metadata URL.");
        }
        return new ReleaseRepository(address.substring(0, address.length() - suffix.length())).latest(installed);
    }

    String latest(String installed) throws IOException, InterruptedException {
        byte[] bytes = fetch(URI.create(base + ARTIFACT_PATH + "maven-metadata.xml"), MAX_METADATA, false, installed);
        String body = new String(bytes, StandardCharsets.UTF_8);
        String version = match(body, "release");
        if (version == null) {
            version = match(body, "latest");
        }
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+)*")) {
            throw new IOException("Repository metadata contains no usable release version.");
        }
        try {
            for (String part : version.split("\\.")) {
                Long.parseLong(part);
            }
        } catch (NumberFormatException overflow) {
            throw new IOException("Repository release version exceeds the supported numeric range.");
        }
        return version;
    }

    void download(String version, String installed, Path destination) throws IOException, InterruptedException {
        if (!VERSION.matcher(version).matches()) {
            throw new IOException("Invalid release version.");
        }
        URI jar = URI.create(base + ARTIFACT_PATH + version + "/bootui-cli-" + version + "-all.jar");
        byte[] bytes = fetch(jar, MAX_JAR, false, installed);
        for (String algorithm : List.of("SHA-512", "SHA-256", "SHA-1")) {
            String suffix = algorithm.toLowerCase(java.util.Locale.ROOT).replace("-", "");
            byte[] checksum = fetch(URI.create(jar + "." + suffix), 4096, true, installed);
            if (checksum == null) {
                continue;
            }
            String expected =
                    new String(checksum, StandardCharsets.UTF_8).trim().split("\\s+", 2)[0];
            try {
                byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
                if (!expected.matches("(?i)[0-9a-f]{" + (digest.length * 2) + "}")) {
                    throw new IOException("Malformed published " + algorithm + " checksum. Nothing was installed.");
                }
                if (!HexFormat.of().formatHex(digest).equalsIgnoreCase(expected)) {
                    throw new IOException(
                            "Download does not match the published " + algorithm + " checksum. Nothing was installed.");
                }
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException("Required checksum algorithm is unavailable: " + algorithm, impossible);
            }
            Files.write(destination, bytes);
            return;
        }
        throw new IOException("No published checksum is available. Nothing was installed.");
    }

    private byte[] fetch(URI uri, int maximum, boolean optional, String installed)
            throws IOException, InterruptedException {
        Duration timeout = Duration.ofSeconds(maximum == MAX_JAR ? 60 : 5);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "bootui-cli/" + installed)
                .GET()
                .build();
        var pending = client.sendAsync(request, info -> new LimitedBody(maximum));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new IOException("Maven request timed out while reading " + uri.getPath() + ".", expired);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException failure) {
                String detail = failure.getMessage();
                throw new IOException(
                        "Cannot read Maven repository response from " + uri.getHost() + uri.getPath() + ": "
                                + (detail == null || detail.isBlank()
                                        ? failure.getClass().getSimpleName()
                                        : detail),
                        failure);
            }
            throw new IOException("Maven request failed for " + uri.getPath() + ".", failed.getCause());
        }
        if (optional && response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("Maven repository returned HTTP " + response.statusCode() + " for " + uri.getPath());
        }
        return response.body();
    }

    private static String match(String xml, String element) {
        var matcher =
                Pattern.compile("<" + element + ">([^<]*)</" + element + ">").matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long maximum;
        private long received;
        private Flow.Subscription subscription;
        private boolean failed;

        LimitedBody(long maximum) {
            this.maximum = maximum;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
            }
            if (received > maximum) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Maven response exceeds the " + maximum + " byte limit."));
            } else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
            }
        }
    }
}
