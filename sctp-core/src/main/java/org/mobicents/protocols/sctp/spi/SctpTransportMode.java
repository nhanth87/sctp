package org.mobicents.protocols.sctp.spi;

/**
 * How the F-Stack data plane is attached. Both modes share the same C ABI.
 * Sidecar is the first production path; in-process FFM is gated behind soak.
 */
public enum SctpTransportMode {
    SIDECAR,
    IN_PROCESS;

    public static SctpTransportMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SIDECAR;
        }
        return valueOf(raw.trim().toUpperCase().replace('-', '_'));
    }
}
