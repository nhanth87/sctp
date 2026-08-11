package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SctpCongestionSampleTest {
    @Test
    void ringFillMapsToQ704Levels() {
        assertEquals(0, SctpCongestionSample.levelFromRing(0, 1024));
        assertEquals(0, SctpCongestionSample.levelFromRing(100, 1024));
        assertEquals(1, SctpCongestionSample.levelFromRing(512, 1024));
        assertEquals(2, SctpCongestionSample.levelFromRing(820, 1024));
        assertEquals(3, SctpCongestionSample.levelFromRing(980, 1024));
        assertEquals(3, SctpCongestionSample.levelFromRing(1024, 1024));
    }

    @Test
    void rwndClosedIsSevere() {
        assertEquals(3, SctpCongestionSample.levelFromRwnd(0, 16_000));
        assertEquals(2, SctpCongestionSample.levelFromRwnd(3_000, 16_000));
        assertEquals(1, SctpCongestionSample.levelFromRwnd(7_000, 16_000));
        assertEquals(0, SctpCongestionSample.levelFromRwnd(12_000, 16_000));
    }
}
