package io.github.jdubois.bootui.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Bounded HTTP/1 probe for raw Host headers and an explicitly bound real TCP source. */
public final class BootUiRawHttpProbe {

    private static final int LIMIT = 4 * 1024 * 1024;
    private final URI base;

    public BootUiRawHttpProbe(String baseUrl) {
        base = URI.create(baseUrl);
        if (!"http".equals(base.getScheme())) {
            throw new IllegalArgumentException("The raw test probe requires an HTTP server");
        }
    }

    public BootUiHttpProbe.Response request(
            String method, String path, Map<String, String> headers, String body, InetAddress source) {
        byte[] content = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.0\r\n");
        if (headers.keySet().stream().noneMatch(key -> "Host".equalsIgnoreCase(key))) {
            head.append("Host: ").append(base.getRawAuthority()).append("\r\n");
        }
        headers.forEach(
                (key, value) -> head.append(key).append(": ").append(value).append("\r\n"));
        head.append("Connection: close\r\nContent-Length: ")
                .append(content.length)
                .append("\r\n\r\n");
        try (Socket socket = new Socket()) {
            if (source != null) {
                socket.bind(new InetSocketAddress(source, 0));
            }
            InetAddress destination = InetAddress.getByName(base.getHost());
            if (source != null && destination.isLoopbackAddress()) {
                // Windows rejects a non-loopback source routed into a loopback destination.
                destination = source;
            }
            socket.connect(new InetSocketAddress(destination, base.getPort() < 0 ? 80 : base.getPort()), 10_000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(content);
            socket.getOutputStream().flush();
            byte[] response = socket.getInputStream().readNBytes(LIMIT + 1);
            if (response.length > LIMIT) {
                throw new IOException("Raw probe response exceeds its bounded test budget");
            }
            return parse(response);
        } catch (IOException ex) {
            throw new IllegalStateException("Raw HTTP test request failed", ex);
        }
    }

    private static BootUiHttpProbe.Response parse(byte[] response) throws IOException {
        String wire = new String(response, StandardCharsets.ISO_8859_1);
        int split = wire.indexOf("\r\n\r\n");
        if (split < 0) {
            throw new IOException("Incomplete raw HTTP response");
        }
        String[] lines = wire.substring(0, split).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1) {
                throw new IOException("Malformed raw HTTP response header");
            }
            headers.computeIfAbsent(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).trim());
        }
        byte[] body = java.util.Arrays.copyOfRange(response, split + 4, response.length);
        if (headers.getOrDefault("transfer-encoding", List.of()).contains("chunked")) {
            body = decodeChunks(body);
        }
        return new BootUiHttpProbe.Response(
                status,
                headers.getOrDefault("content-type", List.of("")).get(0),
                new String(body, StandardCharsets.UTF_8),
                headers);
    }

    private static byte[] decodeChunks(byte[] body) throws IOException {
        String wire = new String(body, StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        while (true) {
            int end = wire.indexOf("\r\n", offset);
            if (end < 0) {
                throw new IOException("Incomplete chunked response");
            }
            int size = Integer.parseInt(wire.substring(offset, end).split(";", 2)[0], 16);
            if (size == 0) {
                return decoded.toByteArray();
            }
            offset = end + 2;
            if (size < 0 || offset + size + 2 > body.length) {
                throw new IOException("Invalid chunked response");
            }
            decoded.write(body, offset, size);
            offset += size + 2;
        }
    }

    public static Optional<InetAddress> nonLoopbackSource() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isPointToPoint() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isAnyLocalAddress()) {
                        return Optional.of(address);
                    }
                }
            }
            return Optional.empty();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect local test interface addresses", ex);
        }
    }
}
