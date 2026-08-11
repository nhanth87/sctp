package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;

class MappedSpscRingTest {
    @TempDir
    Path tmp;

    @Test
    void offerPollAndBackpressure() throws Exception {
        Path ringPath = tmp.resolve("tx.ring");
        try (MappedSpscRing writer = new MappedSpscRing(ringPath, true);
             MappedSpscRing reader = new MappedSpscRing(ringPath, false)) {
            SctpRecord sent = new SctpRecord(7, 1, SctpEventKind.PAYLOAD, 1, SctpRecord.M3UA_PPID,
                    false, 0, 16, 16, new byte[] {3, 4, 5});
            assertTrue(writer.offer(sent));
            SctpRecord got = reader.poll();
            assertNotNull(got);
            assertEquals(7, got.associationId());
            assertEquals(1, got.generation());
            assertEquals(SctpRecord.M3UA_PPID, got.ppid());
            assertEquals(3, got.payloadBytes().length);
            assertFalse(writer.congested());
            assertEquals(0, writer.used());
            assertEquals(MappedSpscRing.DEFAULT_CAPACITY, writer.capacity());
        }
        assertTrue(Files.size(ringPath) > 0);
    }

    @Test
    void peekKeepsSlotUntilRelease() throws Exception {
        Path ringPath = tmp.resolve("rx.ring");
        try (MappedSpscRing writer = new MappedSpscRing(ringPath, true);
             MappedSpscRing reader = new MappedSpscRing(ringPath, false)) {
            assertTrue(writer.offer(new SctpRecord(1, 1, SctpEventKind.PAYLOAD, 0,
                    SctpRecord.M3UA_PPID, false, 0, 16, 16, new byte[] {9})));
            SctpRecord view = reader.peek();
            assertNotNull(view);
            assertEquals(9, view.payloadBytes()[0]);
            assertEquals(9, reader.peek().payloadBytes()[0]);
            reader.release();
            assertNull(reader.peek());
        }
    }
}
