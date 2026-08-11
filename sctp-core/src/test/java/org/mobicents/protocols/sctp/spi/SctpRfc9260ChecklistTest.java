package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Maps RFC 9260 (obsoletes 4960) and RFC 4666 MUST rules to the layer that
 * owns them. Java/sctp-fs implements the ULP adapter + STP AIMD. Transport
 * cwnd/ssthresh/rwnd/INIT-COOKIE live in FreeBSD SCTP / usrsctp (DPDK gates).
 *
 * <p>Do not mark a DPDK row green from this class.
 */
class SctpRfc9260ChecklistTest {
    record Rule(String rfc, String clause, String must, Coverage coverage, String proof) {}

    enum Coverage { UNIT, DPDK_GATE }

    static final List<Rule> RULES = List.of(
            new Rule("RFC 9260", "6.1 A",
                    "MUST NOT transmit new DATA when peer rwnd cannot fit the next chunk (except zero-window probe)",
                    Coverage.UNIT, "AdaptiveSendControllerTest.rfc9260_6_1_rwndZeroStopsUserData"),
            new Rule("RFC 9260", "6.1 B",
                    "MUST NOT transmit new DATA when flightsize >= cwnd+(PMDCS-1)",
                    Coverage.DPDK_GATE, "ipv4-init-cookie-established / linux-kernel-sctp-peer"),
            new Rule("RFC 9260", "6.1 C",
                    "MUST retransmit marked DATA before new DATA",
                    Coverage.DPDK_GATE, "heartbeat-rto"),
            new Rule("RFC 9260", "7 / 7.2",
                    "slow-start + congestion avoidance MUST be used; MUST NOT be more aggressive than §7",
                    Coverage.UNIT, "AdaptiveSendControllerTest.rfc9260_7_mustNotBeMoreAggressiveAfterLoss"),
            new Rule("RFC 9260", "7.2",
                    "congestion control is association-wide, not per stream",
                    Coverage.UNIT, "AdaptiveSendControllerTest.rfc9260_7_2_congestionIsAssociationWide"),
            new Rule("RFC 9260", "7.2",
                    "MUST keep cwnd/ssthresh/partial_bytes_acked per destination when multi-homed",
                    Coverage.DPDK_GATE, "multihoming-failover"),
            new Rule("RFC 9260", "7.2.1",
                    "initial cwnd = min(4*PMDCS, max(2*PMDCS, 4404/4344)); after RTO cwnd <= PMDCS",
                    Coverage.DPDK_GATE, "heartbeat-rto"),
            new Rule("RFC 9260", "7.2.3",
                    "on loss: ssthresh=max(cwnd/2, 4*PMDCS), cwnd=ssthresh; on T3-rtx cwnd=PMDCS",
                    Coverage.UNIT, "AdaptiveSendControllerTest.repeatedSconAtSameLevelHalvesAgain"),
            new Rule("RFC 9260", "7.2.4",
                    "Fast Retransmit after three miss indications",
                    Coverage.DPDK_GATE, "linux-kernel-sctp-peer"),
            new Rule("RFC 9260", "5 / 4-way",
                    "INIT / INIT-ACK / COOKIE-ECHO / COOKIE-ACK before user DATA",
                    Coverage.DPDK_GATE, "ipv4-init-cookie-established"),
            new Rule("RFC 9260", "9",
                    "graceful SHUTDOWN vs ABORT; restart generation must drop stale TSN/payload",
                    Coverage.UNIT, "AssociationGenerationTest.rejectsStaleAfterReconnect"),
            new Rule("RFC 4666", "1.4.7",
                    "M3UA DATA MUST NOT use stream 0; ASPSM/MGMT/RKM SHOULD use stream 0",
                    Coverage.UNIT, "AdaptiveSendControllerTest.streamZeroNeverThrottled"),
            new Rule("RFC 4666", "1.3.2 / 3.4.4",
                    "SCON congestion level 0..3; ASP MUST reduce traffic (or STP bans)",
                    Coverage.UNIT, "AdaptiveSendControllerTest.rfc4666_sconLevelsClampedAndHonored"),
            new Rule("RFC 4666", "M3UA over SCTP",
                    "PPID 3 DATA (stream!=0) MUST be complete+ordered; stream 0 ASPSM MAY be unordered",
                    Coverage.UNIT, "FstackRfc4666Test.m3uaPpid3RejectsUnorderedOrIncomplete")
    );

    @Test
    void everyMustHasAProofAndUnitRulesAreMajorityOfAdapterScope() {
        assertEquals(14, RULES.size());
        long unit = RULES.stream().filter(r -> r.coverage == Coverage.UNIT).count();
        long dpdk = RULES.stream().filter(r -> r.coverage == Coverage.DPDK_GATE).count();
        assertEquals(14, unit + dpdk);
        assertTrue(unit >= 7, "adapter/ULP MUSTs must stay unit-tested, got " + unit);
        RULES.forEach(r -> {
            assertFalse(r.proof.isBlank(), r.rfc + " " + r.clause);
            assertFalse(r.must.isBlank(), r.rfc + " " + r.clause);
        });
    }

    @Test
    void dpdkTransportRulesStayGated() {
        assertTrue(RULES.stream().anyMatch(r ->
                r.coverage == Coverage.DPDK_GATE && r.clause.startsWith("7.2.1")));
        assertTrue(RULES.stream().anyMatch(r ->
                r.coverage == Coverage.DPDK_GATE && r.clause.contains("4-way")));
    }
}
