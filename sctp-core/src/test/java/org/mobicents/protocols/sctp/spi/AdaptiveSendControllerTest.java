package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AdaptiveSendControllerTest {
    private static AdaptiveSendPolicy policy(int maxTps) {
        return new AdaptiveSendPolicy(maxTps, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10);
    }

    @Test
    void streamZeroNeverThrottled() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 3);
        assertFalse(ctl.tryAcquireUserData());
        assertTrue(ctl.tryAcquire(0));
        assertFalse(ctl.canAdmitNewWork());
    }

    @Test
    void sconLevel1CutsPermittedTpsInHalf() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        assertEquals(1, ctl.snapshot().level());
        assertEquals(500, ctl.snapshot().permittedTps());
        assertEquals(SctpCongestionSource.M3UA_SCON, ctl.snapshot().source());
        assertTrue(ctl.snapshot().throttling());
    }

    @Test
    void repeatedSconAtSameLevelHalvesAgain() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        assertEquals(250, ctl.snapshot().permittedTps());
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        assertEquals(125, ctl.snapshot().permittedTps());
    }

    @Test
    void severePausesUserDataAndBlocksHttpAdmit() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 3);
        assertEquals(100, ctl.snapshot().permittedTps());
        assertFalse(ctl.tryAcquire(1));
        assertFalse(ctl.canAdmitNewWork());
        assertTrue(ctl.snapshot().retryAfterSeconds() >= 5);
    }

    @Test
    void ringDoesNotHideOutstandingScon() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 2);
        ctl.noteSignal(SctpCongestionSource.SCTP_RING, 1);
        assertEquals(2, ctl.snapshot().level());
        assertEquals(SctpCongestionSource.M3UA_SCON, ctl.snapshot().source());
        ctl.noteSignal(SctpCongestionSource.SCTP_RING, 0);
        assertEquals(2, ctl.snapshot().level());
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 0);
        assertEquals(0, ctl.snapshot().level());
    }

    @Test
    void holdAppliesWhenClockStartsAtZero() {
        AtomicLong clock = new AtomicLong(0);
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000), clock::get);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        clock.addAndGet(1_000_000_000L);
        ctl.canAdmitNewWork();
        assertEquals(500, ctl.snapshot().permittedTps(), "RFC 9260 §7: hold must apply even if nanoTime origin is 0");
    }

    @Test
    void additiveIncreaseAfterHoldNotSnapBack() {
        AtomicLong clock = new AtomicLong(0);
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000), clock::get);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        assertEquals(500, ctl.snapshot().permittedTps());
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 0);
        assertEquals(500, ctl.snapshot().permittedTps());
        clock.addAndGet(2_000_000_000L);
        ctl.canAdmitNewWork();
        clock.addAndGet(1_000_000_000L);
        ctl.canAdmitNewWork();
        int recovered = ctl.snapshot().permittedTps();
        assertTrue(recovered > 500 && recovered < 1000, "got " + recovered);
    }

    @Test
    void tokenBucketRejectsBurstAbovePermittedTps() {
        AtomicLong clock = new AtomicLong(0);
        AdaptiveSendController ctl = new AdaptiveSendController(policy(20), clock::get);
        clock.addAndGet(1_000_000_000L);
        int accepted = 0;
        for (int i = 0; i < 80; i++) {
            if (ctl.tryAcquire(1)) {
                accepted++;
            }
        }
        assertTrue(accepted <= 40, "burst accepted=" + accepted);
        clock.addAndGet(1_000_000_000L);
        assertTrue(ctl.tryAcquire(1));
    }

    @Test
    void reportersFireOnlyOnLevelTransition() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        AtomicInteger start = new AtomicInteger();
        AtomicInteger finish = new AtomicInteger();
        ctl.addReporter(new SctpCongestionReporter() {
            @Override public void onCongestion(SctpCongestionSnapshot snapshot) { start.incrementAndGet(); }
            @Override public void onCongestionAbated(SctpCongestionSnapshot snapshot) { finish.incrementAndGet(); }
        });
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 0);
        assertEquals(1, start.get());
        assertEquals(1, finish.get());
    }

    @Test
    void unlimitedUntilFirstSignalThenUsesOfferedCeiling() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(0));
        assertTrue(ctl.canAdmitNewWork());
        for (int i = 0; i < 50; i++) {
            assertTrue(ctl.tryAcquire(1));
        }
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        assertTrue(ctl.snapshot().permittedTps() <= 500);
        assertTrue(ctl.snapshot().permittedTps() >= 10);
    }

    @Test
    void disabledNeverThrottles() {
        AdaptiveSendController ctl = AdaptiveSendController.disabled();
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 3);
        assertTrue(ctl.tryAcquire(1));
        assertTrue(ctl.canAdmitNewWork());
        assertEquals(0, ctl.snapshot().level());
    }

    @Test
    void rfc9260_6_1_rwndZeroStopsUserData() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.SCTP_RWND, 3);
        assertFalse(ctl.tryAcquire(1), "RFC 9260 §6.1 A: rwnd closed → no new user DATA");
        assertTrue(ctl.tryAcquire(0), "zero-window probe / M3UA mgmt still allowed on stream 0");
        ctl.noteSignal(SctpCongestionSource.SCTP_RWND, 0);
        assertTrue(ctl.canAdmitNewWork());
    }

    @Test
    void rfc9260_7_2_congestionIsAssociationWide() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.SCTP_RING, 3);
        assertFalse(ctl.tryAcquire(1));
        assertFalse(ctl.tryAcquire(7), "RFC 9260 §7.2: one cwnd for the association, not per stream");
        assertTrue(ctl.tryAcquire(0));
    }

    @Test
    void rfc9260_7_mustNotBeMoreAggressiveAfterLoss() {
        AtomicLong clock = new AtomicLong(0);
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000), clock::get);
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 1);
        clock.addAndGet(1_000_000_000L);
        int accepted = 0;
        for (int i = 0; i < 1000; i++) {
            if (ctl.tryAcquire(1)) {
                accepted++;
            }
        }
        assertTrue(accepted <= 500, "RFC 9260 §7: MUST NOT be more aggressive than MD; accepted=" + accepted);
        assertTrue(accepted >= 1, "still probes at the cut rate");
    }

    @Test
    void rfc4666_sconLevelsClampedAndHonored() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 99);
        assertEquals(3, ctl.snapshot().level());
        assertFalse(ctl.tryAcquire(1));
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, -4);
        assertEquals(0, ctl.snapshot().level());
        assertTrue(ctl.canAdmitNewWork());
        ctl.noteSignal(SctpCongestionSource.M3UA_SCON, 2);
        assertEquals(100, ctl.snapshot().permittedTps(),
                "already below the level-2 fraction after severe — keep conservative rate");
        AdaptiveSendController fresh = new AdaptiveSendController(policy(1000));
        fresh.noteSignal(SctpCongestionSource.M3UA_SCON, 2);
        assertEquals(250, fresh.snapshot().permittedTps());
    }

    @Test
    void sinksReceiveEveryRealtimeSampleIncludingSameLevel() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger lastDpc = new AtomicInteger();
        ctl.addSink(sample -> {
            samples.incrementAndGet();
            lastDpc.set(sample.affectedDpc());
        });
        ctl.noteSample(SctpCongestionSample.scon(2222, 1));
        ctl.noteSample(SctpCongestionSample.scon(2222, 1));
        assertEquals(2222, lastDpc.get());
        ctl.noteSample(SctpCongestionSample.rwnd(7, 3, 0, 8000, 16000));
        assertEquals(3, samples.get());
        assertEquals(0, ctl.latestSample().rwndBytes());
        assertEquals(8000, ctl.latestSample().flightBytes());
        assertEquals(SctpCongestionSource.SCTP_RWND, ctl.latestSample().source());
        assertEquals(3, ctl.snapshot().level());
        assertEquals(SctpCongestionSource.SCTP_RWND, ctl.snapshot().source());
        assertEquals(7, ctl.snapshot().latest().associationId());
    }

    @Test
    void latestSampleKeepsSconDpcAndRingOccupancy() {
        AdaptiveSendController ctl = new AdaptiveSendController(policy(1000));
        ctl.noteSample(SctpCongestionSample.scon(404, 2));
        assertEquals(404, ctl.latestSample().affectedDpc());
        assertEquals(2, ctl.latestSample().level());
        ctl.noteSample(SctpCongestionSample.ring(9, 4, 980, 1024));
        assertEquals(9, ctl.latestSample().associationId());
        assertEquals(4, ctl.latestSample().generation());
        assertEquals(980, ctl.latestSample().ringUsed());
        assertEquals(1024, ctl.latestSample().ringCapacity());
        assertTrue(ctl.latestSample().ringFillPercent() >= 95);
        assertEquals(3, ctl.latestSample().level());
    }
}
