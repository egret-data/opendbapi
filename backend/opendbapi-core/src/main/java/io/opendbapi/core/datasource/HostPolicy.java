package io.opendbapi.core.datasource;

import io.opendbapi.core.error.ApiProblem;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which hosts datasources may connect to. Cloud metadata services are denied by default; an
 * operator can deny more (e.g. the metadata database's own network) or allow only a list.
 * Entries are host names, {@code *.suffix} wildcards or CIDR ranges. Names are resolved, so an
 * alias of a denied address is denied too. DNS can still change between this check and the
 * connection; network policy is the stronger control and this is defence in depth.
 */
public class HostPolicy {

    public static final List<String> DEFAULT_DENIED = List.of(
            "169.254.0.0/16",        // link-local, incl. AWS/GCP/Azure/OpenStack metadata 169.254.169.254
            "fd00:ec2::254/128",     // AWS IPv6 metadata
            "100.100.100.200/32",    // Alibaba Cloud metadata
            "metadata.google.internal",
            "metadata.goog");

    private final List<String> denied;
    private final List<String> allowed;

    public HostPolicy(List<String> denied, List<String> allowed) {
        this.denied = denied == null ? List.of() : List.copyOf(denied);
        this.allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    public void check(String host) {
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        List<InetAddress> addresses = resolve(name);
        for (String rule : denied) {
            if (matches(rule, name, addresses)) {
                throw ApiProblem.badRequest("host_not_allowed",
                        "Connecting to %s is not allowed on this server.".formatted(host));
            }
        }
        if (!allowed.isEmpty() && allowed.stream().noneMatch(rule -> matches(rule, name, addresses))) {
            throw ApiProblem.badRequest("host_not_allowed",
                    "%s is not in the list of hosts datasources are allowed to reach. Ask the operator.".formatted(host));
        }
    }

    private static List<InetAddress> resolve(String name) {
        try {
            return List.of(InetAddress.getAllByName(name));
        } catch (UnknownHostException | SecurityException e) {
            return List.of(); // the connection attempt will report it
        }
    }

    private static boolean matches(String rule, String name, List<InetAddress> addresses) {
        String r = rule.trim().toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        if (r.contains("/")) {
            return addresses.stream().anyMatch(a -> inCidr(a, r));
        }
        if (r.startsWith("*.")) {
            return n.endsWith(r.substring(1));
        }
        if (n.equals(r)) {
            return true;
        }
        List<InetAddress> ruleAddresses = new ArrayList<>(resolveQuiet(r));
        return addresses.stream().anyMatch(ruleAddresses::contains);
    }

    private static List<InetAddress> resolveQuiet(String rule) {
        // Only literal addresses: resolving every rule name on each check would be slow.
        if (rule.matches("[0-9.]+") || rule.contains(":")) {
            return resolve(rule);
        }
        return List.of();
    }

    private static boolean inCidr(InetAddress address, String cidr) {
        String[] parts = cidr.split("/");
        try {
            InetAddress network = InetAddress.getByName(parts[0]);
            if (network.getAddress().length != address.getAddress().length) {
                return false;
            }
            int bits = Integer.parseInt(parts[1]);
            int total = network.getAddress().length * 8;
            BigInteger mask = BigInteger.ONE.shiftLeft(total).subtract(BigInteger.ONE)
                    .shiftRight(bits).not().and(BigInteger.ONE.shiftLeft(total).subtract(BigInteger.ONE));
            return new BigInteger(1, network.getAddress()).and(mask)
                    .equals(new BigInteger(1, address.getAddress()).and(mask));
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
