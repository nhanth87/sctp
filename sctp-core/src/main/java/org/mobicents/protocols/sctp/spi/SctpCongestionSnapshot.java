package org.mobicents.protocols.sctp.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of adaptive SCTP send control. Safe to put on admin JSON.
 * Level 0–3 matches Q.704 / M3UA SCON {@code CongestedIndication}.
 * {@link #latest()} is the last realtime sample (may differ from dominant source).
 */
public record SctpCongestionSnapshot(
        int level,
        SctpCongestionSource source,
        int permittedTps,
        int offeredTps,
        long accepted,
        long rejected,
        Instant lastSignalAt,
        boolean throttling,
        int retryAfterSeconds,
        SctpCongestionSample latest
) {
    public SctpCongestionSnapshot {
        if (level < 0 || level > 3) {
            throw new IllegalArgumentException("congestion level must be 0..3, got " + level);
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lastSignalAt, "lastSignalAt");
        Objects.requireNonNull(latest, "latest");
        if (permittedTps < 0 || offeredTps < 0 || accepted < 0 || rejected < 0 || retryAfterSeconds < 0) {
            throw new IllegalArgumentException("snapshot counters must be >= 0");
        }
    }

    public static SctpCongestionSnapshot idle() {
        return new SctpCongestionSnapshot(0, SctpCongestionSource.NONE, 0, 0, 0, 0,
                Instant.EPOCH, false, 0, SctpCongestionSample.NONE);
    }

    public void writeAdmin(Map<String, Object> dest) {
        dest.put("sctp.congestionLevel", level);
        dest.put("sctp.congestionSource", source.name());
        dest.put("sctp.permittedTps", permittedTps);
        dest.put("sctp.offeredTps", offeredTps);
        dest.put("sctp.throttling", throttling);
        dest.put("sctp.rejected", rejected);
        dest.put("sctp.retryAfterSeconds", retryAfterSeconds);
        dest.put("sctp.ringCongested", level > 0 && source == SctpCongestionSource.SCTP_RING);
        dest.put("sctp.affectedDpc", latest.affectedDpc());
        dest.put("sctp.associationId", latest.associationId());
        dest.put("sctp.ringUsed", latest.ringUsed());
        dest.put("sctp.ringCapacity", latest.ringCapacity());
        dest.put("sctp.ringFillPercent", latest.ringFillPercent());
        dest.put("sctp.rwndBytes", latest.rwndBytes());
        dest.put("sctp.flightBytes", latest.flightBytes());
        dest.put("sctp.cwndBytes", latest.cwndBytes());
        dest.put("sctp.sampleSource", latest.source().name());
        dest.put("sctp.sampleAt", latest.observedAt().toString());
    }
}
