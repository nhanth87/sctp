package org.mobicents.protocols.sctp.spi;

/**
 * Who raised the current congestion level. Effective level is {@code max} across
 * sources so a cleared ring does not hide an outstanding M3UA SCON from the STP.
 */
public enum SctpCongestionSource {
    NONE,
    /** SHM / sidecar TX ring full or native {@code EVT_CONGESTION}. */
    SCTP_RING,
    /** Future F-Stack RWND / cwnd (RFC 4960). */
    SCTP_RWND,
    /** MTP-STATUS SignallingNetworkCongested from M3UA SCON (RFC 4666 / Q.704). */
    M3UA_SCON,
    /** Dataplane send threw (peer not taking data). */
    SEND_FAIL,
    /** Test / operator. */
    MANUAL
}
