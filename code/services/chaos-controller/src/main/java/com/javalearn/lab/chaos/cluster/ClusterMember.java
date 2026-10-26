package com.javalearn.lab.chaos.cluster;

/**
 * One row of the InnoDB Cluster membership view.
 *
 * @param member {@code MEMBER_HOST} - the identity used as the Prometheus
 *               {@code member} label (compose sets report_host to
 *               {@code mysql-1/2/3}, i.e. without the {@code jl-} container prefix)
 * @param port   {@code MEMBER_PORT}; validated but not a label (this lab has one
 *               listener per member, so host alone identifies a row)
 * @param state  bounded state vocabulary
 * @param role   bounded role vocabulary
 */
public record ClusterMember(String member, int port, MemberState state, MemberRole role) {

    public boolean isPrimary() {
        return role == MemberRole.PRIMARY;
    }
}
