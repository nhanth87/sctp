package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.SctpCongestionRejectedException;
import org.mobicents.protocols.sctp.spi.SctpCongestionSource;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class FstackRfc4666Test {
    @Test
    void m3uaPpid3RejectsUnorderedOrIncomplete() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("rfc4666");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 4905, "127.0.0.1", 4906, "c",
                IpChannelType.SCTP, null);
        Association server = mgmt.addAssociation("127.0.0.1", 4906, "127.0.0.1", 4905, "s",
                IpChannelType.SCTP, null);
        client.setAssociationListener(noop());
        server.setAssociationListener(noop());
        mgmt.startAssociation("c");
        mgmt.startAssociation("s");

        assertThrows(IllegalArgumentException.class,
                () -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, true,
                        SctpRecord.M3UA_PPID, 1)),
                "RFC 4666 §1.4.7: M3UA DATA (stream != 0) MUST be ordered");
        assertThrows(IllegalArgumentException.class,
                () -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, false, false,
                        SctpRecord.M3UA_PPID, 1)),
                "RFC 4666: M3UA PPID 3 MUST be a complete SCTP user message");
        assertDoesNotThrow(() -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, true,
                SctpRecord.M3UA_PPID, 0)),
                "RFC 4666: ASPSM/MGMT on stream 0 MAY be unordered (jSS7 ASPUP)");
        assertDoesNotThrow(() -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, false,
                SctpRecord.M3UA_PPID, 1)));
        mgmt.stop();
    }

    @Test
    void rfc9260_rwndClosedRejectsUserDataOnSendPath() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("rwnd");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 4915, "127.0.0.1", 4916, "c",
                IpChannelType.SCTP, null);
        Association server = mgmt.addAssociation("127.0.0.1", 4916, "127.0.0.1", 4915, "s",
                IpChannelType.SCTP, null);
        client.setAssociationListener(noop());
        server.setAssociationListener(noop());
        mgmt.startAssociation("c");
        mgmt.startAssociation("s");

        mgmt.adaptiveController().noteSignal(SctpCongestionSource.SCTP_RWND, 3);
        assertThrows(SctpCongestionRejectedException.class,
                () -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, false,
                        SctpRecord.M3UA_PPID, 1)));
        assertDoesNotThrow(() -> client.send(new PayloadData(4, new byte[] {9, 9, 9, 9}, true, false,
                SctpRecord.M3UA_PPID, 0)));
        assertEquals(3, mgmt.adaptiveController().snapshot().level());
        assertTrue(client.isUp());
        mgmt.stop();
    }

    private static AssociationListener noop() {
        return new AssociationListener() {
            @Override public void onCommunicationUp(Association a, int in, int out) {}
            @Override public void onCommunicationShutdown(Association a) {}
            @Override public void onCommunicationLost(Association a) {}
            @Override public void onCommunicationRestart(Association a) {}
            @Override public void onPayload(Association a, PayloadData p) {}
            @Override public void inValidStreamId(PayloadData p) {}
        };
    }
}
