package io.opendbapi.core.policy;

import io.opendbapi.core.error.ApiProblem;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** A single IP address or a CIDR range, IPv4 or IPv6. Never resolves host names. */
public record IpRange(String text, BigInteger network, BigInteger mask, int length) {

    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    public static IpRange parse(String text) {
        String t = text == null ? "" : text.strip();
        String[] parts = t.split("/", -1);
        if (parts.length > 2 || !LITERAL.matcher(parts[0]).matches() || (!parts[0].contains(".") && !parts[0].contains(":"))) {
            throw invalid(text);
        }
        byte[] bytes = literal(parts[0], text);
        int total = bytes.length * 8;
        int bits;
        try {
            bits = parts.length == 2 ? Integer.parseInt(parts[1]) : total;
        } catch (NumberFormatException e) {
            throw invalid(text);
        }
        if (bits < 0 || bits > total) {
            throw invalid(text);
        }
        BigInteger all = BigInteger.ONE.shiftLeft(total).subtract(BigInteger.ONE);
        BigInteger mask = all.shiftRight(bits).not().and(all);
        return new IpRange(t, new BigInteger(1, bytes).and(mask), mask, bytes.length);
    }

    public boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == length && new BigInteger(1, bytes).and(mask).equals(network);
    }

    /** Parses an IP literal (the getByName call cannot do DNS: input is checked to be numeric). */
    public static InetAddress address(String literal) {
        if (literal == null || !LITERAL.matcher(literal).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] literal(String s, String original) {
        InetAddress a = address(s);
        if (a == null) {
            throw invalid(original);
        }
        return a.getAddress();
    }

    private static ApiProblem invalid(String text) {
        return ApiProblem.badRequest("invalid_ip_range", "'%s' is not an IP address or CIDR range.".formatted(text));
    }
}
