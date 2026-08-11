package org.mobicents.protocols.sctp.spi;

/**
 * Realtime consumer. Invoked on every sample (including same-level repeats).
 * Implementations must not allocate or log on the hot path.
 */
@FunctionalInterface
public interface SctpCongestionSink {
    void onSample(SctpCongestionSample sample);
}
