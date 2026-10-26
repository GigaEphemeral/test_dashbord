package com.javalearn.lab.chaos.cluster;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterMembershipParserTest {

    @Test
    void parsesTabSeparatedQueryRows() {
        String stdout = "mysql-1\t3306\tONLINE\tPRIMARY\n"
                + "mysql-2\t3306\tONLINE\tSECONDARY\n"
                + "mysql-3\t3306\tONLINE\tSECONDARY\n";

        ClusterMembership membership = ClusterMembershipParser.parse(stdout);

        assertThat(membership.size()).isEqualTo(3);
        assertThat(membership.primary()).map(ClusterMember::member).contains("mysql-1");
        assertThat(membership.members())
                .extracting(ClusterMember::role)
                .containsExactly(MemberRole.PRIMARY, MemberRole.SECONDARY, MemberRole.SECONDARY);
    }

    @Test
    void parsesTheStatusScriptRollupFormat() {
        String stdout = "mysql-3:3306 ONLINE SECONDARY | mysql-1:3306 ONLINE PRIMARY | mysql-2:3306 ONLINE SECONDARY";

        ClusterMembership membership = ClusterMembershipParser.parse(stdout);

        assertThat(membership.size()).isEqualTo(3);
        assertThat(membership.primary()).map(ClusterMember::member).contains("mysql-1");
        assertThat(membership.members()).extracting(ClusterMember::port).containsOnly(3306);
    }

    @Test
    void skipsTheHeaderLine() {
        String stdout = "MEMBER_HOST\tMEMBER_PORT\tMEMBER_STATE\tMEMBER_ROLE\n"
                + "mysql-1\t3306\tONLINE\tPRIMARY\n";

        assertThat(ClusterMembershipParser.parse(stdout).size()).isEqualTo(1);
    }

    @Test
    void foldsUnknownRoleAndStateIntoTheBoundedVocabulary() {
        ClusterMembership membership = ClusterMembershipParser.parse("mysql-9\t3306\tWEIRD\tBOGUS\n");

        assertThat(membership.members().get(0).state()).isEqualTo(MemberState.UNKNOWN);
        assertThat(membership.members().get(0).role()).isEqualTo(MemberRole.UNKNOWN);
        assertThat(membership.primary()).isEmpty();
    }

    @Test
    void rejectsBlankOutputInsteadOfPublishingAnEmptyCluster() {
        assertThatThrownBy(() -> ClusterMembershipParser.parse("   \n"))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("no output");
    }

    @Test
    void rejectsDuplicateMembers() {
        String stdout = "mysql-1\t3306\tONLINE\tPRIMARY\nmysql-1\t3306\tONLINE\tSECONDARY\n";

        assertThatThrownBy(() -> ClusterMembershipParser.parse(stdout))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void refusesUnboundedCardinality() {
        String rows = IntStream.rangeClosed(1, ClusterMembershipParser.MAX_MEMBERS + 1)
                .mapToObj(i -> "mysql-" + i + "\t3306\tONLINE\tSECONDARY")
                .collect(Collectors.joining("\n"));

        assertThatThrownBy(() -> ClusterMembershipParser.parse(rows))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("unbounded cardinality");
    }

    @Test
    void rejectsMalformedRows() {
        assertThatThrownBy(() -> ClusterMembershipParser.parse("mysql-1 3306"))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("unparseable");
        assertThatThrownBy(() -> ClusterMembershipParser.parse("mysql-1:3306	3306	ONLINE	PRIMARY"))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("MEMBER_HOST");
        assertThatThrownBy(() -> ClusterMembershipParser.parse("mysql-1\tnotaport\tONLINE\tPRIMARY"))
                .isInstanceOf(MysqlClusterProbeException.class)
                .hasMessageContaining("MEMBER_PORT");
    }
}
