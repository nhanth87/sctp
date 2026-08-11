package org.mobicents.protocols.sctp.spi;

/**
 * Packet I/O underneath the SCTP state machine.
 * {@link #LOOPBACK} exists so unit tests and native smoke boots do not need a NIC.
 */
public enum SctpDataplane {
    DPDK,
    LOOPBACK;

    public static SctpDataplane from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DPDK;
        }
        return valueOf(raw.trim().toUpperCase().replace('-', '_'));
    }
}
