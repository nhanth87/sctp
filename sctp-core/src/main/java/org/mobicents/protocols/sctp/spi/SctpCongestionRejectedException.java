package org.mobicents.protocols.sctp.spi;

import java.util.Objects;

/**
 * User DATA was not sent because adaptive control is backing off the STP.
 * Stream 0 (M3UA ASPSM/ASPTM) never throws this. Fail-fast — never block the
 * SLEE thread.
 */
public final class SctpCongestionRejectedException extends IllegalStateException {
    private final SctpCongestionSnapshot snapshot;

    public SctpCongestionRejectedException(String association, SctpCongestionSnapshot snapshot) {
        super("SCTP congested assoc=" + association
                + " level=" + snapshot.level()
                + " source=" + snapshot.source()
                + " permittedTps=" + snapshot.permittedTps());
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public SctpCongestionSnapshot snapshot() {
        return snapshot;
    }
}
