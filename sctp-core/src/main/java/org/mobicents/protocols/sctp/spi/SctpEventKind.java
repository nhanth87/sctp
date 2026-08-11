package org.mobicents.protocols.sctp.spi;

/** Lifecycle events that must stay ordered relative to payloads on one association. */
public enum SctpEventKind {
    UP,
    SHUTDOWN,
    LOST,
    RESTART,
    PAYLOAD,
    CONGESTION,
    PEER_ADDRESS_CHANGED,
    PEER_UNREACHABLE,
    PEER_REACHABLE
}
