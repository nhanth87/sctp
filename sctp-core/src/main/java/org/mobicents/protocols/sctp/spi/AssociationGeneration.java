package org.mobicents.protocols.sctp.spi;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic generation stamped on every association UP. Payloads and events
 * carrying an older generation after reconnect are dropped (stale-delivery fence).
 */
public final class AssociationGeneration {
    private final AtomicLong current = new AtomicLong(0L);

    public long current() {
        return current.get();
    }

    public long nextUp() {
        return current.incrementAndGet();
    }

    public boolean accept(long observed) {
        return observed == current.get() && observed > 0L;
    }

    public void reset() {
        current.set(0L);
    }
}
