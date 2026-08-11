package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AssociationGenerationTest {
    @Test
    void rejectsStaleAfterReconnect() {
        AssociationGeneration gen = new AssociationGeneration();
        assertFalse(gen.accept(0));
        long first = gen.nextUp();
        assertEquals(1L, first);
        assertTrue(gen.accept(first));
        long second = gen.nextUp();
        assertFalse(gen.accept(first));
        assertTrue(gen.accept(second));
    }

    @Test
    void recordCopiesPayload() {
        byte[] src = {1, 2, 3};
        SctpRecord rec = new SctpRecord(7, 1, SctpEventKind.PAYLOAD, 1, SctpRecord.M3UA_PPID,
                false, 0, 16, 16, src);
        src[0] = 9;
        assertEquals(1, rec.payloadBytes()[0]);
        assertTrue(rec.isM3ua());
    }
}
