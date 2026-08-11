package org.mobicents.protocols.sctp.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * One realtime congestion observation. Controllers ingest every sample — not
 * only level transitions. Unknown telemetry stays 0.
 */
public record SctpCongestionSample(
        Instant observedAt,
        SctpCongestionSource source,
        int level,
        long associationId,
        long generation,
        int affectedDpc,
        int ringUsed,
        int ringCapacity,
        int rwndBytes,
        int flightBytes,
        int cwndBytes,
        int streamId
) {
    public static final SctpCongestionSample NONE = new SctpCongestionSample(
            Instant.EPOCH, SctpCongestionSource.NONE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public SctpCongestionSample {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(source, "source");
        level = Math.max(0, Math.min(3, level));
        if (ringUsed < 0 || ringCapacity < 0 || rwndBytes < 0 || flightBytes < 0 || cwndBytes < 0) {
            throw new IllegalArgumentException("telemetry must be >= 0");
        }
    }

    public int ringFillPercent() {
        if (ringCapacity <= 0) {
            return 0;
        }
        return (int) Math.min(100L, (ringUsed * 100L) / ringCapacity);
    }

    public static int levelFromRing(int used, int capacity) {
        if (capacity <= 0 || used <= 0) {
            return 0;
        }
        if (used >= capacity) {
            return 3;
        }
        int pct = (int) ((used * 100L) / capacity);
        if (pct >= 95) {
            return 3;
        }
        if (pct >= 80) {
            return 2;
        }
        if (pct >= 50) {
            return 1;
        }
        return 0;
    }

    public static int levelFromRwnd(int rwnd, int cwnd) {
        if (rwnd <= 0) {
            return 3;
        }
        if (cwnd > 0 && rwnd < cwnd / 4) {
            return 2;
        }
        if (cwnd > 0 && rwnd < cwnd / 2) {
            return 1;
        }
        return 0;
    }

    public static SctpCongestionSample signal(SctpCongestionSource source, int level) {
        return new SctpCongestionSample(Instant.now(), source, level, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static SctpCongestionSample scon(int affectedDpc, int level) {
        return new SctpCongestionSample(Instant.now(), SctpCongestionSource.M3UA_SCON, level,
                0, 0, affectedDpc, 0, 0, 0, 0, 0, 0);
    }

    public static SctpCongestionSample ring(long associationId, long generation, int used, int capacity) {
        return new SctpCongestionSample(Instant.now(), SctpCongestionSource.SCTP_RING,
                levelFromRing(used, capacity), associationId, generation, 0, used, capacity, 0, 0, 0, 0);
    }

    public static SctpCongestionSample ringLevel(long associationId, long generation, int level) {
        return new SctpCongestionSample(Instant.now(), SctpCongestionSource.SCTP_RING, level,
                associationId, generation, 0, 0, 0, 0, 0, 0, 0);
    }

    public static SctpCongestionSample rwnd(long associationId, long generation, int rwnd, int flight, int cwnd) {
        return new SctpCongestionSample(Instant.now(), SctpCongestionSource.SCTP_RWND,
                levelFromRwnd(rwnd, cwnd), associationId, generation, 0, 0, 0, rwnd, flight, cwnd, 0);
    }

    public static SctpCongestionSample sendFail(long associationId, long generation) {
        return new SctpCongestionSample(Instant.now(), SctpCongestionSource.SEND_FAIL, 1,
                associationId, generation, 0, 0, 0, 0, 0, 0, 0);
    }
}
