package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedSpscRingTest {
    @Test
    void backpressureWhenFull() {
        BoundedSpscRing<String> ring = new BoundedSpscRing<>(2);
        assertTrue(ring.offer("a"));
        assertTrue(ring.offer("b"));
        assertFalse(ring.offer("c"));
        assertEquals("a", ring.poll());
        assertTrue(ring.offer("c"));
        assertEquals("b", ring.poll());
        assertEquals("c", ring.poll());
        assertNull(ring.poll());
    }
}
