package org.mobicents.protocols.sctp.spi;

/**
 * Build-time / boot-time SCTP provider. {@link #FSTACK_DPDK} is the default.
 * {@link #NETTY_KERNEL} is the JVM-only fallback and must not be reachable from a
 * GraalVM native image (it pulls {@code com.sun.nio.sctp}).
 */
public enum SctpBackend {
    FSTACK_DPDK,
    NETTY_KERNEL;

    public static SctpBackend from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FSTACK_DPDK;
        }
        return valueOf(raw.trim().toUpperCase().replace('-', '_'));
    }

    public boolean nativeSafe() {
        return this == FSTACK_DPDK;
    }
}
