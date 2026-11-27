package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Turns the TCP peer address ({@code HttpServletRequest#getRemoteAddr()}) into a rate-limit key.
 * <p>
 * Client-supplied headers such as {@code X-Forwarded-For} are never read here: anyone can send them.
 * Behind a reverse proxy, configure the proxy as trusted at the server level (see docs/authentication.md),
 * which makes {@code getRemoteAddr()} the real client address.
 * <ul>
 *   <li>IPv4 addresses (including IPv4-mapped IPv6) are used as they are.</li>
 *   <li>IPv6 addresses are truncated to a configurable prefix (default /64), because one subscriber usually
 *       controls a whole /64 and could otherwise rotate addresses to evade the limit and fill the table.</li>
 *   <li>Never performs DNS lookups; anything unparseable is used verbatim, truncated to bound memory.</li>
 * </ul>
 */
final class ClientAddresses {

    static final String UNKNOWN = "unknown";
    static final int MAX_KEY_LENGTH = 64;

    private ClientAddresses() {
    }

    static String key(String remoteAddr, int ipv6PrefixLength) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return UNKNOWN;
        }
        String address = remoteAddr.trim();
        if (address.indexOf(':') < 0) {
            return bounded(address); // IPv4 literal (Tomcat does not resolve host names by default)
        }
        String literal = address;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        // Only hex digits, ':' and '.' (embedded IPv4): guarantees InetAddress parses a literal, never resolves DNS.
        if (literal.isEmpty() || !literal.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == ':' || c == '.')) {
            return bounded(address);
        }
        try {
            InetAddress ip = InetAddress.getByName(literal);
            if (ip instanceof Inet4Address) {
                return ip.getHostAddress(); // IPv4-mapped IPv6 (::ffff:a.b.c.d)
            }
            byte[] bytes = ip.getAddress();
            maskToPrefix(bytes, ipv6PrefixLength);
            return InetAddress.getByAddress(bytes).getHostAddress().toLowerCase(Locale.ROOT) + "/" + ipv6PrefixLength;
        } catch (UnknownHostException e) {
            return bounded(address);
        }
    }

    private static void maskToPrefix(byte[] bytes, int prefixLength) {
        for (int i = 0; i < bytes.length; i++) {
            int bitsKept = Math.max(0, Math.min(8, prefixLength - i * 8));
            bytes[i] &= (byte) (0xFF << (8 - bitsKept));
        }
    }

    private static String bounded(String value) {
        return value.length() <= MAX_KEY_LENGTH ? value : value.substring(0, MAX_KEY_LENGTH);
    }
}
