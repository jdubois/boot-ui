package io.github.jdubois.bootui.engine.safety;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * An immutable IPv4 or IPv6 CIDR range used by the localhost guard to widen the trusted source set
 * beyond loopback (for example a local Docker bridge subnet).
 *
 * <p>Matching is done on raw address bytes so it never performs DNS resolution and never mixes
 * address families: an IPv4 candidate is only ever compared against an IPv4 range and likewise for
 * IPv6.</p>
 */
public final class CidrRange {

    private final byte[] network;
    private final int prefixLength;

    private CidrRange(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses a CIDR entry such as {@code 172.16.0.0/12} or {@code fd00::/8}. A bare address without a
     * {@code /prefix} suffix is treated as a single host (full-length prefix).
     *
     * @return the parsed range, or {@code null} when the entry is blank or malformed
     */
    public static CidrRange parse(String cidr) {
        if (cidr == null) {
            return null;
        }
        String candidate = cidr.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        String addressPart = candidate;
        Integer explicitPrefix = null;
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            addressPart = candidate.substring(0, slash);
            String prefixPart = candidate.substring(slash + 1).trim();
            try {
                explicitPrefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        addressPart = addressPart.trim();
        if (!isNumericIpLiteral(addressPart)) {
            return null;
        }
        byte[] address;
        try {
            address = InetAddress.getByName(addressPart).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
        int maxPrefix = address.length * 8;
        int prefixLength = explicitPrefix != null ? explicitPrefix : maxPrefix;
        if (prefixLength < 0 || prefixLength > maxPrefix) {
            return null;
        }
        maskInPlace(address, prefixLength);
        return new CidrRange(address, prefixLength);
    }

    /** Returns {@code true} when {@code address} falls within this range. */
    boolean contains(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
        return true;
    }

    private static void maskInPlace(byte[] address, int prefixLength) {
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        if (remainingBits > 0 && fullBytes < address.length) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            address[fullBytes] &= (byte) mask;
            fullBytes++;
        }
        for (int i = fullBytes; i < address.length; i++) {
            address[i] = 0;
        }
    }

    /**
     * Guards {@link InetAddress#getByName} against accidental DNS lookups: only IPv4/IPv6 numeric
     * literals (digits, dots, colons, hex, optional IPv6 brackets) are accepted.
     */
    private static boolean isNumericIpLiteral(String value) {
        String host = value.trim();
        if (host.length() > 2 && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean sawColon = false;
        boolean sawDot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (c == ':') {
                sawColon = true;
            } else if (c == '.') {
                sawDot = true;
            } else if (!hexDigit) {
                return false;
            }
        }
        return sawColon || sawDot;
    }
}
