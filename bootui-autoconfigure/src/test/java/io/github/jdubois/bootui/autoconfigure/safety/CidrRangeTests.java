package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CidrRangeTests {

    @Test
    void matchesAddressInsideIpv4Range() throws Exception {
        CidrRange range = CidrRange.parse("172.16.0.0/12");
        assertThat(range).isNotNull();
        assertThat(range.contains(InetAddress.getByName("172.17.0.1"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("172.31.255.255"))).isTrue();
    }

    @Test
    void rejectsAddressOutsideIpv4Range() throws Exception {
        CidrRange range = CidrRange.parse("172.16.0.0/12");
        assertThat(range.contains(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(range.contains(InetAddress.getByName("192.168.0.1"))).isFalse();
    }

    @Test
    void matchesAddressInsideIpv6Range() throws Exception {
        CidrRange range = CidrRange.parse("fd00::/8");
        assertThat(range).isNotNull();
        assertThat(range.contains(InetAddress.getByName("fd00::1234"))).isTrue();
    }

    @Test
    void doesNotMatchAcrossAddressFamilies() throws Exception {
        CidrRange ipv4 = CidrRange.parse("172.16.0.0/12");
        assertThat(ipv4.contains(InetAddress.getByName("fd00::1"))).isFalse();
    }

    @Test
    void treatsBareAddressAsSingleHost() throws Exception {
        CidrRange range = CidrRange.parse("172.17.0.1");
        assertThat(range).isNotNull();
        assertThat(range.contains(InetAddress.getByName("172.17.0.1"))).isTrue();
        assertThat(range.contains(InetAddress.getByName("172.17.0.2"))).isFalse();
    }

    @Test
    void returnsNullForBlankOrNullInput() {
        assertThat(CidrRange.parse(null)).isNull();
        assertThat(CidrRange.parse("   ")).isNull();
    }

    @Test
    void returnsNullForMalformedEntries() {
        assertThat(CidrRange.parse("not-a-cidr")).isNull();
        assertThat(CidrRange.parse("172.16.0.0/abc")).isNull();
        assertThat(CidrRange.parse("172.16.0.0/40")).isNull();
        assertThat(CidrRange.parse("example.com/24")).isNull();
    }
}
