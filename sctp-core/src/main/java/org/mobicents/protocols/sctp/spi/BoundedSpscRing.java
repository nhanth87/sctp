package org.mobicents.protocols.sctp.spi;

import java.util.Objects;

/** Bounded SPSC ring used by the sidecar shared-memory path and unit tests. */
public final class BoundedSpscRing<T> {
    private final Object[] slots;
    private final int mask;
    private long head;
    private long tail;

    public BoundedSpscRing(int capacityPow2) {
        if (capacityPow2 < 2 || (capacityPow2 & (capacityPow2 - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be power of two");
        }
        this.slots = new Object[capacityPow2];
        this.mask = capacityPow2 - 1;
    }

    public boolean offer(T value) {
        Objects.requireNonNull(value);
        if (tail - head >= slots.length) {
            return false;
        }
        slots[(int) (tail & mask)] = value;
        tail++;
        return true;
    }

    @SuppressWarnings("unchecked")
    public T poll() {
        if (head == tail) {
            return null;
        }
        int idx = (int) (head & mask);
        T value = (T) slots[idx];
        slots[idx] = null;
        head++;
        return value;
    }

    public int size() {
        return (int) (tail - head);
    }

    public boolean isFull() {
        return tail - head >= slots.length;
    }
}
