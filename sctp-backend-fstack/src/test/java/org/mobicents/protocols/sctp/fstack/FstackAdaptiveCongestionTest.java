package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.AdaptiveSendController;
import org.mobicents.protocols.sctp.spi.AdaptiveSendPolicy;
import org.mobicents.protocols.sctp.spi.SctpCongestionRejectedException;
import org.mobicents.protocols.sctp.spi.SctpCongestionSource;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class FstackAdaptiveCongestionTest {
    @Test
    void userDataRejectedAfterSconSevereButStreamZeroPasses() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("cong");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.setAdaptiveController(new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10)));
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 3905, "127.0.0.1", 3906, "c",
                IpChannelType.SCTP, null);
        Association server = mgmt.addAssociation("127.0.0.1", 3906, "127.0.0.1", 3905, "s",
                IpChannelType.SCTP, null);
        mgmt.startAssociation("c");
        mgmt.startAssociation("s");

        mgmt.adaptiveController().noteSignal(SctpCongestionSource.M3UA_SCON, 3);
        assertThrows(SctpCongestionRejectedException.class,
                () -> client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, false,
                        SctpRecord.M3UA_PPID, 1)));
        assertDoesNotThrow(() -> client.send(new PayloadData(4, new byte[] {9, 9, 9, 9}, true, false,
                SctpRecord.M3UA_PPID, 0)));
        assertEquals(3, mgmt.adaptiveController().snapshot().level());
        assertTrue(server.isUp());
        mgmt.stop();
    }

    @Test
    void ringCongestionEventFeedsController() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("ring");
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        FstackAssociationImpl assoc = (FstackAssociationImpl) mgmt.addAssociation(
                "127.0.0.1", 1, "127.0.0.1", 2, "a", IpChannelType.SCTP, null);
        mgmt.startAssociation("a");
        assoc.deliver(new SctpRecord(assoc.associationId(), assoc.generation(),
                SctpEventKind.CONGESTION, 0, SctpRecord.M3UA_PPID, false, 2, 16, 16, new byte[0]));
        assertEquals(2, assoc.getCongestionLevel());
        assertEquals(2, mgmt.adaptiveController().snapshot().level());
        assertEquals(SctpCongestionSource.SCTP_RING, mgmt.adaptiveController().snapshot().source());
        assertEquals(assoc.associationId(), mgmt.adaptiveController().latestSample().associationId());
        assertEquals(assoc.generation(), mgmt.adaptiveController().latestSample().generation());
        mgmt.stop();
    }
}
