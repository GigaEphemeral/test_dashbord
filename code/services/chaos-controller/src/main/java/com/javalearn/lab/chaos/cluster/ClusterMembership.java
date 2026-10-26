package com.javalearn.lab.chaos.cluster;

import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of one {@code replication_group_members} response.
 *
 * <p>A healthy single-primary group has one PRIMARY. More than one PRIMARY is a
 * split brain: the snapshot keeps every row (the metrics stay truthful) and the
 * monitor logs a WARN, but nothing is silently picked as "the" primary.</p>
 */
public record ClusterMembership(List<ClusterMember> members) {

    public ClusterMembership {
        members = List.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    public List<ClusterMember> primaries() {
        return members.stream().filter(ClusterMember::isPrimary).toList();
    }

    public long primaryCount() {
        return members.stream().filter(ClusterMember::isPrimary).count();
    }

    /** The single primary, when exactly one is reported. */
    public Optional<ClusterMember> primary() {
        List<ClusterMember> primaries = primaries();
        return primaries.size() == 1 ? Optional.of(primaries.get(0)) : Optional.empty();
    }
}
