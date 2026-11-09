package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseRepositoryTests {
    @TempDir
    Path directory;

    @Test
    void prefersReleaseAndAcceptsLatestOnlyWhenReleaseIsMissing() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            repository.put("maven-metadata.xml", "<latest>9.0.0</latest><release>2.0.0</release>");
            var releases = new ReleaseRepository(repository.url());
            assertThat(releases.latest("1.0.0")).isEqualTo("2.0.0");
            repository.put("maven-metadata.xml", "<latest>3.0.0</latest>");
            assertThat(releases.latest("1.0.0")).isEqualTo("3.0.0");
            repository.metadata("../invalid");
            assertThatThrownBy(() -> releases.latest("1.0.0")).hasMessageContaining("no usable");
            repository.metadata("3.0.0-SNAPSHOT");
            assertThatThrownBy(() -> releases.latest("1.0.0")).hasMessageContaining("no usable");
        }
    }

    @Test
    void checksEverySupportedDigestAndFallsBackOnlyOnMissingChecksums() throws Exception {
        for (String algorithm : new String[] {"SHA-512", "SHA-256", "SHA-1"}) {
            try (var repository = new UpdateTestRepository()) {
                byte[] jar = UpdateTestRepository.jar("2.0.0");
                repository.artifact("2.0.0", jar, algorithm);
                Path target = directory.resolve(algorithm);
                new ReleaseRepository(repository.url()).download("2.0.0", "1.0.0", target);
                assertThat(Files.readAllBytes(target)).isEqualTo(jar);
                assertThat(repository.requests)
                        .last()
                        .asString()
                        .endsWith(algorithm.toLowerCase().replace("-", ""));
            }
        }
    }

    @Test
    void missingMalformedAndMismatchedChecksumsNeverWriteADownload() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            repository.artifact("2.0.0", UpdateTestRepository.jar("2.0.0"), "SHA-256");
            String checksum = ReleaseRepository.ARTIFACT_PATH + "2.0.0/bootui-cli-2.0.0-all.jar.sha512";
            Path target = directory.resolve("download.jar");
            for (String invalid : new String[] {"not-a-checksum", "0".repeat(128)}) {
                repository.responses.put(checksum, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThatThrownBy(() -> new ReleaseRepository(repository.url()).download("2.0.0", "1.0.0", target))
                        .hasMessageContaining(invalid.startsWith("not") ? "Malformed" : "does not match");
                assertThat(target).doesNotExist();
                assertThat(repository.requests).doesNotContain(checksum.replace("sha512", "sha256"));
            }
            repository.responses.keySet().removeIf(path -> path.endsWith(".sha512") || path.endsWith(".sha256"));
            assertThatThrownBy(() -> new ReleaseRepository(repository.url()).download("2.0.0", "1.0.0", target))
                    .hasMessageContaining("No published checksum");
        }
    }

    @Test
    void transportFailuresAndOversizedMetadataAreExplicit() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            var releases = new ReleaseRepository(repository.url());
            assertThatThrownBy(() -> releases.latest("1.0.0")).hasMessageContaining("HTTP 404");
            repository.put("maven-metadata.xml", "x".repeat(512 * 1024 + 1));
            assertThatThrownBy(() -> releases.latest("1.0.0")).hasMessageContaining("byte limit");
            repository.statuses.put(ReleaseRepository.ARTIFACT_PATH + "maven-metadata.xml", 503);
            repository.put("maven-metadata.xml", "unavailable");
            assertThatThrownBy(() -> releases.latest("1.0.0")).hasMessageContaining("HTTP 503");
        }
        for (String invalid : new String[] {
            "file:///tmp/repo", "https://user:password@example.com", "https://example.com?token=secret"
        }) {
            assertThatThrownBy(() -> new ReleaseRepository(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aResponseThatSendsHeadersButStallsItsBodyStillTimesOut() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try {
                release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var repository = new ReleaseRepository(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> repository.latest("1.0.0")).hasMessageContaining("timed out");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void oversizedDownloadsAndChecksumsAbortWithoutWritingTheTarget() throws Exception {
        try (var repository = new UpdateTestRepository()) {
            String artifact = ReleaseRepository.ARTIFACT_PATH + "2.0.0/bootui-cli-2.0.0-all.jar";
            repository.responses.put(artifact, new byte[64 * 1024 * 1024 + 1]);
            Path target = directory.resolve("oversized.jar");
            assertThatThrownBy(() -> new ReleaseRepository(repository.url()).download("2.0.0", "1.0.0", target))
                    .hasMessageContaining("byte limit");
            assertThat(target).doesNotExist();
            repository.responses.put(artifact, UpdateTestRepository.jar("2.0.0"));
            repository.responses.put(artifact + ".sha512", new byte[4097]);
            assertThatThrownBy(() -> new ReleaseRepository(repository.url()).download("2.0.0", "1.0.0", target))
                    .hasMessageContaining("byte limit");
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void connectionFailuresHaveAnActionableNonemptyMessage() throws Exception {
        int unused;
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            unused = socket.getLocalPort();
        }
        assertThatThrownBy(() -> new ReleaseRepository("http://127.0.0.1:" + unused).latest("1.0.0"))
                .hasMessageMatching("Cannot read Maven repository response from 127\\.0\\.0\\.1/.+: .+")
                .hasCauseInstanceOf(java.net.ConnectException.class);
    }
}
