package com.javalearn.lab.chaos.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the output of the read-only membership probe into a {@link ClusterMembership}.
 *
 * <p>Two shapes are accepted, both produced by the lab today:</p>
 * <ul>
 *   <li>the direct four-column query ({@code SELECT MEMBER_HOST, MEMBER_PORT,
 *       MEMBER_STATE, MEMBER_ROLE ...}) rendered by {@code mysql --batch --raw
 *       --skip-column-names}: one tab-separated row per member;</li>
 *   <li>the {@code db-cluster-status.sh} rollup, which concatenates
 *       {@code host:port STATE ROLE} segments with {@code ' | '} (the format the
 *       operator sees in the status script).</li>
 * </ul>
 *
 * <p>Rows are separated by newlines and/or {@code |}; columns by tabs or spaces.
 * An optional {@code MEMBER_HOST...} header line is skipped. Anything that
 * cannot be interpreted as a complete row, an empty result, a duplicate
 * {@code MEMBER_HOST} or more than {@link #MAX_MEMBERS} rows is a probe failure
 * ({@link MysqlClusterProbeException}) - never a silently empty snapshot,
 * because an empty snapshot would clear every series and look like "cluster
 * gone" instead of "probe broken".</p>
 */
public final class ClusterMembershipParser {

    /** Hard upper bound on rows; keeps the member/role/state label set bounded. */
    public static final int MAX_MEMBERS = 16;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_ECHO = 120;

    private ClusterMembershipParser() {
    }

    public static ClusterMembership parse(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            throw new MysqlClusterProbeException("membership query returned no output");
        }
        Map<String, ClusterMember> members = new LinkedHashMap<>();
        for (String line : stdout.split("\\R")) {
            for (String segment : line.split("\\|")) {
                String row = segment.trim();
                if (row.isEmpty() || isHeader(row)) {
                    continue;
                }
                ClusterMember member = parseRow(row);
                ClusterMember previous = members.putIfAbsent(member.member(), member);
                if (previous != null) {
                    throw new MysqlClusterProbeException("duplicate MEMBER_HOST '" + member.member()
                            + "' in membership output");
                }
                if (members.size() > MAX_MEMBERS) {
                    throw new MysqlClusterProbeException("membership output has more than "
                            + MAX_MEMBERS + " rows; refusing to publish unbounded cardinality");
                }
            }
        }
        if (members.isEmpty()) {
            throw new MysqlClusterProbeException("membership query returned no member rows");
        }
        return new ClusterMembership(members.values().stream().toList());
    }

    private static boolean isHeader(String row) {
        String[] tokens = WHITESPACE.split(row);
        return tokens.length > 0 && "MEMBER_HOST".equalsIgnoreCase(tokens[0]);
    }

    private static ClusterMember parseRow(String row) {
        String[] tokens = WHITESPACE.split(row);
        String host;
        String port;
        String state;
        String role;
        if (tokens.length == 4) {
            // MEMBER_HOST MEMBER_PORT MEMBER_STATE MEMBER_ROLE (tab or space separated).
            host = tokens[0];
            port = tokens[1];
            state = tokens[2];
            role = tokens[3];
        } else if (tokens.length == 3) {
            // host:port STATE ROLE (db-cluster-status.sh GROUP_CONCAT format).
            int colon = tokens[0].lastIndexOf(':');
            if (colon <= 0 || colon == tokens[0].length() - 1) {
                throw new MysqlClusterProbeException("member row is missing host:port: '" + echo(row) + "'");
            }
            host = tokens[0].substring(0, colon);
            port = tokens[0].substring(colon + 1);
            state = tokens[1];
            role = tokens[2];
        } else {
            throw new MysqlClusterProbeException("unparseable membership row: '" + echo(row) + "'");
        }

        host = host.trim();
        if (host.isEmpty() || host.contains(":")) {
            throw new MysqlClusterProbeException("invalid MEMBER_HOST in row: '" + echo(row) + "'");
        }
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new MysqlClusterProbeException("invalid MEMBER_PORT '" + echo(port) + "' in row: '" + echo(row) + "'");
        }
        if (portNumber < 1 || portNumber > 65535) {
            throw new MysqlClusterProbeException("MEMBER_PORT out of range in row: '" + echo(row) + "'");
        }
        return new ClusterMember(host, portNumber, MemberState.from(state), MemberRole.from(role));
    }

    private static String echo(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_ECHO ? trimmed : trimmed.substring(0, MAX_ECHO) + "...";
    }
}
