package org.mobicents.protocols.sctp.spi;

/**
 * Build-time / boot-time SCTP provider. Native GMLC may only load {@link #FSTACK_DPDK}.
 * {@link #NETTY_KERNEL} stays on the JVM oracle path and must not be reachable from a
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
