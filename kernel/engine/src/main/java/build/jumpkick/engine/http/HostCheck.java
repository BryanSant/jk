// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Host-header validation — the DNS-rebinding defense (see {@code docs/http.md}). A rebinding attack
 * needs the victim's browser to address this server through an attacker-controlled DNS
 * <em>name</em>; an IP-literal {@code Host} can't be rebound. So the rule is: accept IP literals
 * (matching how a user legitimately addresses a loopback or LAN bind), plus the two names a real
 * user might type — {@code localhost} and this machine's own hostname. Any other name → {@code
 * 421}. When the header carries a port it must match the bound port.
 */
final class HostCheck {

    /**
     * This machine's own hostname, resolved once and cached — typically a local (non-DNS) lookup.
     * {@code null} when unavailable; the localhost/IP-literal paths still work.
     */
    private static final String LOCAL_HOSTNAME = localHostname();

    private HostCheck() {}

    static boolean allowed(String hostHeader, int boundPort) {
        if (hostHeader == null || hostHeader.isBlank()) return false; // HTTP/1.1 requires Host
        String host = hostHeader.trim();

        if (host.startsWith("[")) { // bracketed IPv6 literal, optionally [v6]:port
            int close = host.indexOf(']');
            if (close < 0) return false;
            return portMatches(host.substring(close + 1), boundPort) && isIpv6Literal(host.substring(1, close));
        }

        int colon = host.indexOf(':');
        String name = colon < 0 ? host : host.substring(0, colon);
        String portPart = colon < 0 ? "" : host.substring(colon);
        if (!portMatches(portPart, boundPort)) return false;
        return isIpv4Literal(name)
                || name.equalsIgnoreCase("localhost")
                || (LOCAL_HOSTNAME != null && name.equalsIgnoreCase(LOCAL_HOSTNAME));
    }

    /** {@code portPart} is either empty or {@code :<digits>}; when present it must match. */
    private static boolean portMatches(String portPart, int boundPort) {
        if (portPart.isEmpty()) return true;
        if (portPart.charAt(0) != ':') return false;
        try {
            return Integer.parseInt(portPart.substring(1)) == boundPort;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isIpv4Literal(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /**
     * Syntactic check only — hex digits, colons, and (for v4-mapped forms) dots. A DNS name can
     * never contain a colon, so nothing rebindable passes; malformed literals just fail to connect.
     */
    private static boolean isIpv6Literal(String s) {
        if (s.isEmpty() || s.indexOf(':') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static String localHostname() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return (name == null || name.isBlank()) ? null : name;
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}
