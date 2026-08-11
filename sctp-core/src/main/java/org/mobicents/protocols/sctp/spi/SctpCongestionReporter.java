package org.mobicents.protocols.sctp.spi;

/**
 * Upper-service hook (GMLC / USSDGW / OTA / admin). Fired only on level
 * transitions — never per packet.
 */
public interface SctpCongestionReporter {
    void onCongestion(SctpCongestionSnapshot snapshot);

    void onCongestionAbated(SctpCongestionSnapshot snapshot);
}
