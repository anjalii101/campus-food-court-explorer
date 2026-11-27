package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressesTest {

    @Test
    void ipv4IsUsedAsIs() {
        assertThat(ClientAddresses.key("203.0.113.9", 64)).isEqualTo("203.0.113.9");
        assertThat(ClientAddresses.key(" 127.0.0.1 ", 64)).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6AddressesInTheSameSlash64ShareOneKey() {
        String a = ClientAddresses.key("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd", 64);
        String b = ClientAddresses.key("2001:db8:1234:5678::1", 64);
        String otherNetwork = ClientAddresses.key("2001:db8:1234:5679::1", 64);
        assertThat(a).isEqualTo(b).isEqualTo("2001:db8:1234:5678:0:0:0:0/64");
        assertThat(otherNetwork).isNotEqualTo(a);
    }

    @Test
    void prefixLengthIsConfigurable() {
        assertThat(ClientAddresses.key("2001:db8::1", 128)).isEqualTo("2001:db8:0:0:0:0:0:1/128");
        assertThat(ClientAddresses.key("2001:db8:abcd:ef01::1", 48)).isEqualTo("2001:db8:abcd:0:0:0:0:0/48");
        assertThat(ClientAddresses.key("2001:db8:abcd:ff01::1", 56)).isEqualTo("2001:db8:abcd:ff00:0:0:0:0/56");
    }

    @Test
    void loopbackBracketsZoneIdsAndIpv4MappedAddressesAreNormalised() {
        assertThat(ClientAddresses.key("0:0:0:0:0:0:0:1", 64)).isEqualTo(ClientAddresses.key("::1", 64));
        assertThat(ClientAddresses.key("[2001:db8::1]", 64)).isEqualTo(ClientAddresses.key("2001:db8::2", 64));
        assertThat(ClientAddresses.key("fe80::1%eth0", 64)).isEqualTo(ClientAddresses.key("fe80::2", 64));
        assertThat(ClientAddresses.key("::ffff:198.51.100.4", 64)).isEqualTo("198.51.100.4");
    }

    @Test
    void missingOrUnparseableAddressesNeverTriggerLookupsAndAreBounded() {
        assertThat(ClientAddresses.key(null, 64)).isEqualTo(ClientAddresses.UNKNOWN);
        assertThat(ClientAddresses.key("  ", 64)).isEqualTo(ClientAddresses.UNKNOWN);
        assertThat(ClientAddresses.key("not:an:address:zz", 64)).isEqualTo("not:an:address:zz");
        assertThat(ClientAddresses.key("example.com", 64)).isEqualTo("example.com");
        assertThat(ClientAddresses.key("x".repeat(500), 64)).hasSize(ClientAddresses.MAX_KEY_LENGTH);
        assertThat(ClientAddresses.key("1:".repeat(300), 64)).hasSizeLessThanOrEqualTo(ClientAddresses.MAX_KEY_LENGTH);
    }
}
